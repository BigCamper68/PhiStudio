package com.xpe.mobile.editor;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import com.xpe.mobile.model.*;
import java.io.*;
import java.util.*;

/** Real Android Canvas checks for note opacity and the shared Tap selection frame. */
public final class EditorNoteDeviceChecks {
    static final class FrameCanvas extends Canvas {
        final List<RectF> frames = new ArrayList<>();
        FrameCanvas(Bitmap bitmap) { super(bitmap); }
        @Override public void drawRoundRect(RectF rect, float rx, float ry, Paint paint) {
            if (paint.getColor() == Color.WHITE && paint.getStyle() == Paint.Style.STROKE)
                frames.add(new RectF(rect));
            super.drawRoundRect(rect, rx, ry, paint);
        }
    }
    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    static Bitmap draw(EditorView editor, int width, int height) {
        Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        editor.drawNotes(new Canvas(result), 0, 0, width, height);
        return result;
    }
    static int[] pixels(Bitmap bitmap) {
        int[] values = new int[bitmap.getWidth() * bitmap.getHeight()];
        bitmap.getPixels(values, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
        return values;
    }
    public static void run(Context context, File root) throws Exception {
        EditorView editor = new EditorView(context);
        editor.setChart(ChartDocument.fromJson("{}"));
        editor.measure(View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.EXACTLY));
        editor.layout(0, 0, 1200, 700);
        editor.currentBeat = 4;
        editor.pixelsPerBeat = 100;
        editor.settings.highlightSimultaneousNotes = false;
        Bitmap capture = Bitmap.createBitmap(1200, 700, Bitmap.Config.ARGB_8888);
        capture.eraseColor(Color.rgb(17, 23, 30));
        Canvas destination = new Canvas(capture);
        Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        label.setColor(Color.WHITE); label.setTextSize(30); label.setTextAlign(Paint.Align.CENTER);
        RectF tapFrame = null;
        int column = 0;
        for (NoteType type : new NoteType[]{NoteType.TAP, NoteType.DRAG, NoteType.FLICK, NoteType.HOLD}) {
            Note note = new Note(); note.type = type; note.alpha = 0; note.size = 1.3;
            note.startTime = new BeatTime(4, 0, 1);
            note.endTime = new BeatTime(type == NoteType.HOLD ? 6 : 4, 0, 1);
            editor.currentLine().notes.clear(); editor.currentLine().notes.add(note);
            editor.selectedNote = null;
            Bitmap zero = draw(editor, 300, 650);
            require(note.alpha == 0, type + " stored Alpha preserved");
            int[] zeroPixels = pixels(zero);
            require(Arrays.stream(zeroPixels).anyMatch(pixel -> Color.alpha(pixel) > 0), type + " Alpha=0 remains visible in beatmap");
            note.alpha = 255;
            Bitmap opaque = draw(editor, 300, 650);
            require(Arrays.equals(zeroPixels, pixels(opaque)), type + " grid pixels ignore Note properties Alpha");
            zero.recycle(); opaque.recycle();

            note.alpha = 0; editor.selectedNote = note;
            Bitmap selected = Bitmap.createBitmap(300, 650, Bitmap.Config.ARGB_8888);
            FrameCanvas canvas = new FrameCanvas(selected);
            editor.drawNotes(canvas, 0, 0, 300, 650);
            require(canvas.frames.size() == 1, type + " one selected frame");
            RectF frame = canvas.frames.get(0);
            if (type == NoteType.TAP) tapFrame = frame;
            else if (type != NoteType.HOLD) require(tapFrame.equals(frame), type + " frame matches Tap");
            else require(Math.abs(frame.height() - (200 + 6 * editor.density)) < .01,
                    "Hold frame keeps its original length");
            destination.drawBitmap(selected, 300 * column, 30, null);
            destination.drawText(type.toString() + " / Alpha 0", 300 * column + 150, 45, label);
            selected.recycle(); column++;
        }
        try (OutputStream stream = new FileOutputStream(new File(root, "fix24-note-grid.png"))) {
            capture.compress(Bitmap.CompressFormat.PNG, 100, stream);
        }
        capture.recycle();
        System.out.println("PASS fixes24 Android note pixels: Alpha=0/255 identical for all four types; Tap/Drag/Flick share white frame; Hold frame unchanged");
    }
}
