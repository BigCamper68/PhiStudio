package com.xpe.mobile.editor;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.xpe.mobile.R;
import com.xpe.mobile.i18n.UiText;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.noiseeditor.NoiseFieldEditorView;
import com.xpe.mobile.preview.PreviewShaderView;
import com.xpe.mobile.shadereditor.ShaderEditorView;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Canvas editor plus a bounded native side panel; the note grid stays interactive. */
@SuppressLint("ViewConstructor") // Constructed with an existing document view, never inflated from XML.
public final class WorkbenchView extends ViewGroup {
    private final EditorView editor;
    private final PreviewShaderView shaderPreview;
    private final PreviewShaderView gridPreview;
    private final Supplier<View> menuFactory;
    private final LinearLayout pane;
    private final LinearLayout footer;
    private final Button[] tabs = new Button[2];
    private final List<EditorPanelWindow> pages = new ArrayList<>();
    private View menu;
    private TextView panelTitle;
    private Runnable shaderOpener;
    private ShaderEditorView shaderEditor;
    private boolean shadersOpen;
    private NoiseFieldEditorView noiseEditor;
    private boolean noiseOpen;
    private int lastMode;
    private android.widget.PopupMenu modePopup;
    private boolean refreshPending;
    private ChartDocument scope;
    private int scopeLine;
    private com.xpe.mobile.model.JudgeLine scopeJudgeLine;
    private int maximumHeight;
    private java.util.function.Consumer<Object> selectionPropertiesOpener;

    public WorkbenchView(Context context, EditorView editor, Supplier<View> menuFactory) {
        super(context);
        this.editor = editor;
        this.menuFactory = menuFactory;
        addView(editor);
        shaderPreview = new PreviewShaderView(context);
        editor.setPreviewShaderView(shaderPreview);
        pane = new LinearLayout(context) {
            @Override public boolean dispatchTouchEvent(MotionEvent event) {
                EditorPanelWindow page = currentPage();
                if (page != null && !page.scopeValid()) {
                    page.dismiss();
                    return true;
                }
                return super.dispatchTouchEvent(event);
            }
        };
        pane.setOrientation(LinearLayout.VERTICAL);
        pane.setBackgroundColor(Color.rgb(26, 33, 42));
        // Consume touches in the panel, including padding; never leak them into event creation.
        pane.setClickable(true);
        addView(pane);
        footer = new LinearLayout(context);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setBackgroundColor(Color.rgb(24, 30, 37));
        addView(footer);
        addView(shaderPreview);
        gridPreview = new PreviewShaderView(context);
        // TextureView is a regular composited View: keep it behind the editable Canvas.
        // Existing workbench controls retain their order and input ownership.
        gridPreview.setTranslationZ(-1f);
        addView(gridPreview);
        editor.setGridPreviewShaderView(gridPreview);
        LinearLayout tabRow = new LinearLayout(context);
        tabs[0] = button(context, R.string.workbench_event);
        tabs[1] = button(context, R.string.menu_title);
        UiText.text(tabs[0], () -> modeLabel() + " ▾");
        UiText.description(tabs[0], R.string.workbench_mode);
        UiText.description(tabs[1], R.string.menu_title);
        tabs[0].setOnClickListener(view -> openModePicker());
        tabs[1].setOnClickListener(view -> switchTab(2));
        for (Button tab : tabs) {
            tab.setTextSize(12); tab.setSingleLine(true);
            tab.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tabRow.addView(tab, new LinearLayout.LayoutParams(0, Math.round(WorkbenchLayout.workbenchFooter(
                    getResources().getDisplayMetrics().density)), 1f));
        }
        footer.addView(tabRow);
        editor.setWorkbenchListener(this::scheduleRefresh);
        refresh();
    }

