/*#######################################################
 *
 *   Maintained 2017-2025 by Gregor Santner <gsantner AT mailbox DOT org>
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.pixelpen.journal.activity;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SearchView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import net.pixelpen.journal.ApplicationObject;
import net.pixelpen.journal.BuildConfig;
import net.pixelpen.journal.R;
import net.pixelpen.journal.format.ActionButtonBase;
import net.pixelpen.journal.format.FormatRegistry;
import net.pixelpen.journal.format.TextConverterBase;
import net.pixelpen.journal.frontend.DraggableScrollbarScrollView;
import net.pixelpen.journal.frontend.FileInfoDialog;
import net.pixelpen.journal.frontend.AutumnDialogFactory;
import net.pixelpen.journal.frontend.filebrowser.AutumnFileBrowserFactory;
import net.pixelpen.journal.frontend.textview.HighlightingEditor;
import net.pixelpen.journal.frontend.textview.LineNumbersTextView;
import net.pixelpen.journal.frontend.textview.TextViewUtils;
import net.pixelpen.journal.model.AppSettings;
import net.pixelpen.journal.model.Document;
import net.pixelpen.journal.util.AutumnContextUtils;
import net.pixelpen.journal.web.AutumnWebViewClient;
import net.gsantner.opoc.frontend.filebrowser.GsFileBrowserOptions;
import net.gsantner.opoc.frontend.settings.GsFontPreferenceCompat;
import net.gsantner.opoc.frontend.textview.TextViewUndoRedo;
import net.gsantner.opoc.util.GsContextUtils;
import net.gsantner.opoc.util.GsCoolExperimentalStuff;
import net.gsantner.opoc.web.GsWebViewChromeClient;
import net.gsantner.opoc.wrapper.GsTextWatcherAdapter;

import java.io.File;

@SuppressWarnings({"UnusedReturnValue"})
@SuppressLint("NonConstantResourceId")
public class DocumentEditAndViewFragment extends AutumnBaseFragment implements FormatRegistry.TextFormatApplier {
    public static final String FRAGMENT_TAG = "DocumentEditAndViewFragment";
    public static final String SAVESTATE_DOCUMENT = "DOCUMENT";
    public static final String START_PREVIEW = "START_PREVIEW";

    public static DocumentEditAndViewFragment newInstance(final @NonNull Document document, final Integer lineNumber, final Boolean preview) {
        DocumentEditAndViewFragment f = new DocumentEditAndViewFragment();
        Bundle args = new Bundle();
        args.putSerializable(Document.EXTRA_DOCUMENT, document);
        if (lineNumber != null) {
            args.putInt(Document.EXTRA_FILE_LINE_NUMBER, lineNumber);
        }
        if (preview != null) {
            args.putBoolean(START_PREVIEW, preview);
        }
        f.setArguments(args);
        return f;
    }

    private HighlightingEditor _hlEditor;
    private WebView _webView;
    private AutumnWebViewClient _webViewClient;
    private ViewGroup _editorHolder;
    private ViewGroup _textActionsBar;

    private DraggableScrollbarScrollView _verticalScrollView;
    private HorizontalScrollView _horizontalScrollView;
    private LineNumbersTextView _lineNumbersView;
    private SearchView _menuSearchViewForViewMode;
    private Document _document;
    private FormatRegistry _format;
    private AutumnContextUtils _cu;
    private TextViewUndoRedo _editTextUndoRedoHelper;
    private MenuItem _saveMenuItem, _undoMenuItem, _redoMenuItem;
    private boolean _isPreviewVisible;
    private boolean _nextConvertToPrintMode = false;

    public DocumentEditAndViewFragment() {
        super();
    }

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final Bundle args = getArguments();
        if (savedInstanceState != null && savedInstanceState.containsKey(SAVESTATE_DOCUMENT)) {
            _document = (Document) savedInstanceState.getSerializable(SAVESTATE_DOCUMENT);
        } else if (args != null && args.containsKey(Document.EXTRA_DOCUMENT)) {
            _document = (Document) args.get(Document.EXTRA_DOCUMENT);
        }
    }

    @Override
    protected int getLayoutResId() {
        return R.layout.document__fragment__edit;
    }

    @SuppressLint({"SetJavaScriptEnabled", "WrongConstant", "AddJavascriptInterface", "JavascriptInterface"})
    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        final Activity activity = getActivity();

        _hlEditor = view.findViewById(R.id.document__fragment__edit__highlighting_editor);
        _editorHolder = view.findViewById(R.id.document__fragment__edit__editor_holder);
        _textActionsBar = view.findViewById(R.id.document__fragment__edit__text_actions_bar);
        _webView = view.findViewById(R.id.document__fragment_view_webview);
        _verticalScrollView = view.findViewById(R.id.document__fragment__edit__content_editor__scrolling_parent);
        _lineNumbersView = view.findViewById(R.id.document__fragment__edit__line_numbers_view);
        _cu = new AutumnContextUtils(activity);
        _editTextUndoRedoHelper = new TextViewUndoRedo();

        // Using `if (_document != null)` everywhere is dangerous
        // It may cause reads or writes to _silently fail_
        // Instead we try to create it, and exit if that isn't possible
        if (isStateBad()) {
            Toast.makeText(activity, R.string.error_could_not_open_file, Toast.LENGTH_LONG).show();
            if (activity != null) {
                activity.finish();
            }
            return;
        }

        _lineNumbersView.setup(_hlEditor);
        _lineNumbersView.setLineNumbersEnabled(_appSettings.getDocumentLineNumbersEnabled(_document.path));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && _appSettings.getSetWebViewFulldrawing()) {
            WebView.enableSlowWholeDocumentDraw();
        }

        _webViewClient = new AutumnWebViewClient(_webView, activity);
        _webView.setWebChromeClient(new GsWebViewChromeClient(_webView, activity, view.findViewById(R.id.document__fragment_fullscreen_overlay)));
        _webView.setWebViewClient(_webViewClient);
        _webView.addJavascriptInterface(this, "Android");
        _webView.setBackgroundColor(Color.TRANSPARENT);
        WebSettings webSettings = _webView.getSettings();
        webSettings.setBuiltInZoomControls(true);
        webSettings.setDisplayZoomControls(false);
        webSettings.setTextZoom((int) (_appSettings.getViewFontSize() / 15.7f * 100f));
        webSettings.setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
        webSettings.setDatabaseEnabled(true);
        webSettings.setGeolocationEnabled(false);
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setAllowFileAccess(true);
        // Belt and braces with the missing INTERNET permission. A note holding a remote image
        // or an iframe renders without it rather than reaching for it; the permission already
        // makes that impossible, and this makes it impossible on purpose rather than by
        // omission, so restoring the permission some day cannot quietly restore the fetching.
        webSettings.setBlockNetworkLoads(true);
        webSettings.setBlockNetworkImage(true);
        webSettings.setAllowContentAccess(true);
        webSettings.setAllowFileAccessFromFileURLs(true);
        webSettings.setAllowUniversalAccessFromFileURLs(false);
        webSettings.setMediaPlaybackRequiresUserGesture(false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && BuildConfig.IS_TEST_BUILD && BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true); // Inspect on computer chromium browser: chrome://inspect/#devices
        }

        // Upon construction, the document format has been determined from extension etc
        // Here we replace it with the last saved format.
        applyTextFormat(_appSettings.getDocumentFormat(_document.path, _document.getFormat()));

        if (activity instanceof DocumentActivity) {
            ((DocumentActivity) activity).setDocumentTitle(_document.title);
        }

        // Preview mode set before loadDocument to prevent flicker
        final Bundle args = getArguments();
        final boolean startInPreview = _appSettings.getDocumentPreviewState(_document.path);
        if (args != null && savedInstanceState == null) { // Use the launch flag on first launch
            setViewModeVisibility(args.getBoolean(START_PREVIEW, startInPreview), false);
        } else {
            setViewModeVisibility(startInPreview, false);
        }

        // Configure the editor
        // ---------------------------------------------------------
        _hlEditor.setLineSpacing(0, _appSettings.getEditorLineSpacing());
        _hlEditor.setTextSize(TypedValue.COMPLEX_UNIT_SP, _appSettings.getDocumentFontSize(_document.path));
        _hlEditor.setTypeface(GsFontPreferenceCompat.typeface(getContext(), _appSettings.getFontFamily(), Typeface.NORMAL));
        _hlEditor.setBackgroundColor(_appSettings.getEditorBackgroundColor());
        _hlEditor.setTextColor(_appSettings.getEditorForegroundColor());
        _hlEditor.setGravity(_appSettings.isEditorStartEditingInCenter() ? Gravity.CENTER : Gravity.NO_GRAVITY);
        _hlEditor.setHighlightingEnabled(_appSettings.getDocumentHighlightState(_document.path, _hlEditor.getText()));
        _hlEditor.setAutoFormatEnabled(_appSettings.getDocumentAutoFormatEnabled(_document.path));
        _hlEditor.setSaveInstanceState(false); // We will reload from disk
        _hlEditor.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Do not need to send contents to accessibility
            _hlEditor.setImportantForAccessibility(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        }

        // Various settings
        setWrapState(isDisplayedAtMainActivity() || _appSettings.getDocumentWrapState(_document.path));
        updateMenuToggleStates(0);

        // ---------------------------------------------------------
        _document.resetChangeTracking(); // Force next reload in onResume

        final Runnable debounced = TextViewUtils.makeDebounced(500, () -> {
            checkTextChangeState();
            updateUndoRedoIconStates();
        });
        _hlEditor.addTextChangedListener(GsTextWatcherAdapter.after(s -> debounced.run()));

        // We set the keyboard to be hidden if it was hidden when we lost focus
        // This works well to preserve keyboard state.
        if (activity != null) {
            final Window window = activity.getWindow();
            final int adjustResize = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
            final int unchanged = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED | adjustResize;
            final int hidden = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN | adjustResize;
            final int shown = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE | adjustResize;

            _hlEditor.getViewTreeObserver().addOnWindowFocusChangeListener(hasFocus -> {
                if (hasFocus) {
                    // Restore old state
                    _hlEditor.postDelayed(() -> window.setSoftInputMode(unchanged), 500);
                } else {
                    final Boolean isOpen = TextViewUtils.isImeOpen(_hlEditor);
                    if (isOpen != null) {
                        window.setSoftInputMode(isOpen ? shown : hidden);
                    }
                }
            });
        }

        _verticalScrollView.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            _hlEditor.post(() -> {
                final int height = _verticalScrollView.getHeight();
                if (height != _hlEditor.getMinHeight()) {
                    _hlEditor.setMinHeight(height);
                }
            });
        });
    }

    @Override
    protected void onFragmentFirstTimeVisible() {
        final Bundle args = getArguments();
        int startPos = _appSettings.getLastEditPosition(_document.path, _hlEditor.length());
        if (args != null && args.containsKey(Document.EXTRA_FILE_LINE_NUMBER)) {
            final int lno = args.getInt(Document.EXTRA_FILE_LINE_NUMBER);
            if (lno >= 0) {
                startPos = TextViewUtils.getIndexFromLineOffset(_hlEditor.getText(), lno, 0);
            } else {
                startPos = _hlEditor.length();
            }
        }

        _hlEditor.recomputeHighlighting(); // Run before setting scroll position
        TextViewUtils.setSelectionAndShow(_hlEditor, startPos);

        // And again once a layout pass has happened.
        //
        // setSelectionAndShow does nothing at all unless the position is within edit.length(),
        // and showSelection needs the editor and its scroll container to have been measured
        // before it can work out where to scroll. On the way in from a search result neither is
        // reliably true yet: the cursor ends up on the right line while the view stays at the
        // top, which reads as the jump not working. There is also a posted runnable that resets
        // the editor's minimum height, and a relayout from that would undo an earlier scroll.
        //
        // Cheap to repeat and harmless when the first call already succeeded.
        final int showPos = startPos;
        _hlEditor.post(() -> TextViewUtils.setSelectionAndShow(_hlEditor, showPos));

        _hlEditor.post(() -> _hlEditor.animate().alpha(1).setDuration(500).start());
    }

    @Override
    public void onResume() {
        _webView.onResume();
        loadDocument();
        if (_editTextUndoRedoHelper != null && _editTextUndoRedoHelper.getTextView() != _hlEditor) {
            _editTextUndoRedoHelper.setTextView(_hlEditor);
        }
        super.onResume();
    }

    @Override
    public void onPause() {
        saveDocument(false);
        _webView.onPause();
        _appSettings.addRecentFile(_document.file);
        _appSettings.setDocumentPreviewState(_document.path, _isPreviewVisible);
        _appSettings.setLastEditPosition(_document.path, TextViewUtils.getSelection(_hlEditor)[0]);
        super.onPause();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putSerializable(SAVESTATE_DOCUMENT, _document);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.document__edit__menu, menu);
        _cu.tintMenuItems(menu, true, Color.WHITE);
        _cu.setSubMenuIconsVisibility(menu, true);

        final boolean isText = !_document.isBinaryFileNoTextLoading();

        menu.findItem(R.id.action_undo).setVisible(isText && _appSettings.isEditorHistoryEnabled());
        menu.findItem(R.id.action_redo).setVisible(isText && _appSettings.isEditorHistoryEnabled());

        // Undo / Redo / Save (keep visible, but deactivated and tinted grey if not executable)
        _undoMenuItem = menu.findItem(R.id.action_undo).setVisible(isText && !_isPreviewVisible);
        _redoMenuItem = menu.findItem(R.id.action_redo).setVisible(isText && !_isPreviewVisible);
        _saveMenuItem = menu.findItem(R.id.action_save).setVisible(isText && !_isPreviewVisible);

        // Edit / Preview switch
        menu.findItem(R.id.action_edit).setVisible(isText && _isPreviewVisible);
        menu.findItem(R.id.action_preview).setVisible(isText && !_isPreviewVisible);
        menu.findItem(R.id.action_search).setVisible(isText && !_isPreviewVisible);
        menu.findItem(R.id.action_search_view).setVisible(isText && _isPreviewVisible);
        menu.findItem(R.id.submenu_share).setVisible(isText);

        menu.findItem(R.id.action_share_pdf).setVisible(true);

        // SearchView (View Mode)
        _menuSearchViewForViewMode = (SearchView) menu.findItem(R.id.action_search_view).getActionView();
        if (_menuSearchViewForViewMode != null) {
            _menuSearchViewForViewMode.setSubmitButtonEnabled(true);
            _menuSearchViewForViewMode.setQueryHint(getString(R.string.search));
            _menuSearchViewForViewMode.setOnQueryTextFocusChangeListener((v, searchHasFocus) -> {
                if (!searchHasFocus) {
                    _menuSearchViewForViewMode.setQuery("", false);
                    _menuSearchViewForViewMode.setIconified(true);
                }
            });
            _menuSearchViewForViewMode.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
                @Override
                public boolean onQueryTextSubmit(String text) {
                    _webView.findNext(true);
                    return true;
                }

                @Override
                public boolean onQueryTextChange(String text) {
                    _webView.findAllAsync(text);
                    return true;
                }
            });
        }

        // Set various initial states
        updateMenuToggleStates(_document.getFormat());
        checkTextChangeState();
        updateUndoRedoIconStates();
    }

    @Override
    public boolean onReceiveKeyPress(int keyCode, KeyEvent event) {
        if (_format != null && _format.getActions().onReceiveKeyPress(keyCode, event)) {
            return true;
        }

        if (event.isCtrlPressed()) {
            if (event.isShiftPressed() && keyCode == KeyEvent.KEYCODE_Z) {
                if (_editTextUndoRedoHelper != null && _editTextUndoRedoHelper.getCanRedo()) {
                    _hlEditor.withAutoFormatDisabled(_editTextUndoRedoHelper::redo);
                    updateUndoRedoIconStates();
                }
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_S) {
                saveDocument(true);
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_Y) {
                if (_editTextUndoRedoHelper != null && _editTextUndoRedoHelper.getCanRedo()) {
                    _hlEditor.withAutoFormatDisabled(_editTextUndoRedoHelper::redo);
                    updateUndoRedoIconStates();
                }
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_Z) {
                if (_editTextUndoRedoHelper != null && _editTextUndoRedoHelper.getCanUndo()) {
                    _hlEditor.withAutoFormatDisabled(_editTextUndoRedoHelper::undo);
                    updateUndoRedoIconStates();
                }
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_SLASH) {
                setViewModeVisibility(!_isPreviewVisible);
                return true;
            }
        }

        return false;
    }

    private void updateUndoRedoIconStates() {
        Drawable d;
        final boolean canUndo = _editTextUndoRedoHelper != null && _editTextUndoRedoHelper.getCanUndo();
        if (_undoMenuItem != null && _undoMenuItem.isEnabled() != canUndo && (d = _undoMenuItem.setEnabled(canUndo).getIcon()) != null) {
            d.mutate().setAlpha(canUndo ? 255 : 40);
        }

        final boolean canRedo = _editTextUndoRedoHelper != null && _editTextUndoRedoHelper.getCanRedo();
        if (_redoMenuItem != null && _redoMenuItem.isEnabled() != canRedo && (d = _redoMenuItem.setEnabled(canRedo).getIcon()) != null) {
            d.mutate().setAlpha(canRedo ? 255 : 40);
        }
    }

    public boolean loadDocument() {
        if (isSdStatusBad() || isStateBad()) {
            errorClipText();
            return false;
        }

        // Only trigger the load process if constructing or file updated or force reload
        if (_document.hasFileChangedSinceLastLoad()) {

            final String content = _document.loadContent(getContext());
            if (content == null) {
                errorClipText();
                return false;
            }

            if (!_document.isContentSame(_hlEditor.getText())) {
                _hlEditor.withAutoFormatDisabled(() -> _hlEditor.setTextKeepState(content));
            }

            checkTextChangeState();

            if (_isPreviewVisible) {
                updateViewModeText();
            }

            return true;
        }
        return false;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull final MenuItem item) {
        final Activity activity = getActivity();
        if (activity == null) {
            return true;
        }

        final int itemId = item.getItemId();
        switch (itemId) {
            case R.id.action_undo: {
                if (_editTextUndoRedoHelper != null && _editTextUndoRedoHelper.getCanUndo()) {
                    _hlEditor.withAutoFormatDisabled(_editTextUndoRedoHelper::undo);
                    updateUndoRedoIconStates();
                }
                return true;
            }
            case R.id.action_redo: {
                if (_editTextUndoRedoHelper != null && _editTextUndoRedoHelper.getCanRedo()) {
                    _hlEditor.withAutoFormatDisabled(_editTextUndoRedoHelper::redo);
                    updateUndoRedoIconStates();
                }
                return true;
            }
            case R.id.action_save: {
                saveDocument(true);
                return true;
            }
            case R.id.action_preview: {
                setViewModeVisibility(true);
                return true;
            }
            case R.id.action_edit: {
                setViewModeVisibility(false);
                return true;
            }
            case R.id.action_preview_edit_toggle: {
                setViewModeVisibility(!_isPreviewVisible);
                return true;
            }
            case R.id.action_share_text: {
                if (saveDocument(false)) {
                    _cu.shareText(getActivity(), getTextString(), GsContextUtils.MIME_TEXT_PLAIN);
                }
                return true;
            }
            case R.id.action_share_file: {
                if (saveDocument(false)) {
                    _cu.shareStream(getActivity(), _document.file, GsContextUtils.MIME_TEXT_PLAIN);
                }
                return true;
            }
            case R.id.action_share_pdf: {
                // Renders through the preview webview, so it has to switch to preview and wait for
                // it to draw. The screenshot and share-as-image variants shared this block; they
                // are gone, and with them the branch that chose between them.
                _appSettings.getSetWebViewFulldrawing(true);
                if (saveDocument(false)) {
                    _nextConvertToPrintMode = true;
                    setViewModeVisibility(true);
                    Toast.makeText(activity, R.string.please_wait, Toast.LENGTH_LONG).show();
                    _webView.postDelayed(() -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                            _cu.printOrCreatePdfFromWebview(_webView, _document, getTextString().contains("beamer\n"));
                        }
                    }, 7000);
                }
                return true;
            }
            case R.string.action_format_wikitext:
            case R.string.action_format_keyvalue:
            case R.string.action_format_todotxt:
            case R.string.action_format_csv:
            case R.string.action_format_plaintext:
            case R.string.action_format_asciidoc:
            case R.string.action_format_orgmode:
            case R.string.action_format_markdown: {
                if (itemId != _document.getFormat()) {
                    _document.setFormat(itemId);
                    applyTextFormat(itemId);
                    _appSettings.setDocumentFormat(_document.path, _document.getFormat());
                }
                return true;
            }
            case R.id.action_search: {
                setViewModeVisibility(false);
                _format.getActions().onSearch();
                return true;
            }
            default: {
                return super.onOptionsItemSelected(item);
            }
        }
    }

    public void checkTextChangeState() {
        final boolean isTextChanged = !_document.isContentSame(_hlEditor.getText());
        Drawable d;

        if (_saveMenuItem != null && _saveMenuItem.isEnabled() != isTextChanged && (d = _saveMenuItem.setEnabled(isTextChanged).getIcon()) != null) {
            d.mutate().setAlpha(isTextChanged ? 255 : 40);
        }
    }

    @Override
    public void applyTextFormat(final int textFormatId) {
        final Activity activity = getActivity();
        if (activity == null) {
            return;
        }
        _format = FormatRegistry.getFormat(textFormatId, activity, _document);
        _document.setFormat(_format.getFormatId());
        _hlEditor.setHighlighter(_format.getHighlighter());
        _hlEditor.setDynamicHighlightingEnabled(_appSettings.isDynamicHighlightingEnabled());
        _hlEditor.setAutoFormatters(_format.getAutoFormatInputFilter(), _format.getAutoFormatTextWatcher());
        _hlEditor.setAutoFormatEnabled(_appSettings.getDocumentAutoFormatEnabled(_document.path));
        _format.getActions()
                .setDocument(_document)
                .setUiReferences(activity, _hlEditor, _webView)
                .recreateActionButtons(_textActionsBar, _isPreviewVisible ? ActionButtonBase.ActionItem.DisplayMode.VIEW : ActionButtonBase.ActionItem.DisplayMode.EDIT);
        updateMenuToggleStates(_format.getFormatId());
        showHideActionBar();
    }

    private void showHideActionBar() {
        final Activity activity = getActivity();
        if (activity != null) {
            final View bar = activity.findViewById(R.id.document__fragment__edit__text_actions_bar);
            final View parent = activity.findViewById(R.id.document__fragment__edit__text_actions_bar__scrolling_parent);
            final View viewScroll = activity.findViewById(R.id.document__fragment_view_webview);

            if (bar != null && parent != null && _verticalScrollView != null && viewScroll != null) {
                final boolean hide = _textActionsBar.getChildCount() == 0;
                parent.setVisibility(hide ? View.GONE : View.VISIBLE);
                final int marginBottom = hide ? 0 : (int) getResources().getDimension(R.dimen.textactions_bar_height);
                setMarginBottom(_verticalScrollView, marginBottom);
                setMarginBottom(viewScroll, marginBottom);
            }
        }
    }

    private void setMarginBottom(final View view, final int marginBottom) {
        final ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
        if (params != null) {
            params.setMargins(params.leftMargin, params.topMargin, params.rightMargin, marginBottom);
            view.setLayoutParams(params);
        }
    }

    /**
     * Nothing left to tick.
     *
     * <p>This kept the checkmarks on word wrap, line numbers, highlighting, auto-format and the
     * selected format in step with the editor. All of those items are gone: the toggles with the
     * per-file settings submenu, and the format list because this app is markdown and text only.
     * Kept as a no-op rather than deleted, because it is called from the format plumbing in
     * several places and an empty method there is less noise than threading its absence through
     * all of them.
     */
    private void updateMenuToggleStates(final int selectedFormatActionId) {
    }

    private boolean isWrapped() {
        return _horizontalScrollView == null || _hlEditor.getParent() != _horizontalScrollView;
    }

    private ViewGroup.LayoutParams makeLinearLayoutChildParams() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    }

    private ViewGroup.LayoutParams makeScrollViewChildParams() {
        return new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void setWrapState(final boolean wrap) {
        _hlEditor.setHorizontallyScrolling(!wrap);
        final Context context = getContext();
        if (context != null && _hlEditor != null && isWrapped() != wrap) {

            _hlEditor.setAlpha(0);

            final int[] sel = TextViewUtils.getSelection(_hlEditor);
            final boolean hlEnabled = _hlEditor.setHighlightingEnabled(false);

            if (_horizontalScrollView == null) {
                _horizontalScrollView = new HorizontalScrollView(context);
                _horizontalScrollView.setFillViewport(true);
            }

            if (wrap) {
                _horizontalScrollView.removeView(_hlEditor);
                _editorHolder.removeView(_horizontalScrollView);
                _hlEditor.setLayoutParams(makeLinearLayoutChildParams());
                _editorHolder.addView(_hlEditor, 1);
            } else {
                _editorHolder.removeView(_hlEditor);
                _hlEditor.setLayoutParams(makeScrollViewChildParams());
                _horizontalScrollView.addView(_hlEditor);
                _horizontalScrollView.setLayoutParams(makeLinearLayoutChildParams());
                _editorHolder.addView(_horizontalScrollView, 1);
            }

            _hlEditor.requestLayout();

            _hlEditor.setHighlightingEnabled(hlEnabled);
            _hlEditor.post(() -> {
                TextViewUtils.setSelectionAndShow(_hlEditor, sel);
                _hlEditor.post(() -> _hlEditor.animate().alpha(1).setDuration(400).start());
            });
        }
    }

    @Override
    public String getFragmentTag() {
        return FRAGMENT_TAG;
    }

    public void errorClipText() {
        final String text = getTextString();
        if (!TextUtils.isEmpty(text)) {
            Context context = getContext();
            context = context == null ? ApplicationObject.get().getApplicationContext() : context;
            new AutumnContextUtils(context).setClipboard(getContext(), text);
        }
        // Always show error message
        Toast.makeText(getContext(), R.string.error_could_not_open_file, Toast.LENGTH_LONG).show();
        Log.i(DocumentEditAndViewFragment.class.getName(), "Triggering error text clipping");
    }

    public boolean isSdStatusBad() {
        if (_cu.isUnderStorageAccessFolder(getContext(), _document.file, false) &&
                _cu.getStorageAccessFrameworkTreeUri(getContext()) == null) {
            _cu.showMountSdDialog(getActivity());
            return true;
        }
        return false;
    }

    // Checks document state if things aren't in a good state
    public boolean isStateBad() {
        return (_document == null ||
                _hlEditor == null ||
                _appSettings == null ||
                !_cu.canWriteFile(getContext(), _document.file, false, true));
    }

    // Save the file
    public boolean saveDocument(final boolean forceSaveEmpty) {
        final Activity activity = getActivity();
        if (activity == null || isSdStatusBad() || isStateBad()) {
            errorClipText();
            return false;
        }

        // Document is written iff writeable && content has changed
        final CharSequence text = _hlEditor.getText();
        if (!_document.isContentSame(text)) {
            final int minLength = GsContextUtils.TEXTFILE_OVERWRITE_MIN_TEXT_LENGTH;
            if (!forceSaveEmpty && text != null && text.length() < minLength) {
                final String message = activity.getString(R.string.wont_save_min_length, minLength);
                Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
                return true;
            }
            if (_document.saveContent(getActivity(), text, _cu, forceSaveEmpty)) {
                checkTextChangeState();
                return true;
            } else {
                errorClipText();
                return false; // Failure only if saveContent somehow fails
            }
        } else {
            return true; // Report success if text not changed
        }
    }

    private boolean isDisplayedAtMainActivity() {
        return getActivity() instanceof MainActivity;
    }

    public void updateViewModeText() {
        // Don't let text to view mode crash app
        try {
            _format.getConverter().convertMarkupShowInWebView(_document, getTextString(), getActivity(), _webView, _nextConvertToPrintMode, _lineNumbersView.isLineNumbersEnabled());
        } catch (OutOfMemoryError e) {
            _format.getConverter().convertMarkupShowInWebView(_document, "updateViewModeText getTextString(): OutOfMemory  " + e, getActivity(), _webView, _nextConvertToPrintMode, _lineNumbersView.isLineNumbersEnabled());
        }
    }

    public void setViewModeVisibility(final boolean show) {
        setViewModeVisibility(show, true);
    }

    public void setViewModeVisibility(boolean show, final boolean animate) {
        final Activity activity = getActivity();
        if (activity == null) {
            return;
        }

        show |= _document.isBinaryFileNoTextLoading();
        _format.getActions().recreateActionButtons(_textActionsBar, show ? ActionButtonBase.ActionItem.DisplayMode.VIEW : ActionButtonBase.ActionItem.DisplayMode.EDIT);
        showHideActionBar();
        if (show) {
            updateViewModeText();
            _cu.showSoftKeyboard(activity, false, _hlEditor);
            _hlEditor.clearFocus();
            _hlEditor.postDelayed(() -> _cu.showSoftKeyboard(activity, false, _hlEditor), 300);
            GsContextUtils.fadeInOut(_webView, _verticalScrollView, animate);
        } else {
            _webViewClient.setRestoreScrollY(_webView.getScrollY());
            GsContextUtils.fadeInOut(_verticalScrollView, _webView, animate);
        }

        _nextConvertToPrintMode = false;
        _isPreviewVisible = show;

        ((AppCompatActivity) activity).supportInvalidateOptionsMenu();
    }

    // Callback from view-mode/javascript
    @SuppressWarnings("unused")
    @JavascriptInterface
    public void webViewJavascriptCallback(final String[] jsArgs) {
        final String[] args = (jsArgs == null || jsArgs.length == 0 || jsArgs[0] == null) ? new String[0] : jsArgs;
        final String type = args.length == 0 || TextUtils.isEmpty(args[0]) ? "" : args[0];
        if (type.equalsIgnoreCase("toast") && args.length == 2) {
            Toast.makeText(getActivity(), args[1], Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onToolbarClicked(View v) {
        if (_format != null) {
            _format.getActions().runTitleClick();
        }
    }

    @Override
    protected boolean onToolbarLongClicked(View v) {
        if (isVisible() && isResumed()) {
            _format.getActions().runJumpBottomTopAction(_isPreviewVisible ? ActionButtonBase.ActionItem.DisplayMode.VIEW : ActionButtonBase.ActionItem.DisplayMode.EDIT);
            return true;
        }
        return false;
    }

    @Override
    public void onDestroy() {
        try {
            _webView.loadUrl("about:blank");
            _webView.destroy();
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    public Document getDocument() {
        return _document;
    }

    public HighlightingEditor getEditor() {
        return _hlEditor;
    }

    public String getTextString() {
        final CharSequence text = _hlEditor != null ? _hlEditor.getText() : null;
        return text != null ? text.toString() : "";
    }
}
