#version 100
// Generated from knowladge/phigros/shaders/Unlit_BlockSprite.md.

precision highp float;
precision highp int;
uniform mediump sampler2D _MainTex;
varying highp vec2 vs_TEXCOORD0;
varying highp vec4 vs_COLOR0;
mediump vec4 SV_Target0;
vec4 u_xlat0;
mediump float u_xlat16_0;
void main()
{
    u_xlat16_0 = texture2D(_MainTex, vs_TEXCOORD0.xy).x;
    u_xlat0.xyz = vec3(u_xlat16_0) * vs_COLOR0.xyz;
    u_xlat0.w = vs_COLOR0.w;
    SV_Target0 = u_xlat0;
    gl_FragColor = SV_Target0;
    return;
}
