// AIRA neural plasma: electric core, branching filaments, multicolour activity, particle dispersion.
void main() {
  vec2 p = uvc();
  float r = length(p), a = atan(p.y, p.x);
  vec3 blue = vec3(0.2, 0.55, 1.0), cyan = vec3(0.2, 1.0, 1.0), mag = vec3(1.0, 0.3, 0.9), vio = vec3(0.6, 0.35, 1.0);
  vec3 col = vec3(0.0);
  float act = 0.7 + 0.5 * step(0.5, uState) + uLevel;
  // Plasma core
  float core = fbm(p * 6.0 + vec2(uTime * 0.8, -uTime * 0.6));
  col += mix(blue, cyan, core) * exp(-r * 5.0) * (1.8 + core) * act;
  col += vec3(1.0) * exp(-r * 28.0) * 2.2;
  // Branching filaments
  for (int i = 0; i < 9; i++) {
    float fi = float(i);
    float base = fi / 9.0 * TAU + 0.6 * sin(uTime * (0.3 + fi * 0.07) + fi * 2.0);
    float wob = (fbm3(vec2(r * 5.0 - uTime * 2.2, fi * 7.3)) - 0.5) * 1.1 * r;
    float da = abs(mod(a - base - wob + PI, TAU) - PI) * r;
    float len = 0.62 + 0.25 * sin(uTime * 0.9 + fi * 1.7);
    float fade = smoothstep(len, 0.05, r);
    vec3 c = i < 3 ? cyan : (i < 6 ? blue : (i < 8 ? mag : vio));
    col += c * fade * 0.006 / (da + 0.003) * act;
    col += c * fade * exp(-da * 18.0) * 0.12 * act;
    // branch
    float bwob = (noise(vec2(r * 9.0 + uTime * 3.0, fi * 3.1 + 11.0)) - 0.5) * 1.6 * r;
    float db = abs(mod(a - base - wob - 0.35 * smoothstep(0.25, 0.6, r) + bwob * 0.4 + PI, TAU) - PI) * r;
    col += c * smoothstep(len * 0.9, 0.3, r) * step(0.25, r) * 0.003 / (db + 0.0025);
  }
  // Neural connection web (cells)
  vec2 g = p * 7.0 + vec2(uTime * 0.2, 0.0);
  float web = abs(fbm3(g) - 0.5);
  col += mix(vio, cyan, 0.5) * smoothstep(0.035, 0.0, web) * smoothstep(0.85, 0.2, r) * 0.6;
  // Particle dispersion
  for (int k = 0; k < 36; k++) {
    float fk = float(k);
    if (fk > 36.0 * uDensity) break;
    float t = fract(uTime * (0.08 + mod(fk, 7.0) * 0.015) + hash(vec2(fk, 2.0)));
    float ang = hash(vec2(fk, 9.0)) * TAU + t * 1.5;
    vec2 pp = vec2(cos(ang), sin(ang)) * (0.15 + t * 0.85);
    vec3 c = mod(fk, 3.0) < 1.0 ? cyan : (mod(fk, 3.0) < 2.0 ? mag : blue);
    col += c * (1.0 - t) * 0.0003 / (dot(p - pp, p - pp) + 0.0001);
  }
  // Glass sphere rim
  col += blue * exp(-abs(r - 0.78) * 120.0) * 0.35;
  col = mix(col, col * stateTint(vec3(1.0)), 0.3) * uGlow;
  gl_FragColor = vec4(tonemap(col), 1.0);
}
