package com.xpe.mobile.packageio;

import org.json.JSONObject;

public final class ChartJsonFormatDetector {
    private ChartJsonFormatDetector() {
    }

    public static ChartJsonFormat detect(JSONObject root) {
        if (root == null) return ChartJsonFormat.UNKNOWN;
        if (root.optJSONObject("META") != null
                && root.optJSONArray("BPMList") != null
                && root.optJSONArray("judgeLineList") != null) {
            return ChartJsonFormat.RPE;
        }
        if (root.has("formatVersion")
                && root.optJSONArray("judgeLineList") != null
                && root.optJSONArray("BPMList") == null) {
            return ChartJsonFormat.OFFICIAL_PHIGROS;
        }
        return ChartJsonFormat.UNKNOWN;
    }
}