    public static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static Button button(Context context, CharSequence text) {
        Button button = new Button(context);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(12f);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(context, 5), 0, dp(context, 5), 0);
        return button;
    }

    public static Button button(Context context, int resource) {
        Button button = button(context, "");
        UiText.text(button, resource);
        return button;
    }

    public void relocalizeUi() {
        // Refresh detached navigation pages too, without reparenting any live form.
        for (EditorPanelWindow page : pages) page.relocalizeUi();
        if (menu != null) UiText.relocalize(menu);
        UiText.relocalize(footer);
        if (panelTitle != null) {
            EditorPanelWindow page = currentPage();
            panelTitle.setText(page == null ? getContext().getText(R.string.menu_title) : page.title);
        }
        UiText.relocalize(pane);
        editor.relocalizeUi();
        if (shaderEditor != null) shaderEditor.relocalizeUi();
        if (noiseEditor != null) noiseEditor.relocalizeUi();
        if (modePopup != null) modePopup.dismiss();
        invalidate();
    }

    public void setShaderOpener(Runnable opener) { shaderOpener = opener; }
    public void showShaders(ShaderEditorView view) {
        if (shaderEditor != view) {
            if (shaderEditor != null) removeView(shaderEditor);
            shaderEditor = view; addView(view);
        }
        if (noiseOpen) { noiseOpen = false; noiseEditor.onHidden(); }
        lastMode = 3; shadersOpen = true; shaderPreview.hidePreview(); gridPreview.hidePreview();
        editor.setVisibility(GONE); pane.setVisibility(GONE);
        view.onShown(); refresh(); requestLayout();
    }
    public void clearShaderEditor() {
        if (shaderEditor != null) { shaderEditor.onHidden(); removeView(shaderEditor); shaderEditor = null; }
        shadersOpen = false; editor.setVisibility(VISIBLE); requestLayout();
    }
    public void showMenu() { switchTab(2); }
    EditorView editor() { return editor; }
    void setSelectionPropertiesOpener(java.util.function.Consumer<Object> opener) {
        selectionPropertiesOpener = opener;
    }

    private EditorPanelWindow currentPage() {
        for (int index = pages.size() - 1; index >= 0; index--)
            if (pages.get(index).tab == editor.getWorkbenchTab()) return pages.get(index);
        return null;
    }

    private String modeLabel() {
        int mode = noiseOpen ? 4 : shadersOpen ? 3 : editor.getWorkbenchTab() == 2 ? lastMode : editor.getWorkbenchTab();
        return getContext().getString(mode == 4 ? R.string.noise_title : mode == 3 ? R.string.workbench_shaders
                : mode == 1 ? R.string.workbench_storyboard : R.string.workbench_event);
    }
    private void openModePicker() {
        modePopup = new android.widget.PopupMenu(getContext(), tabs[0]);
        int[] ids = {0, 1, 3, 4};
        int[] labels = {R.string.workbench_event, R.string.workbench_storyboard, R.string.workbench_shaders, R.string.noise_title};
        int mode = noiseOpen ? 4 : shadersOpen ? 3 : editor.getWorkbenchTab() == 2 ? lastMode : editor.getWorkbenchTab();
        for (int i = 0; i < ids.length; i++) modePopup.getMenu().add(0, ids[i], i, labels[i]).setCheckable(true).setChecked(ids[i] == mode);
        modePopup.getMenu().setGroupCheckable(0, true, true);
        modePopup.setOnMenuItemClickListener(item -> { switchTab(item.getItemId()); return true; });
        modePopup.setOnDismissListener(menu -> modePopup = null);
        modePopup.show();
    }
    public NoiseFieldEditorView activeNoiseEditor() { return noiseOpen ? noiseEditor : null; }
    public boolean commitNoiseDraft() { return noiseEditor == null || noiseEditor.commitPendingChanges(); }
    public void pauseModes() { if (noiseEditor != null) noiseEditor.pause(); }
    public void switchTab(int tab) {
        hideKeyboard();
        if (tab == 3) {
            if (shaderOpener != null) shaderOpener.run();
            return;
        }
        if (tab == 4) {
            if (editor.getChart() == null) return;
            if (noiseEditor == null || !noiseEditor.isDocumentValid()) {
                clearNoiseEditor(); noiseEditor = new NoiseFieldEditorView(getContext(), editor); addView(noiseEditor);
                addView(noiseEditor.navigationView());
            }
            if (shadersOpen) { shadersOpen = false; shaderEditor.onHidden(); }
            noiseOpen = true; lastMode = 4;
            shaderPreview.hidePreview(); gridPreview.hidePreview();
            editor.setVisibility(GONE); pane.setVisibility(GONE);
            noiseEditor.onShown(); refresh(); requestLayout(); return;
        }
        if (noiseOpen) {
            noiseOpen = false; noiseEditor.onHidden(); editor.setVisibility(VISIBLE); requestLayout();
        }
        if (shadersOpen) { shadersOpen = false; shaderEditor.onHidden(); editor.setVisibility(VISIBLE); requestLayout(); }
        if (tab != 2) lastMode = tab;
        editor.setWorkbenchTab(tab);
        render(); refresh();
    }
    private void clearNoiseEditor() {
        if (noiseEditor != null) { noiseEditor.onHidden(); removeView(noiseEditor.navigationView()); removeView(noiseEditor); noiseEditor = null; }
        noiseOpen = false; editor.setVisibility(VISIBLE);
    }

    boolean push(EditorPanelWindow page) {
        if (page.tab < 0) page.tab = editor.getWorkbenchTab() == 1 ? 1 : 2;
        if (page.selectionTarget != null) {
            // A selection inspector is a single live page, not a navigation history entry per tap.
            for (int index = pages.size() - 1; index >= 0; index--) {
                EditorPanelWindow previous = pages.get(index);
                if (previous.selectionTarget == null || previous.tab != page.tab) continue;
                if (previous.selectionTarget == page.selectionTarget) {
                    editor.setWorkbenchTab(previous.tab);
                    render();
                    return false; // Keep existing, potentially unsaved inputs.
                }
                previous.abandoned();
                pages.remove(index);
            }
        }
        pages.add(page);
        editor.setWorkbenchTab(page.tab);
        render();
        return true;
    }

    void remove(EditorPanelWindow page) {
        pages.remove(page);
        hideKeyboard();
        render();
    }

    public boolean back() {
        if (shadersOpen) { switchTab(editor.getWorkbenchTab()); return true; }
        if (noiseOpen) { switchTab(editor.getWorkbenchTab()); return true; }
        if (!editor.isWorkbenchPreview() && currentPage() != null && pane.getVisibility() == VISIBLE) {
            currentPage().dismiss();
            return true;
        }
        if (editor.getWorkbenchTab() != 0 && !editor.isWorkbenchPreview()) {
            switchTab(editor.getWorkbenchTab() == 2 ? lastMode : 0);
            return true;
        }
        return false;
    }

    private void clearPages() {
        for (EditorPanelWindow page : pages) page.abandoned();
        pages.clear();
        menu = null;
        hideKeyboard();
    }

    private void hideKeyboard() {
        InputMethodManager manager = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager != null) manager.hideSoftInputFromWindow(getWindowToken(), 0);
        editor.requestFocus();
    }

    private void scheduleRefresh() {
        if (refreshPending) return;
        refreshPending = true;
        post(() -> {
            refreshPending = false;
            refresh();
        });
    }

    private void refresh() {
        if (scope != editor.getChart()) {
            if (noiseEditor != null && !noiseEditor.isDocumentValid()) clearNoiseEditor();
            lastMode = 0;
            scope = editor.getChart();
            scopeLine = editor.getLineIndex();
            scopeJudgeLine = editor.getCurrentJudgeLine();
            clearPages();
            render();
        } else if (scopeLine != editor.getLineIndex() || scopeJudgeLine != editor.getCurrentJudgeLine()) {
            scopeLine = editor.getLineIndex();
            scopeJudgeLine = editor.getCurrentJudgeLine();
            pages.removeIf(page -> {
                if (page.retainOnLineChange) return false;
                page.abandoned();
                return true;
            });
            render();
        }
        EditorPanelWindow selectedPage = currentPage();
        Object target = editor.getSelectedPropertyTarget();
        if (selectedPage != null && selectedPage.selectionTarget != null
                && target != null && target != selectedPage.selectionTarget
                && selectionPropertiesOpener != null) {
            selectedPage.abandoned();
            pages.remove(selectedPage);
            hideKeyboard();
            selectionPropertiesOpener.accept(target);
        }
        boolean extended = shadersOpen || noiseOpen;
        if (noiseEditor != null) noiseEditor.navigationView().setVisibility(noiseOpen ? VISIBLE : GONE);
        boolean preview = editor.isWorkbenchPreview();
        footer.setVisibility(!extended && (preview || compactInputs(getHeight())) ? GONE : VISIBLE);
        if (extended) {
            editor.setVisibility(GONE); pane.setVisibility(GONE);
            if (noiseOpen) noiseEditor.invalidatePreview(); else shaderEditor.invalidatePreview();
        } else pane.setVisibility(preview || editor.getWorkbenchTab() != 2 && currentPage() == null ? GONE : VISIBLE);
        boolean menuSelected = !extended && editor.getWorkbenchTab() == 2;
        tabs[0].setSelected(!menuSelected); tabs[1].setSelected(menuSelected);
        tabs[0].setBackgroundColor(!menuSelected ? 0xff2d8474 : 0xff2a3440);
        tabs[1].setBackgroundColor(menuSelected ? 0xff2d8474 : 0xff2a3440);
        UiText.relocalize(tabs[0]);
    }

    private static void detach(View view) {
        if (view != null && view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
    }
    private boolean compactInputs(int height) {
        return height > 0 && height < dp(getContext(), 300)
                && height < maximumHeight - dp(getContext(), 120)
                && findFocus() instanceof android.widget.EditText;
    }

    private void render() {
        pane.removeAllViews();
        if (editor.getWorkbenchTab() != 2 && currentPage() == null) {
            pane.setVisibility(GONE);
            return;
        }
        pane.setVisibility(VISIBLE);
        LinearLayout header = new LinearLayout(getContext());
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button(getContext(), "‹");
        UiText.description(back, R.string.workbench_back);
        back.setOnClickListener(view -> back());
        header.addView(back, new LinearLayout.LayoutParams(dp(getContext(), 36), -1));
        EditorPanelWindow page = currentPage();
        TextView title = new TextView(getContext());
        panelTitle = title;
        title.setText(page == null ? getContext().getText(R.string.menu_title) : page.title);
        title.setTextSize(15f);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        // Balance Back so the title is centered in the panel itself, not the remaining space.
        View spacer = new View(getContext());
        spacer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(spacer, new LinearLayout.LayoutParams(dp(getContext(), 36), -1));
        pane.addView(header, new LinearLayout.LayoutParams(-1, dp(getContext(), 36)));
        if (page == null && menu == null) menu = menuFactory.get();
        View body = page == null ? menu : page.content;
        detach(body);
        pane.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int height = MeasureSpec.getSize(heightSpec);
        maximumHeight = Math.max(maximumHeight, height);
        setMeasuredDimension(width, height);
        float density = getResources().getDisplayMetrics().density;
        int split = Math.round(WorkbenchLayout.split(width, density));
        int top = Math.round(WorkbenchLayout.toolbar(density));
        int footerHeight = Math.min(height, Math.round(WorkbenchLayout.workbenchFooter(density)));
        if (!shadersOpen && !noiseOpen && compactInputs(height) && !editor.isWorkbenchPreview()) footerHeight = 0;
        editor.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        shaderPreview.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.max(0, height - top - Math.round(25f * density)), MeasureSpec.EXACTLY));
        gridPreview.measure(MeasureSpec.makeMeasureSpec(split, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.max(0, height - top - Math.round(WorkbenchLayout.footer(density))), MeasureSpec.EXACTLY));
        pane.measure(MeasureSpec.makeMeasureSpec(width - split, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.max(0, height - top - footerHeight), MeasureSpec.EXACTLY));
        footer.measure(MeasureSpec.makeMeasureSpec(width - split, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(footerHeight, MeasureSpec.EXACTLY));
        if (shaderEditor != null) shaderEditor.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.max(0, height - footerHeight), MeasureSpec.EXACTLY));
        if (noiseEditor != null) noiseEditor.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.max(0, height - footerHeight), MeasureSpec.EXACTLY));
        if (noiseEditor != null) noiseEditor.navigationView().measure(MeasureSpec.makeMeasureSpec(split, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(footerHeight, MeasureSpec.EXACTLY));
        footer.setVisibility(!shadersOpen && !noiseOpen && editor.isWorkbenchPreview() || footerHeight == 0 ? GONE : VISIBLE);
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int width = right - left;
        int height = bottom - top;
        float density = getResources().getDisplayMetrics().density;
        int split = Math.round(WorkbenchLayout.split(width, density));
        int y = Math.max(0, height - footer.getMeasuredHeight());
        int contentTop = Math.min(height, Math.round(WorkbenchLayout.toolbar(density)));
        editor.layout(0, 0, width, height);
        shaderPreview.layout(0, contentTop, width,
                Math.max(contentTop, height - Math.round(25f * density)));
        gridPreview.layout(0, contentTop, split,
                Math.max(contentTop, height - Math.round(WorkbenchLayout.footer(density))));
        pane.layout(split, Math.min(contentTop, y), width, y);
        footer.layout(split, y, width, height);
        if (shaderEditor != null) shaderEditor.layout(0, 0, width, y);
        if (noiseEditor != null) noiseEditor.layout(0, 0, width, y);
        if (noiseEditor != null) noiseEditor.navigationView().layout(0, y, split, height);
    }
}
