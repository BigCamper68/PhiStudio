package com.xpe.mobile.preview;

import com.xpe.mobile.model.NoiseField;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Lossless reimport of our generated Line2Area tracks, guarded against external edits. */
public final class NoiseFieldRpeSource {
    private NoiseFieldRpeSource() { }

    public static JSONObject restore(JSONObject root) throws JSONException {
        JSONObject source = root.optJSONObject("phiStudioNoiseSource");
        if (source == null || source.optInt("version") < 1 || source.optInt("version") > 2) return root;
        JSONArray lines = root.optJSONArray("judgeLineList"), fields = source.optJSONArray("fields");
        int base = source.optInt("baseLineCount", -1);
        if (lines == null || fields == null || base < 0 || lines.length() != base + fields.length()) return root;
        JSONArray nativeFields = root.optJSONArray("blockAreaList");
        if (nativeFields != null && nativeFields.length() > 0) return root;
        JSONArray generated = new JSONArray();
        for (int i = base; i < lines.length(); i++) generated.put(lines.get(i));
        if (source.optInt("version") == 2) {
            if (!hash(root.optJSONArray("BPMList")).equals(source.optString("bpmHash"))
                    || !hash(generated).equals(source.optString("lineHash"))) return root;
        } else {
            if (!legacyMatches(root.optJSONArray("BPMList"), source.optString("bpmHash"))) return root;
            if (!legacyMatches(generated, source.optString("lineHash"))
                    && !matchesSavedLegacyTracks(root, fields, generated)) return root;
        }
        // Validate before replacing anything, and never remove a referenced/edited guide line.
        for (int i = 0; i < base; i++) {
            JSONObject line = lines.optJSONObject(i);
            if (line != null && line.optInt("father", -1) >= base) return root;
        }
        try { NoiseField.fromJson(fields); } catch (JSONException invalid) { return root; }
        JSONObject restored = new JSONObject();
        var keys = root.keys();
        while (keys.hasNext()) { String key = keys.next(); if (!"phiStudioNoiseSource".equals(key)) restored.put(key, root.get(key)); }
        JSONArray originalLines = new JSONArray();
        for (int i = 0; i < base; i++) originalLines.put(lines.get(i));
        return restored.put("judgeLineList", originalLines).put("blockAreaList", fields);
    }

