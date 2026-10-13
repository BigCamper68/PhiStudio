package com.xpe.mobile.editor;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.RectF;
import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.Note;
import com.xpe.mobile.model.NoteType;
import java.util.Set;

/** EditorNotePainter responsibilities extracted from EditorSurfaceView. */
final class EditorNotePainter {
    private final EditorSurfaceView delegateOwner;

    EditorNotePainter(EditorSurfaceView delegateOwner) {
        this.delegateOwner = delegateOwner;
    }

    final void drawNotes(
            Canvas canvas, float left, float top, float right, float bottom) {
        float centerY = delegateOwner.timelineY(bottom);
        Set<Note> multiHitNotes = delegateOwner.multiHintNotes();
        for (Note note : delegateOwner.currentLine().notes) {
            boolean useMultiHit = multiHitNotes.contains(note);
            BeatTime visibleStart = delegateOwner.visibleNoteStart(note);
            BeatTime visibleEnd = delegateOwner.visibleNoteEnd(note);
            float startY = delegateOwner.beatToY(visibleStart.toDouble(), centerY);
            float x = EditorSurfaceView.chartXToScreen(delegateOwner.visibleNoteX(note), left, right);
            float naturalWidth = (float) delegateOwner.settings.noteWidthPixels * delegateOwner.density
                    * delegateOwner.noteTextures.widthScale(useMultiHit);
            float textureWidth = EditorNoteMetrics.width(naturalWidth, note.size);
            RectF bounds;

            if (note.type == NoteType.HOLD) {
                float endY = delegateOwner.beatToY(visibleEnd.toDouble(), centerY);
                bounds = delegateOwner.holdBounds(x, startY, endY, textureWidth);
                if (bounds.bottom < top || bounds.top > bottom) continue;
                delegateOwner.drawHoldTexture(canvas, x, startY, endY, textureWidth, naturalWidth, useMultiHit, 255);
            } else {
                Bitmap bitmap = delegateOwner.noteTextures.bitmap(note.type, useMultiHit);
                float textureHeight = EditorNoteMetrics.height(naturalWidth,
                        bitmap.getWidth(), bitmap.getHeight());
                bounds = new RectF(x - textureWidth / 2f, startY - textureHeight / 2f,
                        x + textureWidth / 2f, startY + textureHeight / 2f);
                if (bounds.bottom < top || bounds.top > bottom) continue;
                delegateOwner.drawBitmap(canvas, bitmap, bounds, 255);
            }

            boolean selected = note == delegateOwner.selectedNote || delegateOwner.selectedNotes.contains(note);
            boolean fakeOutline = note.fake && delegateOwner.settings.showFakeNoteOutline;
            RectF outline = bounds;
            if ((selected || fakeOutline) && note.type != NoteType.HOLD) {
                Bitmap tap = delegateOwner.noteTextures.bitmap(NoteType.TAP, useMultiHit);
                float tapHeight = EditorNoteMetrics.height(naturalWidth, tap.getWidth(), tap.getHeight());
                outline = new RectF(x - textureWidth / 2f, startY - tapHeight / 2f,
                        x + textureWidth / 2f, startY + tapHeight / 2f);
            }
            if (fakeOutline) {
                delegateOwner.strokePaint.setColor(Color.WHITE);
                delegateOwner.strokePaint.setStrokeWidth(1.5f * delegateOwner.density);
                canvas.drawRoundRect(outline, 5f * delegateOwner.density, 5f * delegateOwner.density, delegateOwner.strokePaint);
            }
            if (selected) {
                delegateOwner.drawSelection(canvas, outline);
                if (note == delegateOwner.selectedNote && delegateOwner.selectionCount() == 1
                        && note.type == NoteType.HOLD) {
                    delegateOwner.drawHoldHandles(canvas, x, startY,
                            delegateOwner.beatToY(visibleEnd.toDouble(), centerY),
                            delegateOwner.noteDragValidation != NoteTouchOperation.Error.NONE);
                }
            }
        }
    }

    final RectF holdBounds(float x, float startY, float endY, float width) {
        return new RectF(x - width / 2f, Math.min(startY, endY),
                x + width / 2f, Math.max(startY, endY));
    }

    final void drawBitmap(
            Canvas canvas, Bitmap bitmap, RectF destination, int alpha) {
        delegateOwner.noteTexturePaint.setAlpha(alpha);
        canvas.drawBitmap(bitmap, null, destination, delegateOwner.noteTexturePaint);
    }

    final void drawHoldTexture(Canvas canvas, float x, float startY, float endY,
                                         float width, float naturalWidth, boolean useMultiHit, int alpha) {
        Bitmap bitmap = delegateOwner.noteTextures.bitmap(NoteType.HOLD, useMultiHit);
        if (startY < endY) {
            RectF destination = delegateOwner.holdBounds(x, startY, endY, width);
            delegateOwner.drawBitmap(canvas, bitmap, destination, alpha);
            return;
        }

        int tailPixels = delegateOwner.noteTextures.holdTailPixels(useMultiHit);
        int headPixels = delegateOwner.noteTextures.holdHeadPixels(useMultiHit);
        int bodyBottom = Math.max(tailPixels + 1, bitmap.getHeight() - headPixels);
        float left = x - width / 2f;
        float right = x + width / 2f;
        float tailHeight = EditorNoteMetrics.height(naturalWidth, bitmap.getWidth(), tailPixels);
        float headHeight = EditorNoteMetrics.height(naturalWidth, bitmap.getWidth(), headPixels);

        delegateOwner.noteTexturePaint.setAlpha(alpha);
        canvas.drawBitmap(bitmap,
                new Rect(0, tailPixels, bitmap.getWidth(), bodyBottom),
                new RectF(left, endY, right, startY), delegateOwner.noteTexturePaint);
        canvas.drawBitmap(bitmap,
                new Rect(0, 0, bitmap.getWidth(), tailPixels),
                new RectF(left, endY, right, endY + tailHeight), delegateOwner.noteTexturePaint);
        canvas.drawBitmap(bitmap,
                new Rect(0, bitmap.getHeight() - headPixels,
                        bitmap.getWidth(), bitmap.getHeight()),
                new RectF(left, startY - headHeight, right, startY), delegateOwner.noteTexturePaint);
    }

    final void drawHoldHandles(
            Canvas canvas, float centerX, float startY, float endY, boolean invalidDrag) {
        delegateOwner.drawEventHandles(canvas, centerX, startY, endY, invalidDrag);
    }
}
