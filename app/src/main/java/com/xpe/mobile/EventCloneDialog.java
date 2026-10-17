package com.xpe.mobile;

import android.app.AlertDialog;
import android.graphics.Color;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import com.xpe.mobile.editor.BatchValueTransform;
import com.xpe.mobile.editor.EventCloneOperation;
import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.EventType;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.model.LineEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Named destinations and optional offsets, with a deterministic, non-mutating preview. */
final class EventCloneDialog {
    private final EditorOperationDialogSection owner;
    private final ChartDocument chart;
    private final JudgeLine sourceLine;
    private final int sourceLayer;
    private final List<LineEvent> sources;
    private final List<JudgeLine> targets = new ArrayList<>();
    private final Map<EventType, OffsetInput> offsets = new EnumMap<>(EventType.class);
    private final long seed = System.nanoTime();
    private EditorPanelWindow dialog;
    private LinearLayout content, targetRows, resultRows;
    private Spinner sourceMode, timing;
    private CheckBox customizeValues;
    private EditText delay;
    private LinearLayout delayFields;
    private TextView status;
    private boolean showAllTargets;
    private final Runnable updatePreview = this::refreshPreview;

    EventCloneDialog(EditorOperationDialogSection owner) {
        this.owner = owner;
        chart = owner.editorView.getChart();
        sourceLine = owner.editorView.getCurrentJudgeLine();
        sourceLayer = owner.editorView.getEventLayerIndex();
        sources = owner.editorView.getSelectedEventsForBatch();
    }

