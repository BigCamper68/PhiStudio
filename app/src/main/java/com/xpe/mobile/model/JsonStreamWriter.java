package com.xpe.mobile.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.Writer;
import java.util.Iterator;

/** Writes org.json values without first materializing the complete document as a String. */
final class JsonStreamWriter {
    private JsonStreamWriter() {
    }

    static void value(Writer output, Object value) throws IOException, JSONException {
        if (value == null || value == JSONObject.NULL) {
            output.write("null");
        } else if (value instanceof JSONObject) {
            object(output, (JSONObject) value);
        } else if (value instanceof JSONArray) {
            array(output, (JSONArray) value);
        } else if (value instanceof String || value instanceof Character) {
            string(output, String.valueOf(value));
        } else if (value instanceof Number) {
            String number = JSONObject.numberToString((Number) value);
            output.write(number);
        } else if (value instanceof Boolean) {
            output.write(value.toString());
        } else {
            string(output, value.toString());
        }
    }

    static void object(Writer output, JSONObject object)
            throws IOException, JSONException {
        output.write('{');
        boolean first = true;
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!first) output.write(',');
            first = false;
            string(output, key);
            output.write(':');
            value(output, object.get(key));
        }
        output.write('}');
    }

    private static void array(Writer output, JSONArray array)
            throws IOException, JSONException {
        output.write('[');
        for (int index = 0; index < array.length(); index++) {
            if (index > 0) output.write(',');
            value(output, array.get(index));
        }
        output.write(']');
    }

    private static void string(Writer output, String value) throws IOException {
        output.write('"');
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '"': output.write("\\\""); break;
                case '\\': output.write("\\\\"); break;
                case '\b': output.write("\\b"); break;
                case '\f': output.write("\\f"); break;
                case '\n': output.write("\\n"); break;
                case '\r': output.write("\\r"); break;
                case '\t': output.write("\\t"); break;
                default:
                    if (current < 0x20 || current == 0x2028 || current == 0x2029) {
                        String hex = Integer.toHexString(current);
                        output.write("\\u");
                        for (int pad = hex.length(); pad < 4; pad++) output.write('0');
                        output.write(hex);
                    } else {
                        output.write(current);
                    }
            }
        }
        output.write('"');
    }
}
