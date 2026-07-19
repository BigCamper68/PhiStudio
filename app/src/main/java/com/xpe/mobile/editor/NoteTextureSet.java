package com.xpe.mobile.editor;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.xpe.mobile.R;
import com.xpe.mobile.model.NoteType;

import java.util.EnumMap;
import java.util.Map;

public final class NoteTextureSet {
    private static final int HOLD_TAIL_PIXELS = 50;
    private static final int HOLD_HEAD_PIXELS = 50;
    private static final int HOLD_MH_TAIL_PIXELS = 50;
    private static final int HOLD_MH_HEAD_PIXELS = 95;
    private static final int HIT_EFFECT_COLUMNS = 5;
    private static final int HIT_EFFECT_ROWS = 6;

    private final Map<NoteType, Bitmap> normal = new EnumMap<>(NoteType.class);
    private final Map<NoteType, Bitmap> multiHit = new EnumMap<>(NoteType.class);
    private final Bitmap hitEffect;

    public NoteTextureSet(Resources resources) {
        normal.put(NoteType.TAP, decode(resources, R.drawable.note_click));
        normal.put(NoteType.DRAG, decode(resources, R.drawable.note_drag));
        normal.put(NoteType.FLICK, decode(resources, R.drawable.note_flick));
        normal.put(NoteType.HOLD, decode(resources, R.drawable.note_hold));

        multiHit.put(NoteType.TAP, decode(resources, R.drawable.note_click_mh));
        multiHit.put(NoteType.DRAG, decode(resources, R.drawable.note_drag_mh));
        multiHit.put(NoteType.FLICK, decode(resources, R.drawable.note_flick_mh));
        multiHit.put(NoteType.HOLD, decode(resources, R.drawable.note_hold_mh));
        hitEffect = decode(resources, R.drawable.hit_fx);
    }

    public Bitmap bitmap(NoteType type, boolean useMultiHit) {
        return (useMultiHit ? multiHit : normal).get(type);
    }

    public float widthScale(boolean useMultiHit) {
        if (!useMultiHit) return 1f;
        Bitmap regular = normal.get(NoteType.TAP);
        Bitmap highlighted = multiHit.get(NoteType.TAP);
        return highlighted.getWidth() / (float) regular.getWidth();
    }

    public int holdTailPixels(boolean useMultiHit) {
        return useMultiHit ? HOLD_MH_TAIL_PIXELS : HOLD_TAIL_PIXELS;
    }

    public int holdHeadPixels(boolean useMultiHit) {
        return useMultiHit ? HOLD_MH_HEAD_PIXELS : HOLD_HEAD_PIXELS;
    }

    public Bitmap hitEffectBitmap() {
        return hitEffect;
    }

    public int hitEffectColumns() {
        return HIT_EFFECT_COLUMNS;
    }

    public int hitEffectRows() {
        return HIT_EFFECT_ROWS;
    }

    private static Bitmap decode(Resources resources, int resourceId) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        Bitmap bitmap = BitmapFactory.decodeResource(resources, resourceId, options);
        if (bitmap == null) {
            throw new IllegalStateException("Unable to decode bundled note texture " + resourceId);
        }
        return bitmap;
    }
}
