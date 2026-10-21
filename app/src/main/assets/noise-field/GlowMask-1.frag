#version 100
// Generated from knowladge/phigros/shaders/Unlit_GlowMask.md.

precision highp float;
precision highp int;
uniform mediump sampler2D _MainTex;
varying highp vec2 vs_TEXCOORD0;
mediump vec2 SV_Target0;
mediump float u_xlat16_0;
void main()
{
    u_xlat16_0 = texture2D(_MainTex, vs_TEXCOORD0.xy).y;
    SV_Target0.y = u_xlat16_0;
    SV_Target0.x = 0.0;
    gl_FragColor = vec4(SV_Target0, 0.0, 1.0);
    return;
}
