package com.xpe.mobile.preview;

import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.EventType;
import com.xpe.mobile.model.Note;
import com.xpe.mobile.model.NoteType;
import com.xpe.mobile.preview.PreviewChartIndex.PreparedChart;
import com.xpe.mobile.preview.PreviewChartIndex.PreparedNote;
import com.xpe.mobile.preview.PreviewLineEvaluator.LocalLine;
import com.xpe.mobile.preview.PreviewLineEvaluator.ResolvedLine;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Manual judgement, independent of Android input and of the autoplay preview state.
 * Behaviour reference: TeamFlos/phira, prpr/src/judge.rs at 1559e58 (also main).
 * Input positions are width-normalized, Y up; raw positions normalize each screen axis.
 */
public final class PlaytestJudge {
    public static final double PERFECT = .08, GOOD = .16, BAD = .22, RELEASE = .05;
    public static final double X_LIMIT = .21 / (16.0 / 9.0) * 2;
    private static final double NOTE_WIDTH = 0.13175016; // prpr core.rs, independent of sprite scale
    private static final int NEW = 0, PRE = 1, HOLD = 2, DONE = 3, SKIPPED = 4;
    public enum Grade { PERFECT, GOOD, BAD, MISS }
    public enum Phase { DOWN, MOVE, UP, CANCEL }

    public static final class Hit {
        public final Note note;
        public final int line;
        public final double seconds;
        public final Grade grade;
        /** Hold heads play a sound without committing the hold's final score. */
        public final boolean holdHead;
        final int seed;
        Hit(Entry e, double seconds, Grade grade, boolean holdHead) {
            this(e, seconds, grade, holdHead, 0);
        }
        Hit(Entry e, double seconds, Grade grade, boolean holdHead, int seed) {
            note = e.cached.note; line = e.line; this.seconds = seconds;
            this.grade = grade; this.holdHead = holdHead; this.seed = seed;
        }
        public boolean playsSound() {
            return holdHead || (note.type != NoteType.HOLD
                    && (grade == Grade.PERFECT || grade == Grade.GOOD));
        }
    }

    private final ChartDocument chart;
    private final PreparedChart prepared;
    private final double yScale;
    private final List<List<Entry>> lines = new ArrayList<>();
    private final int[] cursors;
    private final Map<Note, Entry> entries = new IdentityHashMap<>();
    private final Map<Integer, Finger> fingers = new LinkedHashMap<>();
    private final List<Input> inputs = new ArrayList<>();
    private final List<Hit> effects = new ArrayList<>();
    private final List<BadVisual> badVisuals = new ArrayList<>();
    private final List<Hit> pending = new ArrayList<>();
    private Consumer<Hit> listener;
    private ResolvedLine[] transforms;
    private double lastTime;
    private double lastEnd;
    private int total, combo, maxCombo;
    private int effectSequence = 1;
    private final int[] counts = new int[4];

    public PlaytestJudge(ChartDocument chart, boolean useRpe170Speed, double aspect, double startSeconds) {
        if (chart == null || !Double.isFinite(startSeconds) || startSeconds < 0)
            throw new IllegalArgumentException("Invalid playtest start");
        this.chart = chart;
        prepared = PreviewChartIndex.prepared(chart, useRpe170Speed);
        yScale = 1.5 / (Double.isFinite(aspect) && aspect > 0 ? aspect : 16.0 / 9);
        cursors = new int[prepared.lines.length];
        lastTime = lastEnd = startSeconds;
        for (int li = 0; li < prepared.lines.length; li++) {
            List<Entry> ordered = new ArrayList<>();
            if (chart.judgeLines.get(li).attachUi == null) {
                for (PreparedNote n : prepared.lines[li].notes) {
                    if (n.note.fake) continue;
                    Entry e = new Entry(li, n);
                    if (n.startSeconds < startSeconds) e.status = SKIPPED;
                    else { total++; lastEnd = Math.max(lastEnd, n.endSeconds); }
                    ordered.add(e); entries.put(n.note, e);
                }
            }
            // JudgeLineCache first orders drawing entries. Judge::new then performs a stable
            // sort by time; this tie order decides which overlapping note receives a click.
            ordered.sort(Comparator.comparing((Entry e) -> e.cached.note.type != NoteType.HOLD)
                    .thenComparing(e -> e.cached.note.above != 1)
                    .thenComparingDouble(e -> (float) e.cached.note.speed)
                    .thenComparingDouble(PlaytestJudge::drawOrderHeight));
            ordered.sort(Comparator.comparingDouble((Entry e) -> e.cached.startSeconds));
            lines.add(ordered);
        }
    }

