package com.xpe.mobile.i18n;

import android.content.Context;
import com.xpe.mobile.R;
import com.xpe.mobile.model.EventType;

/** Presentation mapping: the chart model keeps stable technical names and JSON keys. */
public final class ModelLabels {
    private ModelLabels() { }
    public static String event(Context context, EventType type) {
        int id = switch (type) {
            case MOVE_X -> R.string.event_type_move_x;
            case MOVE_Y -> R.string.event_type_move_y;
            case ROTATE -> R.string.event_type_rotate;
            case ALPHA -> R.string.event_type_alpha;
            case SPEED -> R.string.event_type_speed;
        };
        return UiText.string(context, id);
    }
}
