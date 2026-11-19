#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform vec2 uRes;      // pixels
uniform float uTime;    // seconds (already speed-scaled)
uniform float uState;   // 0 ready, 1 listening, 2 thinking, 3 speaking, 4 error
uniform float uLevel;   // 0..1 audio level
uniform float uGlow;    // 0.4..1.6 glow intensity
uniform float uDensity; // 0.3..1.0 particle density
uniform float uMorph;   // 0..1 orb -> humanoid
uniform vec3 uAccent;   // owner accent colour
uniform float uAgents[5]; // agent activity 0..1 (Agent Office)

const float PI = 3.14159265;
const float TAU = 6.2831853;

float hash(vec2 p) { p = fract(p * vec2(123.34, 456.21)); p += dot(p, p + 45.32); return fract(p.x * p.y); }
float noise(vec2 p) {
  vec2 i = floor(p), f = fract(p);
  vec2 u = f * f * (3.0 - 2.0 * f);
  return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x), u.y);
}
float fbm(vec2 p) {
  float v = 0.0, a = 0.5;
  for (int i = 0; i < 5; i++) { v += a * noise(p); p = p * 2.03 + vec2(1.7, 9.2); a *= 0.5; }
  return v;
}
mat2 rot(float a) { float c = cos(a), s = sin(a); return mat2(c, -s, s, c); }
float sdCapsule(vec2 p, vec2 a, vec2 b, float r) {
  vec2 pa = p - a, ba = b - a;
  float h = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
  return length(pa - ba * h) - r;
}
float sdBox(vec2 p, vec2 b) { vec2 d = abs(p) - b; return length(max(d, 0.0)) + min(max(d.x, d.y), 0.0); }
float smin(float a, float b, float k) { float h = clamp(0.5 + 0.5 * (b - a) / k, 0.0, 1.0); return mix(b, a, h) - k * h * (1.0 - h); }
/* Centered coords: y up, shorter side spans [-1,1]; the visual sits slightly above centre. */
vec2 uvc() {
  vec2 uv = (gl_FragCoord.xy - 0.5 * uRes) / min(uRes.x, uRes.y);
  return uv * 2.0;
}
vec3 stateTint(vec3 base) {
  if (uState > 3.5) return mix(base, vec3(1.0, 0.25, 0.3), 0.6);
  if (uState > 2.5) return mix(base, vec3(1.0, 0.7, 0.2), 0.45);
  if (uState > 1.5) return mix(base, vec3(0.7, 0.4, 1.0), 0.45);
  return base;
}
vec3 tonemap(vec3 c) { c = 1.0 - exp(-c * 1.15); return pow(c, vec3(0.92)); }
/* Trapezoid: half-width r1 at bottom (y=-he), r2 at top (y=+he). (Inigo Quilez, MIT) */
float sdTrapezoid(vec2 p, float r1, float r2, float he) {
  vec2 k1 = vec2(r2, he), k2 = vec2(r2 - r1, 2.0 * he);
  p.x = abs(p.x);
  vec2 ca = vec2(p.x - min(p.x, (p.y < 0.0) ? r1 : r2), abs(p.y) - he);
  vec2 cb = p - k1 + k2 * clamp(dot(k1 - p, k2) / dot(k2, k2), 0.0, 1.0);
  float s = (cb.x < 0.0 && ca.y < 0.0) ? -1.0 : 1.0;
  return s * sqrt(min(dot(ca, ca), dot(cb, cb)));
}