    public void setListener(Consumer<Hit> listener) { this.listener = listener; }
    private static double drawOrderHeight(Entry e) {
        Note n = e.cached.note;
        float offset = (float) n.yOffset * 2f / 900f * (float) n.speed;
        return (e.cached.startDistance / 450 + offset) * (float) n.speed;
    }
    private static float judgeX(Note note) { return (float) note.positionX / 675f; }
    public int total() { return total; }
    public int combo() { return combo; }
    public int maxCombo() { return maxCombo; }
    public int count(Grade grade) { return counts[grade.ordinal()]; }
    public int judged() { return counts[0] + counts[1] + counts[2] + counts[3]; }
    public double lastNoteSeconds() { return lastEnd; }
    public double accuracy() { return total == 0 ? 1 : (counts[0] + counts[1] * .65) / total; }
    public int score() {
        if (total == 0) return 0;
        return counts[0] == total ? 1000000
                : (int) Math.round((.9 * accuracy() + .1 * maxCombo / total) * 1000000);
    }

    /** Queue every Android historical sample; judgement happens once per display frame. */
    public void queue(int id, Phase phase, double x, double y, double rawX, double rawY, double at) {
        if (phase == null || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(at)) return;
        inputs.add(new Input(id, phase, (float) x, (float) y, (float) rawX, (float) rawY, at));
    }

    /** Pause/cancellation must never leave a finger or a pending click behind. */
    public void clearTouches() { fingers.clear(); inputs.clear(); }

    public void update(double time) {
        if (!Double.isFinite(time) || time < lastTime) return;
        updateTransforms(time);
        Map<Integer, Finger> frame = new LinkedHashMap<>();
        for (Map.Entry<Integer, Finger> f : fingers.entrySet()) {
            f.getValue().down = false;
            frame.put(f.getKey(), f.getValue());
        }
        double step = (time - lastTime) / (inputs.size() + 1);
        double trackerTime = lastTime;
        for (Input in : inputs) {
            trackerTime += step;
            Finger f = frame.get(in.id);
            if (in.phase == Phase.DOWN) {
                f = new Finger(in, (float) trackerTime);
                frame.put(in.id, f); fingers.put(in.id, f);
            } else if (f != null) {
                f.x = in.x; f.y = in.y; f.at = in.at;
                if (in.phase == Phase.MOVE) f.tracker.push((float) trackerTime, in.rawX, in.rawY);
                else { f.ended = true; fingers.remove(in.id); }
            }
        }
        inputs.clear();
        for (Finger f : frame.values()) {
            boolean flick = !f.down && !f.ended && f.tracker.flicked;
            if (f.down || flick) select(f, f.down, time);
        }
        for (int li = 0; li < lines.size(); li++) {
            List<Entry> notes = lines.get(li);
            for (int i = cursors[li]; i < notes.size(); i++) {
                Entry e = notes.get(i);
                Note n = e.cached.note;
                if (e.status == HOLD) {
                    if (e.cached.endSeconds - time <= BAD) e.tailSafe = true;
                    else if (hasFinger(frame, e)) e.upAt = Double.POSITIVE_INFINITY;
                    else if (time > e.upAt + RELEASE) finish(e, Grade.MISS, time, .25);
                    else if (Double.isInfinite(e.upAt)) e.upAt = time;
                    continue;
                }
                if (e.status != NEW) continue;
                double dt = time - e.cached.startSeconds;
                if (dt > BAD) { finish(e, Grade.MISS, time, .25); continue; }
                if (-dt > BAD) break;
                if (n.type == NoteType.DRAG && hasFinger(frame, e)) e.status = PRE;
            }
        }
        for (int li = 0; li < lines.size(); li++) {
            List<Entry> notes = lines.get(li);
            for (int i = cursors[li]; i < notes.size(); i++) {
                Entry e = notes.get(i);
                if (e.status == HOLD && e.tailSafe && time >= e.cached.endSeconds) {
                    finish(e, e.perfect ? Grade.PERFECT : Grade.GOOD, time,
                            e.headAt - e.cached.startSeconds);
                    continue;
                }
                if (e.cached.note.type == NoteType.TAP && time + GOOD < e.cached.startSeconds) break;
                if (e.cached.note.type != NoteType.TAP && time < e.cached.startSeconds) continue;
                if (e.status != PRE) continue;
                double due = e.cached.startSeconds - (e.cached.note.type == NoteType.TAP ? GOOD : 0);
                if (time >= due) {
                    if (e.cached.note.type == NoteType.TAP) e.status = DONE;
                    else finish(e, Grade.PERFECT, time, 0);
                }
            }
            for (int i = cursors[li]; i < notes.size(); i++) {
                Entry e = notes.get(i);
                if (e.cached.startSeconds > time) break;
                if (e.status == HOLD && time > e.pulseAt) {
                    emitEffect(e, time, e.perfect ? Grade.PERFECT : Grade.GOOD);
                    e.pulseAt += .15;
                }
            }
            while (cursors[li] < notes.size() && notes.get(cursors[li]).status >= DONE) cursors[li]++;
        }
        for (Hit hit : pending) if (listener != null) listener.accept(hit);
        pending.clear();
        effects.removeIf(h -> time - h.seconds >= .5);
        badVisuals.removeIf(h -> time - h.seconds >= .5);
        lastTime = time;
    }

