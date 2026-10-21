#version 100
// Generated from knowladge/phigros/shaders/Unlit_ReadyBlock.md.

precision highp float;
precision highp int;
uniform 	vec4 _Time;
uniform 	float _ShineSpeed;
uniform 	float _ShineBrightness;
uniform 	vec4 _ShineColor;
uniform mediump sampler2D _DisabledNormalBlockRT;
uniform mediump sampler2D _DisabledSubtractBlockRT;
uniform mediump sampler2D _ComposeRT;
varying highp vec2 vs_TEXCOORD0;
mediump vec4 SV_Target0;
vec3 u_xlat0;
mediump float u_xlat16_0;
bool u_xlatb0;
mediump float u_xlat16_1;
vec3 u_xlat2;
mediump float u_xlat16_2;
mediump float u_xlat16_3;
void main()
{
    u_xlat16_0 = texture2D(_DisabledNormalBlockRT, vs_TEXCOORD0.xy).x;
    u_xlat16_2 = texture2D(_DisabledSubtractBlockRT, vs_TEXCOORD0.xy).y;
    u_xlat16_1 = (-u_xlat16_0) + u_xlat16_2;
    u_xlat16_0 = texture2D(_ComposeRT, vs_TEXCOORD0.xy).x;
    u_xlat16_3 = abs(u_xlat16_1) * u_xlat16_0 + -9.99999975e-05;
    u_xlat16_1 = u_xlat16_0 * abs(u_xlat16_1);
    u_xlatb0 = u_xlat16_3<0.0;
    if(u_xlatb0){discard;}
    u_xlat0.x = _Time.y * _ShineSpeed;
    u_xlat0.x = sin(u_xlat0.x);
    u_xlat0.x = u_xlat0.x * 0.5 + 1.0;
    u_xlat2.xyz = vec3(vec3(_ShineBrightness, _ShineBrightness, _ShineBrightness)) * _ShineColor.xyz;
    u_xlat0.xyz = u_xlat0.xxx * u_xlat2.xyz;
    u_xlat0.xyz = vec3(u_xlat16_1) * u_xlat0.xyz;
    SV_Target0.w = u_xlat16_1;
    SV_Target0.xyz = u_xlat0.xyz;
    gl_FragColor = SV_Target0;
    return;
}
