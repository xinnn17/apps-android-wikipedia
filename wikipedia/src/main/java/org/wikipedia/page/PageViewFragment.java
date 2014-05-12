package org.wikipedia.page;

import android.content.*;
import android.os.*;
import android.support.v4.app.*;
import android.util.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import org.mediawiki.api.json.*;
import org.wikipedia.*;
import org.wikipedia.analytics.*;
import org.wikipedia.bridge.*;
import org.wikipedia.editing.*;
import org.wikipedia.events.*;
import org.wikipedia.history.*;
import org.wikipedia.pageimages.*;
import org.wikipedia.bookmarks.*;
import org.wikipedia.styledviews.*;

import java.util.*;

public class PageViewFragment extends Fragment {
    private static final String KEY_TITLE = "title";
    private static final String KEY_PAGE = "page";
    private static final String KEY_STATE = "state";
    private static final String KEY_SCROLL_Y = "scrollY";
    private static final String KEY_CURRENT_HISTORY_ENTRY = "currentHistoryEntry";
    private static final String KEY_QUICK_RETURN_BAR_ID = "quickReturnBarId";

    public static final int STATE_NO_FETCH = 1;
    public static final int STATE_INITIAL_FETCH = 2;
    public static final int STATE_COMPLETE_FETCH = 3;

    private int state = STATE_NO_FETCH;

    private PageTitle title;
    private ObservableWebView webView;
    private ProgressBar loadProgress;
    private View networkError;
    private View retryButton;
    private View pageDoesNotExistError;
    private DisableableDrawerLayout tocDrawer;
    private FrameLayout pageFragmentContainer;

    private Page page;
    private HistoryEntry curEntry;

    private CommunicationBridge bridge;
    private LinkHandler linkHandler;
    private EditHandler editHandler;

    private WikipediaApp app;
    private Api api;

    private int scrollY;
    private int quickReturnBarId;

    private View quickReturnBar;

    private ReadingActionFunnel readingActionFunnel;

    // Pass in the id rather than the View object itself for the quickReturn bar, to help it survive rotates
    public PageViewFragment(PageTitle title, HistoryEntry historyEntry, int quickReturnBarId) {
        this.title = title;
        this.curEntry = historyEntry;
        this.quickReturnBarId = quickReturnBarId;
    }

    public PageViewFragment() {
    }

    public PageTitle getTitle() {
        return title;
    }

    public Page getPage() {
        return page;
    }

    /*
    Hide the entire fragment. This is necessary when displaying a new page fragment on top
    of a previous one -- some devices have issues with rendering "heavy" components
    (like WebView) when overlaid on top of many other Views.
     */
    public void hide() {
        pageFragmentContainer.setVisibility(View.GONE);
    }

    /*
    Make this fragment visible. Make sure to call this when going "back" through the
    stack of fragments
     */
    public void show() {
        pageFragmentContainer.setVisibility(View.VISIBLE);
    }

    private void displayLeadSection() {
        JSONObject leadSectionPayload = new JSONObject();
        try {
            leadSectionPayload.put("title", page.getDisplayTitle());
            leadSectionPayload.put("section", page.getSections().get(0).toJSON());

            bridge.sendMessage("displayLeadSection", leadSectionPayload);

            JSONObject attributionPayload = new JSONObject();
            String lastUpdatedText = getString(R.string.last_updated_text, Utils.formatDateRelative(page.getPageProperties().getLastModified()));
            attributionPayload.put("historyText", lastUpdatedText);
            attributionPayload.put("historyTarget", page.getTitle().getUriForAction("history"));
            attributionPayload.put("licenseHTML", getString(R.string.content_license_html));
            bridge.sendMessage("displayAttribution", attributionPayload);
        } catch (JSONException e) {
            // This should never happen
            throw new RuntimeException(e);
        }

        ViewAnimations.crossFade(loadProgress, webView);
    }