    private void select(Finger finger, boolean click, double now) {
        double at = Math.max(lastTime - BAD, Math.min(now, finger.at));
        double bestKey = BAD + Math.max(0, X_LIMIT / NOTE_WIDTH - 1) * .2;
        double bestDt = BAD;
        Entry best = null;
        for (int li = 0; li < lines.size(); li++) {
            double localX = localX(li, finger.x, finger.y);
            if (!Double.isFinite(localX)) continue;
            List<Entry> notes = lines.get(li);
            for (int i = cursors[li]; i < notes.size(); i++) {
                Entry e = notes.get(i);
                Note n = e.cached.note;
                if (e.status != NEW && e.status != PRE) continue;
                if (!click && (n.type == NoteType.TAP || n.type == NoteType.HOLD)) continue;
                double delta = e.cached.startSeconds - at;
                if (delta >= bestKey) break;
                double dt = delta < 0 ? Math.abs(Math.min(0, delta + .07)) : delta;
                double distance = Math.abs(judgeX(n) - (float) localX) / (double) (float) n.judgeArea;
                if (!Double.isFinite(distance) || distance > X_LIMIT) continue;
                if (dt > (n.type == NoteType.TAP ? BAD - PERFECT * Math.max(0, distance - .9) : GOOD)) continue;
                double priorityTime = dt + (n.type == NoteType.DRAG || n.type == NoteType.FLICK ? GOOD : 0);
                double key = priorityTime + Math.max(0, distance / NOTE_WIDTH - 1) * .2;
                if (key < bestKey) { best = e; bestDt = priorityTime; bestKey = key; }
            }
        }
        if (best == null) return;
        Note n = best.cached.note;
        if (n.type == NoteType.DRAG || click && n.type == NoteType.FLICK) return;
        if (!click) { best.status = PRE; finger.tracker.flicked = false; return; }
        if (n.type == NoteType.HOLD) {
            best.status = HOLD; best.perfect = bestDt <= PERFECT;
            best.headAt = at; best.pulseAt = at;
            pending.add(new Hit(best, now, best.perfect ? Grade.PERFECT : Grade.GOOD, true));
        } else if (bestDt <= GOOD) finish(best, bestDt <= PERFECT ? Grade.PERFECT : Grade.GOOD,
                now, at - best.cached.startSeconds);
        else if (best.status == NEW) {
            best.status = PRE;
            commit(best, Grade.BAD, now, now - best.cached.startSeconds);
            badVisuals.add(new BadVisual(best, now));
        }
    }

