// JARVIS ARC: multi-layer mechanical reactor with electric arcs and energy pulses.
void main() {
  vec2 p = uvc();
  p += 0.02 * vec2(sin(uTime * 0.31), cos(uTime * 0.23));          // parallax sway
  float r = length(p), a = atan(p.y, p.x);
  float act = 0.6 + 0.4 * step(0.5, uState) + uLevel * 0.8;
  vec3 cyan = stateTint(mix(vec3(0.15, 0.85, 1.0), uAccent, 0.35));
  vec3 col = vec3(0.0);
  // Core
  float pulse = 1.0 + 0.08 * sin(uTime * 3.0) + 0.25 * uLevel;
  col += vec3(1.0) * exp(-r * 38.0 / pulse) * 2.4;
  col += cyan * exp(-r * 9.0 / pulse) * 1.3 * act;
  // Inner rotating triangle (SDF stroke)
  vec2 q = rot(-uTime * 0.6) * p;
  float tri = max(abs(q.x) * 0.866 + q.y * 0.5, -q.y) - 0.13;
  col += mix(cyan, vec3(1.0), 0.5) * exp(-abs(tri) * 140.0) * 1.4;
  // 10 coil segments
  float seg = fract((a + uTime * 0.35) / TAU * 10.0);
  float coil = step(0.18, r) * step(r, 0.30) * step(0.12, seg) * step(seg, 0.88);
  float coilEdge = exp(-abs(r - 0.18) * 160.0) + exp(-abs(r - 0.30) * 160.0);
  col += cyan * (coil * 0.28 + coilEdge * 0.7 * step(0.12, seg) * step(seg, 0.88));
  // Rotating dashed rings (mechanical layers)
  for (int i = 0; i < 4; i++) {
    float fi = float(i);
    float rr = 0.40 + fi * 0.085;
    float dir = mod(fi, 2.0) < 0.5 ? 1.0 : -1.0;
    float dash = step(0.45, fract((a / TAU) * (14.0 + fi * 8.0) + uTime * dir * (0.08 + fi * 0.03)));
    float w = i == 0 ? 220.0 : 380.0;
    col += cyan * exp(-abs(r - rr) * w) * dash * (0.9 - fi * 0.15);
  }
  // Degree ticks
  float tick = step(0.82, fract(a / TAU * 120.0)) * step(0.74, r) * step(r, 0.78);
  col += cyan * tick * 0.6;
  // Electric arcs crawling between coil ring and first mechanical ring
  for (int k = 0; k < 4; k++) {
    float fk = float(k);
    float centre = fract(uTime * (0.05 + fk * 0.013) + fk * 0.27) * TAU - PI;
    float da = abs(mod(a - centre + PI, TAU) - PI);
    float window = smoothstep(0.55, 0.0, da);
    float path = 0.35 + 0.045 * (fbm3(vec2(a * 9.0 + fk * 3.1, uTime * 4.0 + fk)) - 0.5) * 2.0;
    col += mix(cyan, vec3(1.0), 0.6) * window * 0.0035 / (abs(r - path) + 0.002) * act;
  }
  // Energy pulses expanding outward
  for (int k = 0; k < 3; k++) {
    float t = fract(uTime * (0.35 + 0.3 * step(2.5, uState)) + float(k) / 3.0);
    col += cyan * exp(-abs(r - (0.12 + t * 0.9)) * 90.0) * (1.0 - t) * 0.5 * act;
  }
  // Orbiting particles
  for (int k = 0; k < 24; k++) {
    float fk = float(k);
    if (fk > 24.0 * uDensity) break;
    float ang = fk * 2.399 + uTime * (0.2 + mod(fk, 5.0) * 0.06);
    float rad = 0.55 + mod(fk * 0.37, 0.35);
    vec2 pp = vec2(cos(ang), sin(ang)) * rad;
    col += mix(cyan, vec3(1.0), 0.4) * 0.00035 / (dot(p - pp, p - pp) + 0.00012);
  }
  col *= uGlow;
  col += cyan * 0.05 * exp(-r * 1.5);                              // ambient bloom
  gl_FragColor = vec4(tonemap(col), 1.0);
}
