#version 100
// Generated from knowladge/phigros/shaders/Unlit_ActiveBlock.md.

uniform 	vec4 _ProjectionParams;
uniform 	vec4 _ScreenParams;
uniform 	vec4 hlslcc_mtx4x4unity_ObjectToWorld[4];
uniform 	vec4 hlslcc_mtx4x4unity_MatrixVP[4];
uniform 	vec4 _SparkMap_ST;
uniform 	mediump vec4 _DisplaceMap_ST;
uniform 	vec4 _TouchDisplaceMap_ST;
uniform 	vec4 _NoiseMap_ST;
attribute highp vec4 in_POSITION0;
attribute highp vec4 in_COLOR0;
attribute highp vec2 in_TEXCOORD0;
varying highp vec2 vs_TEXCOORD0;
varying highp vec2 vs_TEXCOORD1;
varying highp vec2 vs_TEXCOORD2;
varying highp vec2 vs_TEXCOORD4;
varying highp vec4 vs_TEXCOORD3;
varying highp vec2 vs_TEXCOORD5;
varying highp float vs_TEXCOORD6;
varying highp vec4 vs_COLOR0;
vec4 u_xlat0;
vec4 u_xlat1;
void main()
{
    vs_TEXCOORD1.xy = in_TEXCOORD0.xy * _DisplaceMap_ST.xy + _DisplaceMap_ST.zw;
    vs_TEXCOORD0.xy = in_TEXCOORD0.xy;
    vs_TEXCOORD2.xy = in_TEXCOORD0.xy * _SparkMap_ST.xy + _SparkMap_ST.zw;
    vs_TEXCOORD4.xy = in_TEXCOORD0.xy * _TouchDisplaceMap_ST.xy + _TouchDisplaceMap_ST.zw;
    u_xlat0 = in_POSITION0.yyyy * hlslcc_mtx4x4unity_ObjectToWorld[1];
    u_xlat0 = hlslcc_mtx4x4unity_ObjectToWorld[0] * in_POSITION0.xxxx + u_xlat0;
    u_xlat0 = hlslcc_mtx4x4unity_ObjectToWorld[2] * in_POSITION0.zzzz + u_xlat0;
    u_xlat0 = u_xlat0 + hlslcc_mtx4x4unity_ObjectToWorld[3];
    u_xlat1 = u_xlat0.yyyy * hlslcc_mtx4x4unity_MatrixVP[1];
    u_xlat1 = hlslcc_mtx4x4unity_MatrixVP[0] * u_xlat0.xxxx + u_xlat1;
    u_xlat1 = hlslcc_mtx4x4unity_MatrixVP[2] * u_xlat0.zzzz + u_xlat1;
    u_xlat0 = hlslcc_mtx4x4unity_MatrixVP[3] * u_xlat0.wwww + u_xlat1;
    u_xlat1.x = u_xlat0.y * _ProjectionParams.x;
    u_xlat1.w = u_xlat1.x * 0.5;
    u_xlat1.xz = u_xlat0.xw * vec2(0.5, 0.5);
    vs_TEXCOORD3.xy = u_xlat1.zz + u_xlat1.xw;
    vs_TEXCOORD3.zw = u_xlat0.zw;
    gl_Position = u_xlat0;
    u_xlat0.x = _ScreenParams.y * 0.888888896;
    vs_TEXCOORD6 = u_xlat0.x / _ScreenParams.x;
    vs_TEXCOORD5.xy = in_TEXCOORD0.xy * _NoiseMap_ST.xy + _NoiseMap_ST.zw;
    vs_COLOR0 = in_COLOR0;
    return;
}