    private boolean hasFinger(Map<Integer, Finger> frame, Entry e) {
        Note note = e.cached.note;
        for (Finger f : frame.values()) {
            double dx = Math.abs(judgeX(note) - (float) localX(e.line, f.x, f.y)) / (double) (float) note.judgeArea;
            if (dx <= X_LIMIT && (e.status == HOLD || Math.abs(lastFrameTime - e.cached.startSeconds)
                    <= BAD - PERFECT * Math.max(0, dx - .9))) return true;
        }
        return false;
    }

    private double lastFrameTime;
    private void updateTransforms(double time) {
        lastFrameTime = time;
        LocalLine[] local = new LocalLine[lines.size()];
        transforms = new ResolvedLine[lines.size()];
        int[] state = new int[lines.size()];
        double beat = prepared.time.beat(time);
        for (int i = 0; i < local.length; i++) local[i] = new LocalLine(
                prepared.lines[i].valueAt(EventType.MOVE_X, beat, time),
                prepared.lines[i].valueAt(EventType.MOVE_Y, beat, time),
                -prepared.lines[i].valueAt(EventType.ROTATE, beat, time), 255);
        for (int i = 0; i < local.length; i++)
            PreviewLineEvaluator.resolveLine(chart, i, local, transforms, state, yScale);
    }

    private double localX(int line, double x, double y) {
        ResolvedLine tr = transforms[line];
        return (float) (tr.cos * (x - tr.x / 675) + tr.sin * (y - tr.y * yScale / 675));
    }

    private void finish(Entry e, Grade grade, double at, double diff) {
        e.status = DONE; e.result = grade; commit(e, grade, at, diff);
        if (e.cached.note.type != NoteType.HOLD && (grade == Grade.PERFECT || grade == Grade.GOOD))
            emitEffect(e, at, grade);
    }

    private void commit(Entry e, Grade grade, double at, double diff) {
        counts[grade.ordinal()]++;
        if (grade == Grade.PERFECT || grade == Grade.GOOD) maxCombo = Math.max(maxCombo, ++combo);
        else combo = 0;
        pending.add(new Hit(e, at, grade, false));
    }

    private void emitEffect(Entry e, double at, Grade grade) {
        effects.add(new Hit(e, at, grade, false, effectSequence++));
    }

    boolean hidden(Note note) {
        Entry e = entries.get(note);
        return e != null && (e.status == SKIPPED || e.status == DONE && note.type != NoteType.HOLD);
    }
    boolean missedHold(Note note) {
        Entry e = entries.get(note);
        return e != null && e.status == DONE && e.result == Grade.MISS && note.type == NoteType.HOLD;
    }
    int lineColor() { return counts[2] + counts[3] > 0 ? 0xFFFFFF : counts[1] > 0 ? 0xB4E1FF : -1; }

    List<RenderScene.RenderLine> badNotes(double time) {
        List<RenderScene.RenderLine> result = new ArrayList<>();
        for (BadVisual b : badVisuals) {
            double age = time - b.seconds;
            if (age < 0 || age >= .5) continue;
            RenderScene.RenderNote note = new RenderScene.RenderNote(b.type, b.x, b.y, b.y,
                    b.above, b.size, (int) (255 * (1 - age / .5)), false, false, false,
                    0x6C4343, b.sizeY, 0);
            result.add(new RenderScene.RenderLine(b.line, 0, b.transform.x, b.transform.y,
                    b.transform.rotationDegrees, 0, -1, 1, 1, b.incline, null, null,
                    false, false, false, false, 0, 0, Collections.singletonList(note),
                    Collections.emptyList(), Collections.emptyList()));
        }
        return result;
    }

