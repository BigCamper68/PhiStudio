package com.xpe.mobile.editor;

import com.xpe.mobile.model.LineEvent;
import com.xpe.mobile.model.Note;

/** EditorFieldCopies responsibilities extracted from EditorSurfaceView. */
final class EditorFieldCopies {
    static void copyNoteFields(Note source, Note target) {
        target.above = source.above;
        target.alpha = source.alpha;
        target.startTime = source.startTime;
        target.endTime = source.endTime;
        target.fake = source.fake;
        target.positionX = source.positionX;
        target.size = source.size;
        target.speed = source.speed;
        target.type = source.type;
        target.visibleTime = source.visibleTime;
        target.yOffset = source.yOffset;
        target.hasTint = source.hasTint;
        target.tintRgb = source.tintRgb;
        target.hasHitEffectTint = source.hasHitEffectTint;
        target.hitEffectTintRgb = source.hitEffectTintRgb;
        target.judgeArea = source.judgeArea;
    }

    static void copyEventFields(LineEvent source, LineEvent target) {
        target.type = source.type;
        target.startTime = source.startTime;
        target.endTime = source.endTime;
        target.start = source.start;
        target.end = source.end;
        target.easingType = source.easingType;
        target.easingLeft = source.easingLeft;
        target.easingRight = source.easingRight;
        target.linkGroup = source.linkGroup;
        target.bezier = source.bezier;
        System.arraycopy(source.bezierPoints, 0, target.bezierPoints, 0,
                source.bezierPoints.length);
    }
}
