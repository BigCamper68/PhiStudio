package com.xpe.mobile;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.MotionEvent;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.annotation.SuppressLint;

import com.xpe.mobile.editor.EditorView;
import com.xpe.mobile.editor.WorkbenchLayout;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.preview.PreviewShaderView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Canvas editor plus a bounded native side panel; the note grid stays interactive. */
@SuppressLint("ViewConstructor") // Constructed with an existing document view, never inflated from XML.
final class WorkbenchView extends ViewGroup {
    private final EditorView editor;
    private final PreviewShaderView shaderPreview;
    private final Supplier<View> menuFactory;
    private final LinearLayout pane;
    private final LinearLayout footer;
    private final Button[] tabs = new Button[4];
    private final List<EditorPanelWindow> pages = new ArrayList<>();
    private View menu;
    private Runnable shaderOpener;
    private ShaderEditorView shaderEditor;
    private boolean shadersOpen;
    private static final int[] TAB_IDS = {0, 1, 3, 2};
    private boolean refreshPending;
    private ChartDocument scope;
    private int scopeLine;
    private com.xpe.mobile.model.JudgeLine scopeJudgeLine;
    private int maximumHeight;
    private java.util.function.Consumer<Object> selectionPropertiesOpener;

    WorkbenchView(Context context, EditorView editor, Supplier<View> menuFactory) {
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
        LinearLayout tabRow = new LinearLayout(context);
        String[] labels = {"Events", "Storyboard", "Shaders", "Menu"};
        for (int index = 0; index < tabs.length; index++) {
            int next = TAB_IDS[index];
            tabs[index] = button(context, labels[index]);
            tabs[index].setTextSize(10f);
            tabs[index].setSingleLine(true);
            tabs[index].setPadding(dp(context, 2), 0, dp(context, 2), 0);
            tabs[index].setContentDescription(labels[index] + " workbench");
            tabs[index].setOnClickListener(view -> switchTab(next));
            tabRow.addView(tabs[index], new LinearLayout.LayoutParams(0, dp(context, 36), 1f));
        }
        footer.addView(tabRow);
        editor.setWorkbenchListener(this::scheduleRefresh);
        refresh();
    }

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static Button button(Context context, CharSequence text) {
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

    void setShaderOpener(Runnable opener) { shaderOpener = opener; }
    void showShaders(ShaderEditorView view) {
        if (shaderEditor != view) {
            if (shaderEditor != null) removeView(shaderEditor);
            shaderEditor = view; addView(view);
        }
        shadersOpen = true; shaderPreview.hidePreview();
        editor.setVisibility(GONE); pane.setVisibility(GONE);
        view.onShown(); refresh(); requestLayout();
    }
    void clearShaderEditor() {
        if (shaderEditor != null) { shaderEditor.onHidden(); removeView(shaderEditor); shaderEditor = null; }
        shadersOpen = false; editor.setVisibility(VISIBLE); requestLayout();
    }
    void showMenu() { switchTab(2); }
    EditorView editor() { return editor; }
    void setSelectionPropertiesOpener(java.util.function.Consumer<Object> opener) {
        selectionPropertiesOpener = opener;
    }

    private EditorPanelWindow currentPage() {
        for (int index = pages.size() - 1; index >= 0; index--)
            if (pages.get(index).tab == editor.getWorkbenchTab()) return pages.get(index);
        return null;
    }

    void switchTab(int tab) {
        hideKeyboard();
        if (tab == 3) { if (shaderOpener != null) shaderOpener.run(); return; }
        if (shadersOpen) { shadersOpen = false; shaderEditor.onHidden(); editor.setVisibility(VISIBLE); requestLayout(); }
        editor.setWorkbenchTab(tab);
        render();
        refresh();
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

    boolean back() {
        if (shadersOpen) { switchTab(editor.getWorkbenchTab()); return true; }
        if (!editor.isWorkbenchPreview() && currentPage() != null && pane.getVisibility() == VISIBLE) {
            currentPage().dismiss();
            return true;
        }
        if (editor.getWorkbenchTab() != 0 && !editor.isWorkbenchPreview()) {
            switchTab(0);
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
        if (shadersOpen) {
            editor.setVisibility(GONE); pane.setVisibility(GONE); footer.setVisibility(VISIBLE);
            for (int index = 0; index < tabs.length; index++) {
                tabs[index].setSelected(TAB_IDS[index] == 3);
                tabs[index].setBackgroundColor(TAB_IDS[index] == 3 ? Color.rgb(45, 132, 116) : Color.rgb(42, 52, 64));
            }
            shaderEditor.invalidatePreview();
            return;
        }
        boolean preview = editor.isWorkbenchPreview();
        footer.setVisibility(preview || compactInputs(getHeight()) ? GONE : VISIBLE);
        pane.setVisibility(preview || editor.getWorkbenchTab() != 2 && currentPage() == null ? GONE : VISIBLE);
        for (int index = 0; index < tabs.length; index++) {
            tabs[index].setSelected(editor.getWorkbenchTab() == TAB_IDS[index]);
            tabs[index].setBackgroundColor(editor.getWorkbenchTab() == TAB_IDS[index]
                    ? Color.rgb(45, 132, 116) : Color.rgb(42, 52, 64));
        }
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
        back.setContentDescription("Back to workbench");
        back.setOnClickListener(view -> back());
        header.addView(back, new LinearLayout.LayoutParams(dp(getContext(), 36), -1));
        EditorPanelWindow page = currentPage();
        TextView title = new TextView(getContext());
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
        if (!shadersOpen && compactInputs(height) && !editor.isWorkbenchPreview()) footerHeight = 0;
        editor.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        shaderPreview.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.max(0, height - top - Math.round(25f * density)), MeasureSpec.EXACTLY));
        pane.measure(MeasureSpec.makeMeasureSpec(width - split, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.max(0, height - top - footerHeight), MeasureSpec.EXACTLY));
        footer.measure(MeasureSpec.makeMeasureSpec(width - split, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(footerHeight, MeasureSpec.EXACTLY));
        if (shaderEditor != null) shaderEditor.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.max(0, height - footerHeight), MeasureSpec.EXACTLY));
        footer.setVisibility(!shadersOpen && editor.isWorkbenchPreview() || footerHeight == 0 ? GONE : VISIBLE);
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int width = right - left;
        int height = bottom - top;
        float density = getResources().getDisplayMetrics().density;
        int split = Math.round(WorkbenchLayout.split(width, density));
        int y = Math.max(0, height - footer.getMeasuredHeight());
        editor.layout(0, 0, width, height);
        shaderPreview.layout(0, Math.round(WorkbenchLayout.toolbar(density)), width,
                Math.max(Math.round(WorkbenchLayout.toolbar(density)), height - Math.round(25f * density)));
        pane.layout(split, Math.round(WorkbenchLayout.toolbar(density)), width, y);
        footer.layout(split, y, width, height);
        if (shaderEditor != null) shaderEditor.layout(0, 0, width, y);
    }
}
