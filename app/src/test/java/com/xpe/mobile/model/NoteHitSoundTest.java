package com.xpe.mobile.model;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class NoteHitSoundTest {
    @Test public void hitSoundIsEditableCopiedAndRoundTripsWithUnknownFields() throws Exception {
        Note note = Note.fromJson(new JSONObject("{\"hitsound\":\"sounds/custom.ogg\",\"future\":17}"));
        assertEquals("sounds/custom.ogg", note.hitSound);
        assertEquals(note.hitSound, note.copy().hitSound);
        note.hitSound = "tap.mp3";
        JSONObject encoded = note.toJson();
        assertEquals("tap.mp3", encoded.getString("hitsound"));
        assertEquals(17, encoded.getInt("future"));
        assertEquals("tap.mp3", Note.fromJson(encoded).hitSound);
        note.hitSound = null;
        assertFalse(note.toJson().has("hitsound"));
        assertNull(Note.fromJson(note.toJson()).hitSound);
        assertNull(Note.fromJson(new JSONObject("{\"hitsound\":null}")).hitSound);
    }

    @Test public void prprReservedNamesOverrideTypeWithoutBeingPackageFiles() {
        assertEquals(NoteType.TAP, HitSoundReference.soundType(NoteType.DRAG, "tap.mp3"));
        assertEquals(NoteType.FLICK, HitSoundReference.soundType(NoteType.TAP, "flick.mp3"));
        assertEquals(NoteType.DRAG, HitSoundReference.soundType(NoteType.HOLD, "drag.mp3"));
        assertEquals(NoteType.HOLD, HitSoundReference.soundType(NoteType.HOLD, null));
        assertFalse(HitSoundReference.isCustom("tap.mp3"));
        assertFalse(HitSoundReference.isCustom(""));
        assertTrue(HitSoundReference.isCustom("sounds/tap.mp3"));
        assertTrue(HitSoundReference.isCustom("./tap.mp3"));
    }
}
