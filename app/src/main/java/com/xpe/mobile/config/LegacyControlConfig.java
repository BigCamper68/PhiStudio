package com.xpe.mobile.config;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Parser for the original InputControls.icp. The native editor does not emulate
 * keyboard input, but keeping this normalized layout model allows a later config
 * to be mapped onto direct editor actions without Winlator.
 */
public final class LegacyControlConfig {
    public static final class Element {
        public String type;
        public String shape;
        public String primaryBinding;
        public float x;
        public float y;
        public float scale;
        public boolean toggle;
        public String text;
    }

    private final List<Element> elements;

    private LegacyControlConfig(List<Element> elements) {
        this.elements = elements;
    }

    public List<Element> elements() {
        return Collections.unmodifiableList(elements);
    }

    public static LegacyControlConfig loadReference(Context context) throws IOException, JSONException {
        try (InputStream input = context.getAssets().open("input_controls_reference.icp")) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            JSONObject root = new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
            JSONArray source = root.optJSONArray("elements");
            List<Element> result = new ArrayList<>();
            if (source != null) {
                for (int index = 0; index < source.length(); index++) {
                    JSONObject item = source.getJSONObject(index);
                    Element element = new Element();
                    element.type = item.optString("type", "BUTTON");
                    element.shape = item.optString("shape", "CIRCLE");
                    JSONArray bindings = item.optJSONArray("bindings");
                    element.primaryBinding = bindings == null ? "NONE" : bindings.optString(0, "NONE");
                    element.x = (float) item.optDouble("x", 0.5);
                    element.y = (float) item.optDouble("y", 0.5);
                    element.scale = (float) item.optDouble("scale", 1.0);
                    element.toggle = item.optBoolean("toggleSwitch", false);
                    element.text = item.optString("text", "");
                    result.add(element);
                }
            }
            return new LegacyControlConfig(result);
        }
    }
}