    void show() {
        if (sources.isEmpty()) { owner.showMessage(owner.getString(R.string.event_clone_empty)); return; }
        ScrollView scroll = new ScrollView(owner);
        content = owner.createDialogContent(scroll);
        owner.addMenuSection(content, R.string.clone_source_heading);
        BeatTime first = sources.get(0).startTime, last = sources.get(0).endTime;
        EnumSet<EventType> types = EnumSet.noneOf(EventType.class);
        for (LineEvent source : sources) {
            types.add(source.type);
            if (source.startTime.compareTo(first) < 0) first = source.startTime;
            if (source.endTime.compareTo(last) > 0) last = source.endTime;
        }
        owner.addHint(content, owner.getResources().getQuantityString(R.plurals.clone_source_summary, sources.size(), sources.size(),
                lineLabel(sourceLine), sourceLayer, first.toString(), last.toString()));
        sourceMode = owner.addSpinner(content, R.string.clone_source_mode, R.array.clone_source_modes, 0);
        owner.addMenuSection(content, R.string.clone_targets_heading);
        owner.addMenuButton(content, R.string.clone_choose_lines).setOnClickListener(v -> chooseLines());
        owner.addHint(content, owner.getString(R.string.clone_order_help));
        targetRows = new LinearLayout(owner); targetRows.setOrientation(LinearLayout.VERTICAL);
        content.addView(targetRows, new LinearLayout.LayoutParams(-1, -2));
        renderTargets();

        owner.addMenuSection(content, R.string.clone_timing_heading);
        timing = owner.addSpinner(content, R.string.clone_timing_mode, R.array.clone_timing_modes, 0);
        delayFields = new LinearLayout(owner); delayFields.setOrientation(LinearLayout.VERTICAL);
        content.addView(delayFields, new LinearLayout.LayoutParams(-1, -2));
        delay = owner.addField(delayFields, owner.getString(R.string.clone_delay), "1", false);
        owner.addHint(delayFields, owner.getString(R.string.clone_delay_help));
        delayFields.setVisibility(View.GONE);

        customizeValues = owner.addCheckBox(content, R.string.clone_customize_values, false);
        LinearLayout customization = new LinearLayout(owner);
        customization.setOrientation(LinearLayout.VERTICAL);
        content.addView(customization, new LinearLayout.LayoutParams(-1, -2));
        customization.setVisibility(View.GONE);
        owner.addHint(customization, owner.getString(R.string.clone_offset_help));
        for (EventType type : types) {
            if (type == EventType.SPEED) continue;
            OffsetInput input = new OffsetInput(customization, type);
            offsets.put(type, input);
        }
        if (types.contains(EventType.SPEED)) owner.addHint(customization, owner.getString(R.string.clone_speed_help));
        if (offsets.isEmpty()) customizeValues.setVisibility(View.GONE);
        customizeValues.setOnCheckedChangeListener((button, enabled) -> {
            customization.setVisibility(enabled ? View.VISIBLE : View.GONE);
            schedulePreview();
        });
        owner.addMenuSection(content, R.string.clone_preview_heading);
        status = owner.addHint(content, "");
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        resultRows = new LinearLayout(owner); resultRows.setOrientation(LinearLayout.VERTICAL);
        content.addView(resultRows, new LinearLayout.LayoutParams(-1, -2));
        owner.addHint(content, owner.getString(R.string.clone_preserved_help));
        dialog = new EditorPanelWindow.Builder(owner, owner.host.workbench())
                .setTitle(R.string.event_clone_title).setView(scroll)
                .setWorkbenchTab(owner.editorView.getWorkbenchTab())
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.clone_copy_action, null).create();
        EditorDialogSection.setSpinnerChangeListener(sourceMode, this::schedulePreview);
        EditorDialogSection.setSpinnerChangeListener(timing, () -> {
            delayFields.setVisibility(timing.getSelectedItemPosition() == 1 ? View.VISIBLE : View.GONE);
            schedulePreview();
        });
        EditorDialogSection.watch(this::schedulePreview, delay);
        owner.showEditorWindow(dialog, () -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                content.removeCallbacks(updatePreview);
                if (!sameSource()) { showError(owner.getString(R.string.clone_source_changed)); return; }
                try {
                    EventCloneOperation.Spec spec = readSpec();
                    EventCloneOperation.Result result = owner.editorView.applyEventClone(spec);
                    if (result.error != EventCloneOperation.Error.NONE) {
                        showError(owner.eventCloneErrorMessage(result.error)); return;
                    }
                    dialog.dismiss();
                    owner.showMessage(owner.getResources().getQuantityString(R.plurals.event_clone_applied,
                            result.events.size(), result.events.size()));
                } catch (IllegalArgumentException error) { showError(owner.getString(R.string.event_clone_invalid)); }
            });
            refreshPreview();
        });
    }

    private boolean sameSource() {
        if (chart != owner.editorView.getChart() || sourceLine != owner.editorView.getCurrentJudgeLine()
                || sourceLayer != owner.editorView.getEventLayerIndex()) return false;
        List<LineEvent> current = owner.editorView.getSelectedEventsForBatch();
        return current.size() == sources.size() && current.containsAll(sources);
    }

    private void schedulePreview() {
        if (dialog == null) return;
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        content.removeCallbacks(updatePreview);
        content.postDelayed(updatePreview, 120);
    }

    private EventCloneOperation.Spec readSpec() {
        EventCloneOperation.Spec spec = new EventCloneOperation.Spec();
        spec.lineSequence = new int[targets.size()];
        for (int i = 0; i < targets.size(); i++) spec.lineSequence[i] = chart.judgeLines.indexOf(targets.get(i));
        spec.keepSource = sourceMode.getSelectedItemPosition() == 0;
        spec.timeIncrement = timing.getSelectedItemPosition() == 0 ? BeatTime.zero()
                : BeatTime.parseFlexible(delay.getText().toString());
        boolean customize = customizeValues.isChecked();
        spec.xProfile = profile(EventType.MOVE_X, customize, seed + 1);
        spec.yProfile = profile(EventType.MOVE_Y, customize, seed + 2);
        spec.rotateProfile = profile(EventType.ROTATE, customize, seed + 3);
        spec.alphaProfile = profile(EventType.ALPHA, customize, seed + 4);
        return spec;
    }

    private BatchValueTransform.Spec profile(EventType type, boolean customize, long randomSeed) {
        OffsetInput input = offsets.get(type);
        return customize && input != null ? input.read(randomSeed) : new BatchValueTransform.Spec();
    }

    private void refreshPreview() {
        content.removeCallbacks(updatePreview);
        Button apply = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        apply.setText(sourceMode.getSelectedItemPosition() == 0 ? R.string.clone_copy_action : R.string.clone_move_action);
        resultRows.removeAllViews();
        if (!sameSource()) { showError(owner.getString(R.string.clone_source_changed)); return; }
        if (targets.isEmpty()) { showError(owner.getString(R.string.clone_choose_hint)); return; }
        try {
            EventCloneOperation.Spec spec = readSpec();
            EventCloneOperation.Result result = EventCloneOperation.prepare(chart, sourceLine, sourceLayer, sources, spec);
            if (result.error != EventCloneOperation.Error.NONE) {
                showError(owner.eventCloneErrorMessage(result.error)); return;
            }
            status.setTextColor(Color.rgb(128, 203, 196));
            status.setText(owner.getString(R.string.clone_preview_summary, targets.size(), result.events.size(),
                    owner.getString(spec.keepSource ? R.string.clone_preview_copy : R.string.clone_preview_move)));
            double[] x = BatchValueTransform.values(spec.xProfile, targets.size());
            double[] y = BatchValueTransform.values(spec.yProfile, targets.size());
            double[] rotation = BatchValueTransform.values(spec.rotateProfile, targets.size());
            double[] alpha = BatchValueTransform.values(spec.alphaProfile, targets.size());
            BeatTime shift = BeatTime.zero();
            for (int i = 0; i < Math.min(12, targets.size()); i++) {
                BeatTime from = result.events.get(i * sources.size()).startTime;
                BeatTime to = result.events.get(i * sources.size()).endTime;
                for (int j = 1; j < sources.size(); j++) {
                    LineEvent event = result.events.get(i * sources.size() + j);
                    if (event.startTime.compareTo(from) < 0) from = event.startTime;
                    if (event.endTime.compareTo(to) > 0) to = event.endTime;
                }
                String text = owner.getString(R.string.clone_preview_line, i + 1, lineLabel(targets.get(i)),
                        from.toString(), to.toString(),
                        (shift.compareTo(BeatTime.zero()) >= 0 ? "+" : "") + shift.toString());
                String changes = offsetLabel("X", x[i]) + offsetLabel("Y", y[i])
                        + offsetLabel("Rotate", rotation[i]) + offsetLabel("Alpha", alpha[i]);
                owner.addHint(resultRows, text + (changes.isEmpty() ? "" : "\n" + changes.trim()));
                shift = shift.plus(spec.timeIncrement);
            }
            if (targets.size() > 12) owner.addHint(resultRows,
                    owner.getResources().getQuantityString(R.plurals.clone_more_preview,
                            targets.size() - 12, targets.size() - 12));
            apply.setEnabled(true);
        } catch (IllegalArgumentException | ArithmeticException error) {
            showError(owner.getString(R.string.event_clone_invalid));
        }
    }

    private static String offsetLabel(String label, double value) {
        return value == 0 ? "" : label + " " + (value > 0 ? "+" : "")
                + String.format(Locale.ROOT, "%.4g", value) + "   ";
    }

    private void showError(String message) {
        status.setTextColor(Color.rgb(255, 167, 149));
        status.setText(message);
        resultRows.removeAllViews();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
    }

    private String lineLabel(JudgeLine line) {
        return owner.getString(R.string.clone_line_label, chart.judgeLines.indexOf(line), line.name);
    }

    private void renderTargets() {
        targetRows.removeAllViews();
        if (targets.isEmpty()) { owner.addHint(targetRows, owner.getString(R.string.clone_choose_hint)); return; }
        int visible = showAllTargets ? targets.size() : Math.min(6, targets.size());
        for (int i = 0; i < visible; i++) {
            final int index = i;
            JudgeLine line = targets.get(i);
            owner.addHint(targetRows, (i + 1) + ". " + lineLabel(line));
            LinearLayout actions = new LinearLayout(owner);
            targetRows.addView(actions, new LinearLayout.LayoutParams(-1, -2));
            Button up = action(actions, "↑", () -> {
                Collections.swap(targets, index, index - 1); renderTargets(); schedulePreview();
            });
            up.setEnabled(i > 0); up.setContentDescription(owner.getString(R.string.clone_move_earlier, lineLabel(line)));
            Button down = action(actions, "↓", () -> {
                Collections.swap(targets, index, index + 1); renderTargets(); schedulePreview();
            });
            down.setEnabled(i + 1 < targets.size());
            down.setContentDescription(owner.getString(R.string.clone_move_later, lineLabel(line)));
            action(actions, owner.getString(R.string.clone_remove_line), () -> {
                targets.remove(index); renderTargets(); schedulePreview();
            }).setContentDescription(owner.getString(R.string.clone_remove_named_line, lineLabel(line)));
        }
        if (targets.size() > 6) {
            Button expand = owner.addMenuButton(targetRows, showAllTargets ? R.string.clone_show_less : R.string.clone_show_all);
            expand.setOnClickListener(v -> { showAllTargets = !showAllTargets; renderTargets(); });
        }
    }

    private Button action(LinearLayout parent, String label, Runnable callback) {
        Button button = new Button(owner); button.setText(label); button.setAllCaps(false);
        button.setMinWidth(0); button.setMinimumWidth(0); button.setPadding(owner.dp(3), 0, owner.dp(3), 0);
        button.setMinHeight(owner.dp(40)); button.setMinimumHeight(owner.dp(40));
        parent.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
        button.setOnClickListener(v -> callback.run()); return button;
    }

    private void chooseLines() {
        List<JudgeLine> draft = new ArrayList<>(targets);
        ScrollView scroll = new ScrollView(owner);
        LinearLayout body = owner.createDialogContent(scroll);
        EditText search = owner.addField(body, owner.getString(R.string.clone_search_lines), "", false);
        TextView count = owner.addHint(body, "");
        LinearLayout shortcuts = new LinearLayout(owner); body.addView(shortcuts);
        LinearLayout choices = new LinearLayout(owner); choices.setOrientation(LinearLayout.VERTICAL);
        LinearLayout navigation = new LinearLayout(owner);
        int[] page = {0};
        Runnable[] update = new Runnable[1];
        action(shortcuts, owner.getString(R.string.clone_all_other_lines), () -> {
            if (chart.judgeLines.size() - 1 > EventCloneOperation.MAX_TARGET_LINES) {
                count.setText(owner.getString(R.string.clone_line_limit)); return;
            }
            draft.clear(); for (JudgeLine line : chart.judgeLines) if (line != sourceLine) draft.add(line);
            update[0].run();
        });
        action(shortcuts, owner.getString(R.string.controls_clear), () -> { draft.clear(); update[0].run(); });
        body.addView(choices, new LinearLayout.LayoutParams(-1, -2));
        body.addView(navigation, new LinearLayout.LayoutParams(-1, -2));
        Button previous = action(navigation, owner.getString(R.string.clone_previous), () -> { page[0]--; update[0].run(); });
        Button next = action(navigation, owner.getString(R.string.clone_next), () -> { page[0]++; update[0].run(); });
        update[0] = () -> {
            count.setText(owner.getString(R.string.clone_selected_lines, draft.size()));
            choices.removeAllViews();
            String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            List<JudgeLine> matches = new ArrayList<>();
            for (JudgeLine line : chart.judgeLines) if (lineLabel(line).toLowerCase(Locale.ROOT).contains(query)) matches.add(line);
            int pages = Math.max(1, (matches.size() + 11) / 12);
            page[0] = Math.max(0, Math.min(pages - 1, page[0]));
            for (int i = page[0] * 12; i < Math.min(matches.size(), (page[0] + 1) * 12); i++) {
                JudgeLine line = matches.get(i);
                CheckBox check = new CheckBox(owner);
                check.setText(owner.getString(R.string.clone_picker_line, lineLabel(line),
                        line == sourceLine ? owner.getString(R.string.clone_source_suffix) : ""));
                check.setMinHeight(owner.dp(48)); check.setChecked(draft.contains(line));
                choices.addView(check, new LinearLayout.LayoutParams(-1, -2));
                check.setOnCheckedChangeListener((button, selected) -> {
                    if (selected && draft.size() >= EventCloneOperation.MAX_TARGET_LINES) {
                        check.setChecked(false); count.setText(owner.getString(R.string.clone_line_limit)); return;
                    }
                    if (selected) draft.add(line); else draft.remove(line);
                    count.setText(owner.getString(R.string.clone_selected_lines, draft.size()));
                });
            }
            if (matches.isEmpty()) owner.addHint(choices, owner.getString(R.string.clone_no_lines));
            previous.setEnabled(page[0] > 0); next.setEnabled(page[0] + 1 < pages);
        };
        EditorDialogSection.watch(() -> { page[0] = 0; update[0].run(); }, search);
        update[0].run();
        EditorPanelWindow chooser = new EditorPanelWindow.Builder(owner, owner.host.workbench())
                .setTitle(R.string.clone_choose_lines).setView(scroll)
                .setWorkbenchTab(dialog.tab)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.clone_use_lines, (d, which) -> {
                    targets.clear(); targets.addAll(draft); renderTargets(); schedulePreview();
                }).create();
        chooser.show();
    }

    private final class OffsetInput {
        final LinearLayout root;
        final Spinner mode;
        final EditText fixed, first, last, sequence, variation;
        final EasingSelector easing;
        final CheckBox advanced;

        OffsetInput(LinearLayout parent, EventType type) {
            root = new LinearLayout(owner); root.setOrientation(LinearLayout.VERTICAL);
            parent.addView(root, new LinearLayout.LayoutParams(-1, -2));
            mode = owner.addStringSpinner(root, owner.eventTypeLabel(type),
                    owner.resourceEntries(R.array.clone_offset_modes), 0);
            LinearLayout details = new LinearLayout(owner); details.setOrientation(LinearLayout.VERTICAL);
            root.addView(details, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout constant = new LinearLayout(owner); constant.setOrientation(LinearLayout.VERTICAL);
            details.addView(constant, new LinearLayout.LayoutParams(-1, -2));
            fixed = owner.addField(constant, owner.getString(R.string.clone_fixed_offset), "0", true);
            LinearLayout ramp = new LinearLayout(owner); ramp.setOrientation(LinearLayout.VERTICAL);
            details.addView(ramp, new LinearLayout.LayoutParams(-1, -2));
            first = owner.addField(ramp, owner.getString(R.string.clone_first_offset), "0", true);
            last = owner.addField(ramp, owner.getString(R.string.clone_last_offset), "0", true);
            easing = EasingSelector.addTo(ramp, owner.getString(R.string.clone_offset_curve), 1);
            advanced = owner.addCheckBox(details, R.string.clone_variation_toggle, false);
            LinearLayout extras = new LinearLayout(owner); extras.setOrientation(LinearLayout.VERTICAL);
            details.addView(extras, new LinearLayout.LayoutParams(-1, -2));
            sequence = owner.addField(extras, owner.getString(R.string.clone_multipliers), "1", false);
            variation = owner.addField(extras, owner.getString(R.string.clone_variation), "0", true);
            owner.addHint(extras, owner.getString(R.string.clone_variation_help));
            extras.setVisibility(View.GONE); details.setVisibility(View.GONE);
            EditorDialogSection.setSpinnerChangeListener(mode, () -> {
                int selected = mode.getSelectedItemPosition();
                details.setVisibility(selected == 0 ? View.GONE : View.VISIBLE);
                constant.setVisibility(selected == 1 ? View.VISIBLE : View.GONE);
                ramp.setVisibility(selected == 2 ? View.VISIBLE : View.GONE);
                schedulePreview();
            });
            advanced.setOnCheckedChangeListener((button, enabled) -> {
                extras.setVisibility(enabled ? View.VISIBLE : View.GONE); schedulePreview();
            });
            EditorDialogSection.watch(EventCloneDialog.this::schedulePreview, fixed, first, last, sequence, variation);
            easing.setOnChanged(EventCloneDialog.this::schedulePreview);
        }

        BatchValueTransform.Spec read(long randomSeed) {
            BatchValueTransform.Spec spec = new BatchValueTransform.Spec();
            if (mode.getSelectedItemPosition() == 0) return spec;
            if (mode.getSelectedItemPosition() == 1) spec.lowerBound = spec.upperBound = EditorDialogSection.parseDouble(fixed);
            else {
                spec.lowerBound = EditorDialogSection.parseDouble(first);
                spec.upperBound = EditorDialogSection.parseDouble(last);
                spec.easingType = easing.type();
            }
            if (advanced.isChecked()) {
                spec.periodicSequence = BatchValueTransform.parseSequence(sequence.getText().toString());
                spec.disturbance = EditorDialogSection.parseDouble(variation);
            }
            spec.randomSeed = randomSeed;
            if (!BatchValueTransform.isValid(spec)) throw new IllegalArgumentException("invalid offset");
            return spec;
        }
    }
}
