package com.xpe.mobile.audio;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;

/** Keeps a vendor codec/output cause visible when an IOException supplies a generic wrapper. */
final class AudioErrorMessages {
    private AudioErrorMessages() { }

    static String describe(Throwable error) {
        if (error == null) return "Unknown audio error";
        Set<String> messages = new LinkedHashSet<>();
        List<Throwable> causes = new ArrayList<>();
        Throwable current = error;
        for (int depth = 0; current != null && depth < 4; depth++, current = current.getCause()) {
            causes.add(current);
        }
        // A long private PCM path can otherwise push ENOENT/EMFILE off the visible toast.
        for (int i = causes.size() - 1; i >= 0; i--) {
            current = causes.get(i);
            String message = current.getMessage();
            if (message != null && !message.trim().isEmpty()) messages.add(message.trim());
            else if (current.getCause() == null) messages.add(current.getClass().getSimpleName());
        }
        return messages.isEmpty() ? error.getClass().getSimpleName() : String.join(": ", messages);
    }
}