    void appendEffects(int line, double time, List<RenderScene.HitEffect> target) {
        for (int i = 0; i < effects.size(); i++) {
            Hit h = effects.get(i);
            if (h.line != line || h.seconds > time || time - h.seconds >= .5) continue;
            ResolvedLine tr = PreviewLineEvaluator.resolveSingleLineAtSeconds(chart, prepared, line,
                    h.seconds, new boolean[lines.size()], yScale);
            Note n = h.note;
            double x = n.positionX, y = n.yOffset * n.speed * (n.above == 1 ? 1 : -1);
            double wx = tr.x + tr.cos * x - tr.sin * y * yScale;
            double wy = tr.y + tr.sin * x / yScale + tr.cos * y;
            int rgb = n.hasHitEffectTint ? n.hitEffectTintRgb : h.grade == Grade.GOOD ? 0xB4E1FF : 0xFFEC9F;
            target.add(new RenderScene.HitEffect(wx, wy, tr.x, tr.y, x, y, tr.rotationDegrees,
                    (time - h.seconds) / .5, rgb, h.seed,
                    n.hasHitEffectTint ? 255 : h.grade == Grade.GOOD ? 235 : 225));
        }
    }

    private static final class Entry {
        final int line;
        final PreparedNote cached;
        int status;
        Grade result;
        boolean perfect, tailSafe;
        double headAt, pulseAt, upAt = Double.POSITIVE_INFINITY;
        Entry(int line, PreparedNote cached) { this.line = line; this.cached = cached; }
    }
    private final class BadVisual {
        final int line;
        final NoteType type;
        final boolean above;
        final double seconds, x, y, size, sizeY, incline;
        final ResolvedLine transform;
        BadVisual(Entry e, double at) {
            Note n = e.cached.note;
            line = e.line; type = n.type; above = n.above == 1; seconds = at;
            transform = transforms[line];
            double base = e.cached.startDistance - prepared.lines[line].speed.distanceAtSeconds(at);
            com.xpe.mobile.model.NoteControlEvents ctrl = chart.judgeLines.get(line).noteControls;
            double position = ctrl == null ? 1 : PrprEvents.control(ctrl.position, base + n.yOffset, 1);
            double scale = ctrl == null ? 1 : PrprEvents.control(ctrl.size, base + n.yOffset, 1);
            double travel = ctrl == null ? 1 : PrprEvents.control(ctrl.y, base + n.yOffset, 1);
            x = ChartEvaluator.finiteOr(n.positionX * position, 0);
            y = ChartEvaluator.finiteOr(base * n.speed * travel + n.yOffset * n.speed, 0);
            size = ChartEvaluator.finiteOr(n.size * scale, 1); sizeY = chart.noteUniformScale ? size : 1;
            com.xpe.mobile.model.ExtendedLineEvents extended = chart.judgeLines.get(line).extended;
            incline = extended == null ? 0 : PrprEvents.numericAtSeconds(extended.inclineEvents,
                    prepared.time, at, 0, false);
        }
    }
    private static final class Input {
        final int id;
        final Phase phase;
        final float x, y, rawX, rawY;
        final double at;
        Input(int id, Phase phase, float x, float y, float rawX, float rawY, double at) {
            this.id = id; this.phase = phase; this.x = x; this.y = y;
            this.rawX = rawX; this.rawY = rawY; this.at = at;
        }
    }
    private static final class Finger {
        float x, y;
        double at;
        boolean down = true, ended;
        final FlickTracker tracker;
        Finger(Input in, float time) {
            x = in.x; y = in.y; at = in.at;
            tracker = new FlickTracker(time, in.rawX, in.rawY);
        }
    }
    static final class FlickTracker {
        private static final float THRESHOLD = .8f * 275 / 386;
        float x, y, dx, dy, time;
        boolean hasDelta, flicked, stopped = true;
        FlickTracker(float time, float x, float y) { this.time = time; this.x = x; this.y = y; }
        void push(float now, float px, float py) {
            float mx = px - x, my = py - y;
            x = px; y = py;
            float length = (float) Math.sqrt(mx * mx + my * my), dt = now - time;
            if (hasDelta) {
                if ((mx * dx + my * dy) / dt < THRESHOLD) stopped = true;
                if (stopped && !flicked) flicked = length / dt >= THRESHOLD * 2;
            }
            dx = mx / length; dy = my / length;
            hasDelta = true; time = now;
        }
    }
}
