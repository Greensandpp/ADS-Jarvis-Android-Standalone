// APEX: cyan/black armoured humanoid with energy fields, electric trails, glowing geometry and gold accents.
float armour(vec2 p, float t, out float plates) {
  vec2 hp = p - vec2(0.0, 0.565);
  float helm = smin(length(hp * vec2(1.0, 0.82)) - 0.088, sdTrapezoid(p - vec2(0.0, 0.47), 0.035, 0.075, 0.05) - 0.01, 0.03);
  float neck = sdCapsule(p, vec2(0.0, 0.43), vec2(0.0, 0.36), 0.04);
  float chest = sdTrapezoid(p - vec2(0.0, 0.215), 0.125, 0.235, 0.12) - 0.015;
  float abd = sdTrapezoid(p - vec2(0.0, 0.0), 0.105, 0.125, 0.095) - 0.012;
  float pelvis = sdTrapezoid(p - vec2(0.0, -0.17), 0.07, 0.13, 0.07) - 0.01;
  vec2 pl = rot(0.35) * (p - vec2(-0.27, 0.30)), pr = rot(-0.35) * (p - vec2(0.27, 0.30));
  float paul = min(length(pl * vec2(1.0, 1.45)) - 0.085, length(pr * vec2(1.0, 1.45)) - 0.085);
  float uarm = min(sdCapsule(p, vec2(-0.29, 0.25), vec2(-0.33, 0.03), 0.047), sdCapsule(p, vec2(0.29, 0.25), vec2(0.33, 0.03), 0.047));
  float farm = min(sdCapsule(p, vec2(-0.33, 0.02), vec2(-0.355, -0.23), 0.052), sdCapsule(p, vec2(0.33, 0.02), vec2(0.355, -0.23), 0.052));
  float thigh = min(sdCapsule(p, vec2(-0.085, -0.24), vec2(-0.1, -0.52), 0.066), sdCapsule(p, vec2(0.085, -0.24), vec2(0.1, -0.52), 0.066));
  float shin = min(sdCapsule(p, vec2(-0.1, -0.54), vec2(-0.11, -0.84), 0.056), sdCapsule(p, vec2(0.1, -0.54), vec2(0.11, -0.84), 0.056));
  float d = min(helm, neck);
  d = min(d, chest); d = min(d, abd); d = min(d, pelvis); d = min(d, paul);
  d = min(d, uarm); d = min(d, farm); d = min(d, thigh); d = min(d, shin);
  // plate seams: boundaries between the separate armour parts
  float a1 = min(helm, chest), a2 = min(abd, pelvis), a3 = min(paul, uarm), a4 = min(farm, min(thigh, shin));
  plates = min(min(abs(helm - chest), abs(chest - abd)), min(abs(abd - pelvis), min(abs(paul - chest), abs(thigh - shin))));
  plates = min(plates, abs(uarm - farm));
  plates += 0.0 * (a1 + a2 + a3 + a4);
  return d;
}
void main() {
  vec2 p = uvc() * 1.05;
  float t = uTime;
  vec3 cyan = stateTint(mix(vec3(0.1, 0.95, 1.0), uAccent, 0.25)), gold = vec3(1.0, 0.75, 0.25);
  vec3 col = vec3(0.0);
  // Background hex energy field with sweep
  vec2 h = p * 6.0; h.y *= 1.1547; h.x += mod(floor(h.y), 2.0) * 0.5;
  vec2 f = fract(h) - 0.5;
  float hexEdge = smoothstep(0.46, 0.5, max(abs(f.x), abs(f.y) * 0.866 + abs(f.x) * 0.5));
  float sweep = exp(-abs(fract(t * 0.15) * 3.0 - 1.5 - p.y) * 3.0);
  col += cyan * hexEdge * (0.05 + 0.25 * sweep) * smoothstep(1.6, 0.2, length(p));
  // Armoured figure: plates with panel lines, rim light, glowing edges
  float rise = smoothstep(0.0, 1.0, uMorph);
  vec2 q = p - vec2(0.0, (1.0 - rise) * -0.15);
  float plates;
  float d = armour(q, t, plates);
  float inside = smoothstep(0.005, -0.005, d);
  // Lit armour body: vertical light gradient, plate seams, inner contour lines, chest V lines.
  float light = 0.35 + 0.65 * smoothstep(-0.9, 0.6, q.y) * (0.6 + 0.4 * smoothstep(0.25, 0.0, abs(q.x)));
  col += mix(vec3(0.01, 0.04, 0.06), cyan * 0.18, light) * inside;
  col += cyan * inside * exp(-plates * 260.0) * 0.9;                                   // plate seams
  col += cyan * inside * exp(-abs(d + 0.022) * 420.0) * 0.55;                          // inner contour
  float vline = abs(abs(q.x) - (0.02 + (0.3 - q.y) * 0.35));
  col += cyan * inside * step(0.1, q.y) * step(q.y, 0.33) * exp(-vline * 300.0) * 0.6; // chest V
  col += cyan * inside * smoothstep(-0.05, 0.0, d) * 0.5;                              // bevel
  col += cyan * exp(-abs(d) * 160.0) * 1.6;                                             // edge glow
  col += cyan * exp(-max(d, 0.0) * 14.0) * 0.18 * (0.7 + uLevel);                      // energy aura
  // Gold accents: visor, chest reactor
  float visor = sdBox(q - vec2(0.0, 0.54), vec2(0.07, 0.012));
  col += gold * exp(-max(visor, 0.0) * 140.0) * 1.5 * (0.8 + 0.4 * sin(t * 3.0));
  float reactor = length(q - vec2(0.0, 0.2)) - 0.045;
  col += mix(gold, vec3(1.0), 0.4) * exp(-max(reactor, 0.0) * 50.0) * (1.4 + uLevel);
  // Electric trails orbiting the figure
  float r = length(p - vec2(0.0, 0.05)), a = atan(p.y - 0.05, p.x);
  for (int k = 0; k < 3; k++) {
    float fk = float(k);
    float centre = fract(t * (0.12 + fk * 0.05) + fk * 0.33) * TAU - PI;
    float da = abs(mod(a - centre + PI, TAU) - PI);
    float path = 0.82 + 0.05 * fk + 0.04 * (fbm3(vec2(a * 7.0 + fk, t * 5.0)) - 0.5) * 2.0;
    col += mix(cyan, vec3(1.0), 0.5) * smoothstep(1.2, 0.0, da) * 0.003 / (abs(r - path) + 0.002);
  }
  // Rising geometric particles
  for (int k = 0; k < 24; k++) {
    float fk = float(k);
    if (fk > 24.0 * uDensity) break;
    float tt = fract(t * 0.1 + hash(vec2(fk, 3.0)));
    vec2 pp = vec2((hash(vec2(fk, 5.0)) - 0.5) * 1.6, -0.9 + tt * 1.9);
    vec2 dd = abs(p - pp);
    col += (mod(fk, 4.0) < 1.0 ? gold : cyan) * (1.0 - tt) * smoothstep(0.012, 0.0, max(dd.x, dd.y)) * 0.8;
  }
  col *= uGlow;
  gl_FragColor = vec4(tonemap(col), 1.0);
}