    private void populateNonLeadSections() {
        bridge.sendMessage("startSectionsDisplay", new JSONObject());
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putParcelable(KEY_TITLE, title);
        outState.putParcelable(KEY_PAGE, page);
        outState.putInt(KEY_STATE, state);
        outState.putInt(KEY_SCROLL_Y, webView.getScrollY());
        outState.putParcelable(KEY_CURRENT_HISTORY_ENTRY, curEntry);
        outState.putInt(KEY_QUICK_RETURN_BAR_ID, quickReturnBarId);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, final Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_page, container, false);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        if (savedInstanceState != null && savedInstanceState.containsKey(KEY_TITLE)) {
            title = savedInstanceState.getParcelable(KEY_TITLE);
            if (savedInstanceState.containsKey(KEY_PAGE)) {
                page = savedInstanceState.getParcelable(KEY_PAGE);
            }
            state = savedInstanceState.getInt(KEY_STATE);
            scrollY = savedInstanceState.getInt(KEY_SCROLL_Y);
            curEntry = savedInstanceState.getParcelable(KEY_CURRENT_HISTORY_ENTRY);
            quickReturnBarId = savedInstanceState.getInt(KEY_QUICK_RETURN_BAR_ID);
        }
        if (title == null) {
            throw new RuntimeException("No PageTitle passed in to constructor or in instanceState");
        }

        app = (WikipediaApp)getActivity().getApplicationContext();

        pageFragmentContainer = (FrameLayout) getView().findViewById(R.id.page_fragment_container);
        webView = (ObservableWebView) getView().findViewById(R.id.page_web_view);
        loadProgress = (ProgressBar) getView().findViewById(R.id.page_load_progress);
        networkError = getView().findViewById(R.id.page_error);
        retryButton = getView().findViewById(R.id.page_error_retry);
        pageDoesNotExistError = getView().findViewById(R.id.page_does_not_exist);
        quickReturnBar = getActivity().findViewById(quickReturnBarId);
        tocDrawer = (DisableableDrawerLayout) getView().findViewById(R.id.page_toc_drawer);
        // disable TOC drawer until the page is loaded
        tocDrawer.setSlidingEnabled(false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            // Enable Pinch-Zoom
            webView.getSettings().setBuiltInZoomControls(true);
            webView.getSettings().setDisplayZoomControls(false);
        }

        bridge = new CommunicationBridge(webView, "file:///android_asset/index.html");
        setupMessageHandlers();
        Utils.addUtilityMethodsToBridge(getActivity(), bridge);
        Utils.setupDirectionality(title.getSite().getLanguage(), Locale.getDefault().getLanguage(), bridge);
        bridge.injectStyleBundle(new PackagedStyleBundle("styles.css"));
        linkHandler = new LinkHandler(getActivity(), bridge, title.getSite()){
            @Override
            public void onInternalLinkClicked(PageTitle title) {
                HistoryEntry historyEntry = new HistoryEntry(title, HistoryEntry.SOURCE_INTERNAL_LINK);
                app.getBus().post(new NewWikiPageNavigationEvent(title, historyEntry));
            }
        };
        api = ((WikipediaApp)getActivity().getApplicationContext()).getAPIForSite(title.getSite());

        retryButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ViewAnimations.crossFade(networkError, loadProgress);
                performActionForState(state);
            }
        });

        setState(state);
        performActionForState(state);

        editHandler = new EditHandler(this, bridge);
        new QuickReturnHandler(webView, quickReturnBar);
    }

    private void setupMessageHandlers() {
        Utils.addUtilityMethodsToBridge(getActivity(), bridge);
        bridge.addListener("requestSection", new CommunicationBridge.JSEventListener() {
            @Override
            public void onMessage(String messageType, JSONObject messagePayload) {
                try {
                    int index = messagePayload.optInt("index");
                    if (index >= page.getSections().size()) {
                        // Page has only one section yo
                        bridge.sendMessage("noMoreSections", new JSONObject());
                    } else {
                        JSONObject wrapper = new JSONObject();
                        wrapper.put("section", page.getSections().get(index).toJSON());
                        wrapper.put("index", index);
                        wrapper.put("isLast", index == page.getSections().size() - 1);
                        wrapper.put("fragment", page.getTitle().getFragment());
                        bridge.sendMessage("displaySection", wrapper);
                    }
                } catch (JSONException e) {
                    // Won't happen
                    throw new RuntimeException(e);
                }
            }
        });
        if (app.getRemoteConfig().getConfig().has("disableAnonEditing")
                && app.getRemoteConfig().getConfig().optBoolean("disableAnonEditing")
                && !app.getUserInfoStorage().isLoggedIn()) {
            bridge.sendMessage("hideEditButtons", new JSONObject());
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == EditHandler.RESULT_REFRESH_PAGE) {
            ViewAnimations.crossFade(webView, loadProgress);
            setState(STATE_NO_FETCH);
            performActionForState(state);
        }
    }

    private void performActionForState(int forState) {
        switch (forState) {
            case STATE_NO_FETCH:
                new LeadSectionFetchTask().execute();
                break;
            case STATE_INITIAL_FETCH:
                new RestSectionsFetchTask().execute();
                break;
            case STATE_COMPLETE_FETCH:
                displayLeadSection();
                populateNonLeadSections();
                webView.scrollTo(0, scrollY);
                break;
            default:
                // This should never happen
                throw new RuntimeException("Unknown state encountered " + state);
        }
    }

    private void setState(int state) {
        this.state = state;
        app.getBus().post(new PageStateChangeEvent(state));
        // FIXME: Move this out into a PageComplete event of sorts
        if (state == STATE_COMPLETE_FETCH) {
            if (tocHandler == null) {
                tocHandler = new ToCHandler(tocDrawer, quickReturnBar, bridge);
            }
            tocHandler.setupToC(page);
        }
    }

    private class LeadSectionFetchTask extends SectionsFetchTask {
        public LeadSectionFetchTask() {
            super(getActivity(), title, "0");
        }

        @Override
        public RequestBuilder buildRequest(Api api) {
            RequestBuilder builder =  super.buildRequest(api);
            builder.param("prop", builder.getParams().get("prop") + "|lastmodified|normalizedtitle|displaytitle");
            return builder;
        }

        private PageProperties pageProperties;

        @Override
        public List<Section> processResult(ApiResult result) throws Throwable {
            JSONObject mobileView = result.asObject().optJSONObject("mobileview");
            if(mobileView != null){
                pageProperties = new PageProperties(mobileView);
                if (mobileView.has("redirected")) {
                    // Handle redirects properly.
                    title = new PageTitle(mobileView.optString("redirected"), title.getSite());
                } else if (mobileView.has("normalizedtitle")) {
                    // We care about the normalized title only if we were not redirected
                    title = new PageTitle(mobileView.optString("normalizedtitle"), title.getSite());
                }
            }
            return super.processResult(result);
        }

        @Override
        public void onFinish(List<Section> result) {
            // have we been unwittingly detached from our Activity?
            if (!isAdded()) {
                Log.d("PageViewFragment", "Detached from activity, so stopping update.");
                return;
            }
            page = new Page(title, (ArrayList<Section>) result, pageProperties);
            editHandler.setPage(page);
            displayLeadSection();
            setState(STATE_INITIAL_FETCH);
            new RestSectionsFetchTask().execute();

            // Add history entry now
            app.getPersister(HistoryEntry.class).persist(curEntry);
            new PageImageSaveTask(app, api, title).execute();
        }

        @Override
        public void onCatch(Throwable caught) {
            // in any case, make sure the TOC drawer is closed and disabled
            tocDrawer.setSlidingEnabled(false);

            if (caught instanceof SectionsFetchException) {
                if (((SectionsFetchException)caught).getCode().equals("missingtitle")
                        || ((SectionsFetchException)caught).getCode().equals("invalidtitle")){
                    ViewAnimations.crossFade(loadProgress, pageDoesNotExistError);

                }
            } else if (caught instanceof ApiException) {
                // Check for the source of the error and have different things turn up
                ViewAnimations.crossFade(loadProgress, networkError);
                // Not sure why this is required, but without it tapping retry hides networkError
                // FIXME: INVESTIGATE WHY THIS HAPPENS!
                networkError.setVisibility(View.VISIBLE);
            } else {
                throw new RuntimeException(caught);
            }
        }
    }

    private class RestSectionsFetchTask extends SectionsFetchTask {
        public RestSectionsFetchTask() {
            super(getActivity(), title, "1-");
        }

        @Override
        public void onFinish(List<Section> result) {
            // have we been unwittingly detached from our Activity?
            if (!isAdded()) {
                Log.d("PageViewFragment", "Detached from activity, so stopping update.");
                return;
            }
            ArrayList<Section> newSections = (ArrayList<Section>) page.getSections().clone();
            newSections.addAll(result);
            page = new Page(page.getTitle(), newSections, page.getPageProperties());
            editHandler.setPage(page);
            populateNonLeadSections();
            setState(STATE_COMPLETE_FETCH);
        }
    }

    public void bookmarkPage() {
        new BookmarkPageTask(getActivity(), title) {
            @Override
            public void onFinish(Void result) {
                Toast.makeText(getActivity(), R.string.toast_saved_page, Toast.LENGTH_LONG).show();
            }
        }.execute();
    }

    private ToCHandler tocHandler;
    public void showToC() {
        tocHandler.show();
    }

    public boolean handleBackPressed() {
        if (tocHandler != null && tocHandler.isVisible()) {
            tocHandler.hide();
            return true;
        }
        return false;
    }
}
