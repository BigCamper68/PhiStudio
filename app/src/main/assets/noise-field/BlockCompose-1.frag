#version 100
// Generated from knowladge/phigros/shaders/Unlit_BlockCompose.md.

precision highp float;
precision highp int;
uniform mediump sampler2D _DisabledNormalBlockRT;
uniform mediump sampler2D _DisabledSubtractBlockRT;
varying highp vec2 vs_TEXCOORD0;
mediump vec2 SV_Target0;
mediump float u_xlat16_0;
mediump float u_xlat16_1;
mediump vec2 u_xlat16_2;
void main()
{
    u_xlat16_0 = texture2D(_DisabledNormalBlockRT, vs_TEXCOORD0.xy).x;
    u_xlat16_2.xy = texture2D(_DisabledSubtractBlockRT, vs_TEXCOORD0.xy).xy;
    u_xlat16_1 = u_xlat16_2.x * u_xlat16_2.y + (-u_xlat16_0);
    SV_Target0.y = u_xlat16_2.y;
    SV_Target0.x = abs(u_xlat16_1);
    gl_FragColor = vec4(SV_Target0, 0.0, 1.0);
    return;
}