    public static String hash(Object value) throws JSONException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            hashValue(digest, value);
            StringBuilder result = new StringBuilder();
            for (byte b : digest.digest()) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static void hashValue(MessageDigest digest, Object value) throws JSONException {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            ArrayList<String> keys = new ArrayList<>(); object.keys().forEachRemaining(keys::add); Collections.sort(keys);
            update(digest, "{");
            for (String key : keys) { hashValue(digest, key); hashValue(digest, object.get(key)); }
            update(digest, "}");
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value; update(digest, "[");
            for (int i = 0; i < array.length(); i++) { hashValue(digest, array.get(i)); update(digest, ","); }
            update(digest, "]");
        } else if (value instanceof Number) {
            double number = ((Number) value).doubleValue();
            // JSON -0 and 0 denote the same value; Android parses integer -0 as +0.
            long bits = Double.doubleToLongBits(number == 0 ? 0 : number);
            update(digest, "#" + Long.toHexString(bits) + ";");
        } else if (value == null || value == JSONObject.NULL) update(digest, "null;");
        else if (value instanceof Boolean) update(digest, (Boolean) value ? "true;" : "false;");
        else {
            byte[] text = value.toString().getBytes(StandardCharsets.UTF_8);
            update(digest, "s" + text.length + ":"); digest.update(text);
        }
    }
    /** Preserve checkpoint 9's signed-zero wire tokens before Android JSONTokener loses them. */
    public static JSONObject parse(String json) throws JSONException {
        if (!json.contains("phiStudioNoiseSource")) return new JSONObject(json);
        StringBuilder normalized = null;
        int copied = 0;
        char quote = 0;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (quote != 0) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == quote) quote = 0;
                continue;
            }
            if (c == '"' || c == '\'') { quote = c; continue; }
            if (c != '-' || i + 1 >= json.length() || json.charAt(i + 1) != '0') continue;
            int end = i + 2;
            if (end < json.length() && !Character.isWhitespace(json.charAt(end))
                    && json.charAt(end) != ',' && json.charAt(end) != '}' && json.charAt(end) != ']') continue;
            if (normalized == null) normalized = new StringBuilder(json.length() + 32);
            normalized.append(json, copied, end).append(".0"); copied = end; i++;
        }
        return new JSONObject(normalized == null ? json : normalized.append(json, copied, json.length()).toString());
    }

    private static boolean legacyMatches(Object value, String expected) throws JSONException {
        return legacyHash(value, false).equals(expected) || legacyHash(value, true).equals(expected);
    }
    private static String legacyHash(Object value, boolean androidEscaping) throws JSONException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            legacyValue(digest, value, androidEscaping);
            StringBuilder result = new StringBuilder();
            for (byte b : digest.digest()) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static void legacyValue(MessageDigest digest, Object value, boolean androidEscaping) throws JSONException {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            ArrayList<String> keys = new ArrayList<>(); object.keys().forEachRemaining(keys::add); Collections.sort(keys);
            update(digest, "{");
            for (String key : keys) {
                update(digest, legacyQuote(key, androidEscaping) + ":");
                legacyValue(digest, object.get(key), androidEscaping); update(digest, ",");
            }
            update(digest, "}");
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value; update(digest, "[");
            for (int i = 0; i < array.length(); i++) { legacyValue(digest, array.get(i), androidEscaping); update(digest, ","); }
            update(digest, "]");
        } else update(digest, value instanceof Number ? Double.toString(((Number) value).doubleValue())
                : value == null || value == JSONObject.NULL ? "null"
                : value instanceof Boolean ? value.toString() : legacyQuote(value.toString(), androidEscaping));
    }
    private static String legacyQuote(String value, boolean androidEscaping) {
        // Reproduce both checkpoint 9 codecs independently of the current platform.
        StringBuilder out = new StringBuilder("\"");
        char previous = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c == '/' && (androidEscaping || previous == '<')) out.append("\\/");
            else if (c == '\b') out.append("\\b");
            else if (c == '\f') out.append("\\f");
            else if (c == '\n') out.append("\\n");
            else if (c == '\r') out.append("\\r");
            else if (c == '\t') out.append("\\t");
            else if (c < 32 || !androidEscaping && (c >= 0x80 && c < 0xa0 || c >= 0x2000 && c < 0x2100))
                out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
            else out.append(c);
            previous = c;
        }
        return out.append('"').toString();
    }

    /** Checkpoint 9 could save a failed import as normal RPE, adding default line keys.
     * Compare regenerated tracks through the same model before restoring its source.
     * Actual changes to motion, RGB or lifecycle remain external edits and are preserved.
     */
    private static boolean matchesSavedLegacyTracks(JSONObject root, JSONArray fields, JSONArray generated) {
        if (generated.length() == 0) return false;
        try {
            String texture = generated.getJSONObject(0).getString("Texture").replace('\\', '/');
            int slash = texture.lastIndexOf('/');
            if (slash < 0) return false;
            String folder = texture.substring(0, slash);
            com.xpe.mobile.model.ChartDocument source = new com.xpe.mobile.model.ChartDocument();
            JSONArray bpms = root.getJSONArray("BPMList");
            for (int i = 0; i < bpms.length(); i++) source.bpmChanges.add(com.xpe.mobile.model.BpmChange.fromJson(bpms.getJSONObject(i)));
            source.noiseFields.addAll(NoiseField.fromJson(fields));
            NoiseFieldRpeExport expected = NoiseFieldRpeExport.legacy(source, folder);
            if (expected.lines.size() != generated.length()) return false;
            for (int i = 0; i < generated.length(); i++) {
                JSONObject actualLine = com.xpe.mobile.model.JudgeLine.fromJson(generated.getJSONObject(i)).toJson();
                JSONObject expectedLine = com.xpe.mobile.model.JudgeLine.fromJson(expected.lines.get(i)).toJson();
                if (!hash(actualLine).equals(hash(expectedLine))) return false;
            }
            return true;
        } catch (JSONException | IllegalArgumentException ex) { return false; }
    }

    private static void update(MessageDigest digest, String value) { digest.update(value.getBytes(StandardCharsets.UTF_8)); }
}
