package com.xpe.mobile.preview;

/** Optional material variants; the extracted native shader assets remain byte-for-byte intact. */
final class NoiseFieldColorShaders {
    private NoiseFieldColorShaders() { }
    static String material(String nativeFragment, boolean active) {
        String helper = "\nuniform mediump sampler2D phiPalette;\nuniform highp vec2 phiMaskSize;\nuniform highp float phiHasInactive;\n"
                + "highp vec3 phiRgb; highp vec3 phiSparkRgb;\n"
                + "highp vec3 phiSample(highp vec2 uv, highp float layer){"
                + "uv=clamp(uv,0.5/phiMaskSize,vec2(1.0)-0.5/phiMaskSize);"
                + "return texture2D(phiPalette,vec2(uv.x,uv.y*0.5+layer*0.5)).rgb;}\n"
                + "highp float phiSaturation(highp vec3 rgb){"
                + "highp float v=max(max(rgb.r,rgb.g),rgb.b);"
                + "return (v-min(min(rgb.r,rgb.g),rgb.b))/max(v,0.00001)/(1.0-84.0/255.0);}\n"
                + "highp vec3 phiTint(highp vec3 material,highp vec3 rgb){"
                + "if(all(lessThan(abs(rgb-vec3(1.0,84.0/255.0,84.0/255.0)),vec3(0.001))))return material;"
                + "highp float v=max(max(rgb.r,rgb.g),rgb.b), lo=min(min(rgb.r,rgb.g),rgb.b), chroma=v-lo;"
                + "if(chroma<0.00001)return vec3(material.r*v);"
                + "highp vec3 hue=(rgb-vec3(lo))/chroma;"
                + "highp float s=clamp((1.0-material.g/max(material.r,0.00001))*phiSaturation(rgb),0.0,1.0);"
                + "return material.r*v*mix(vec3(1.0),hue,s);}\n";
        // Both displacement maps use the same image in the native material.
        String body = nativeFragment.replace("uniform mediump sampler2D _TouchDisplaceMap;", "")
                .replace("texture2D(_TouchDisplaceMap,", "texture2D(_DisplaceMap,");
        for (String uniform : new String[]{"_EdgeColor", "_FillColor", "_GlowColor", "_NoiseTint", "_TouchGlowColor"})
            body = body.replace(uniform + ".xyz", "phiTint(" + uniform + ".xyz,phiRgb)");
        body = body.replace("_SparkTint.xyz", "phiTint(_SparkTint.xyz,phiSparkRgb)");
        body = body.replace("u_xlat1.y * _SparkHueShiftAmount + u_xlat49",
                "u_xlat1.y * _SparkHueShiftAmount * phiSaturation(phiSparkRgb) + u_xlat49");
        String setup = active
                ? "phiRgb=phiSample(vs_TEXCOORD0.xy,0.0);"
                    + "if(phiHasInactive>0.5&&texture2D(_ComposeRT,vs_TEXCOORD0.xy).r+texture2D(_EffectRT,vs_TEXCOORD0.xy).g<=0.0001)"
                    + "phiRgb=phiSample(vs_TEXCOORD0.xy,1.0);phiSparkRgb=phiRgb;"
                : "phiRgb=phiSample(vs_TEXCOORD0.xy,1.0);phiSparkRgb=phiRgb;";
        return body.replace("void main()\n{", helper + "\nvoid main()\n{\n" + setup + "\n");
    }
    static String compose(String nativeFragment) {
        int output = nativeFragment.indexOf("    u_xlat16_5 = texture2D(_NormalBlockRT");
        if (output < 0) throw new IllegalArgumentException("Native Compose UV output missing");
        String fragment = nativeFragment.substring(0, output)
                + "    highp vec2 sourceUV=clamp(u_xlat1.xy,0.5/phiMaskSize,vec2(1.0)-0.5/phiMaskSize);\n"
                + "    gl_FragColor=texture2D(phiPalette,vec2(sourceUV.x,sourceUV.y*0.5));\n}\n";
        return fragment.replace("void main()", "uniform mediump sampler2D phiPalette;\nuniform highp vec2 phiMaskSize;\nvoid main()");
    }
}
