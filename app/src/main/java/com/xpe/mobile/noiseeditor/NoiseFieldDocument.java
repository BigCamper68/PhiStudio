package com.xpe.mobile.noiseeditor;

import com.xpe.mobile.editor.EditHistory;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.NoiseField;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.json.JSONException;
import org.json.JSONObject;

/** The field editor shares the chart's history and autosave path, not a second chart copy. */
public final class NoiseFieldDocument {
    public final ChartDocument chart;
    private final EditHistory history;
    private final BooleanSupplier scopeValid;
    private NoiseField clipboard;
    private LiveEdit liveEdit;
    public int selected = -1, selectedKey = -1;
    public NoiseFieldEdits.Channel channel;

    public NoiseFieldDocument(ChartDocument chart, EditHistory history, BooleanSupplier scopeValid) {
        this.chart = chart; this.history = history; this.scopeValid = scopeValid;
        if (!chart.noiseFields.isEmpty()) selected = 0;
    }
    public boolean valid() { return scopeValid.getAsBoolean(); }
    public NoiseField field() {
        return valid() && selected >= 0 && selected < chart.noiseFields.size()
                ? chart.noiseFields.get(selected) : null;
    }
    public int count() { return chart.noiseFields.size(); }
    public boolean canUndo() { return valid() && history.canUndo(); }
    public boolean canRedo() { return valid() && history.canRedo(); }
    public void undo() { if (valid()) history.undo(); }
    public void redo() { if (valid()) history.redo(); }
    public boolean canPaste() { return clipboard != null && valid(); }
    public void copy() { if (field() != null) clipboard = field(); }
    public String name(int index) { return chart.noiseFields.get(index).toJson().optString(NoiseFieldEdits.LABEL, ""); }

    public void select(int index) {
        selected = Math.max(-1, Math.min(count() - 1, index)); selectedKey = -1;
    }
    public void selectKey(NoiseFieldEdits.Channel next, int index) { channel = next; selectedKey = index; }

    public void add(double seconds, double duration, boolean subtract) throws JSONException {
        insert(NoiseFieldEdits.create(seconds, duration, subtract));
    }
    public void paste(double seconds) throws JSONException {
        if (canPaste()) insert(NoiseFieldEdits.shifted(clipboard, seconds - clipboard.appear));
    }
    public void duplicate(double seconds) throws JSONException {
        if (field() != null) insert(NoiseFieldEdits.shifted(field(), seconds - field().appear));
    }
    private void insert(NoiseField field) {
        List<NoiseField> after = new ArrayList<>(chart.noiseFields);
        int index = selected < 0 ? after.size() : selected + 1;
        after.add(index, field);
        commit(after, index, null, -1);
    }
    public boolean replace(NoiseField expected, NoiseField value) {
        if (field() != expected) return false;
        if (expected.toJson().toString().equals(value.toJson().toString())) return false;
        List<NoiseField> after = new ArrayList<>(chart.noiseFields);
        after.set(selected, value);
        commit(after, selected, channel, selectedKey);
        return true;
    }

    /** Every valid keystroke reaches the chart; one input session is one undo step. */
    public boolean replaceLive(NoiseField expected, NoiseField value, Object inputSession) {
        if (!valid() || field() != expected) return false;
        if (expected.toJson().toString().equals(value.toJson().toString())) return false;
        List<NoiseField> after = new ArrayList<>(chart.noiseFields);
        after.set(selected, value);
        LiveEdit previous = liveEdit;
        LiveEdit next = new LiveEdit(inputSession, new ArrayList<>(chart.noiseFields), after,
                selected, channel, selectedKey);
        if (previous != null && previous.session == inputSession && previous.index == selected
                && previous.channel == channel && previous.key == selectedKey) {
            next = new LiveEdit(inputSession, previous.before, after, selected, channel, selectedKey);
            if (history.replaceLast(previous, next)) { liveEdit = next; return true; }
            next = new LiveEdit(inputSession, new ArrayList<>(chart.noiseFields), after, selected, channel, selectedKey);
        }
        history.execute(next); liveEdit = next;
        return true;
    }
    private final class LiveEdit implements EditHistory.Command {
        final Object session;
        final List<NoiseField> before, after;
        final int index, key;
        final NoiseFieldEdits.Channel channel;
        LiveEdit(Object session, List<NoiseField> before, List<NoiseField> after, int index,
                 NoiseFieldEdits.Channel channel, int key) {
            this.session = session; this.before = before; this.after = after;
            this.index = index; this.channel = channel; this.key = key;
        }
        @Override public void apply() { restore(after, index, channel, key); }
        @Override public void revert() { restore(before, index, channel, key); }
    }
    public void delete() {
        if (field() == null) return;
        List<NoiseField> after = new ArrayList<>(chart.noiseFields);
        after.remove(selected);
        commit(after, Math.min(selected, after.size() - 1), null, -1);
    }
    public void addKey(double seconds) throws JSONException {
        NoiseField field = field();
        if (field == null || channel == null) return;
        int index = NoiseFieldEdits.keyCount(field, channel);
        replaceKeySnapshot(field, NoiseFieldEdits.withKey(field, channel, -1,
                NoiseFieldEdits.sampledKey(field, channel, seconds)), index);
    }
    public void duplicateKey(double seconds) throws JSONException {
        if (field() == null || channel == null || selectedKey < 0) return;
        JSONObject key = NoiseFieldEdits.copy(NoiseFieldEdits.key(field(), channel, selectedKey)).put("time", seconds);
        replaceKeySnapshot(field(), NoiseFieldEdits.withKey(field(), channel, -1, key),
                NoiseFieldEdits.keyCount(field(), channel));
    }
    public void deleteKey() throws JSONException {
        if (field() == null || channel == null || selectedKey < 0) return;
        replaceKeySnapshot(field(), NoiseFieldEdits.withoutKey(field(), channel, selectedKey), -1);
    }
    private void replaceKeySnapshot(NoiseField before, NoiseField afterField, int key) {
        if (field() != before) return;
        List<NoiseField> after = new ArrayList<>(chart.noiseFields);
        after.set(selected, afterField);
        commit(after, selected, channel, key);
    }
    private void commit(List<NoiseField> after, int next, NoiseFieldEdits.Channel nextChannel, int nextKey) {
        if (!valid()) return;
        List<NoiseField> before = new ArrayList<>(chart.noiseFields);
        int previous = selected, previousKey = selectedKey;
        NoiseFieldEdits.Channel previousChannel = channel;
        history.execute(new EditHistory.Command() {
            @Override public void apply() { restore(after, next, nextChannel, nextKey); }
            @Override public void revert() { restore(before, previous, previousChannel, previousKey); }
        });
    }
    private void restore(List<NoiseField> snapshot, int index, NoiseFieldEdits.Channel nextChannel, int key) {
        chart.noiseFields.clear(); chart.noiseFields.addAll(snapshot);
        selected = index; channel = nextChannel; selectedKey = key;
    }
}
