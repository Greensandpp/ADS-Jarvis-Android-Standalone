// MIRA: energy orb that transforms into a blue-violet holographic humanoid and back.
float human(vec2 p, float t) {
  vec2 hp = p - vec2(0.03 * sin(t * 0.7), 0.0);                       // head sway
  float head = length((hp - vec2(0.0, 0.52)) * vec2(1.0, 0.85)) - 0.115;
  float neck = sdCapsule(p, vec2(0.0, 0.40), vec2(0.0, 0.32), 0.04);
  float torso = sdTrapezoid(p - vec2(0.0, 0.17), 0.075, 0.13, 0.12) - 0.04;
  float waist = sdTrapezoid(p - vec2(0.0, -0.06), 0.13, 0.08, 0.12) - 0.035;
  torso = smin(torso, waist, 0.05);
  float hair = length((hp - vec2(0.0, 0.47)) * vec2(1.25, 0.7)) - 0.12;
  float sh = sdCapsule(p, vec2(-0.2, 0.28), vec2(0.2, 0.28), 0.055);
  float armL = sdCapsule(p, vec2(-0.22, 0.27), vec2(-0.30 - 0.02 * sin(t), -0.12), 0.042);
  float armR = sdCapsule(p, vec2(0.22, 0.27), vec2(0.30 + 0.02 * sin(t + 1.0), -0.12), 0.042);
  float hips = sdTrapezoid(p - vec2(0.0, -0.22), 0.17, 0.12, 0.07) - 0.04;
  float legs = min(sdCapsule(p, vec2(-0.08, -0.25), vec2(-0.1, -0.78), 0.06), sdCapsule(p, vec2(0.08, -0.25), vec2(0.1, -0.78), 0.06));
  float d = smin(min(head, hair), neck, 0.04);
  d = smin(d, torso, 0.06); d = smin(d, sh, 0.06);
  d = smin(d, min(armL, armR), 0.04); d = smin(d, hips, 0.05); d = smin(d, legs, 0.05);
  return d;
}
void main() {
  vec2 p = uvc() * 1.05;
  float t = uTime;
  vec2 dp = p + 0.015 * vec2(fbm(p * 4.0 + t), fbm(p * 4.0 - t)) ;  // plasma distortion
  float orb = length(dp - vec2(0.0, 0.1)) - (0.24 + 0.02 * sin(t * 2.0) + 0.05 * uLevel);
  float m = smoothstep(0.0, 1.0, uMorph);
  float d = mix(orb, human(dp, t), m);
  vec3 blue = vec3(0.25, 0.5, 1.0), vio = vec3(0.65, 0.3, 1.0), pink = vec3(1.0, 0.45, 0.95);
  vec3 c0 = stateTint(mix(blue, vio, 0.5 + 0.5 * sin(t * 0.5 + p.y * 3.0)));
  vec3 col = vec3(0.0);
  // luminous contour
  col += c0 * exp(-abs(d) * 90.0) * 1.6;
  col += mix(c0, vec3(1.0), 0.6) * exp(-abs(d) * 400.0) * 1.2;
  // inner holographic body: flowing plasma lines + scanlines
  float inside = smoothstep(0.01, -0.02, d);
  float flow = sin(p.y * 46.0 - t * 4.0 + fbm(p * 5.0 + t) * 6.0);
  col += mix(blue, pink, fbm(p * 3.0 - t * 0.3)) * inside * (0.12 + 0.18 * smoothstep(0.6, 1.0, flow));
  col += vio * inside * 0.12 * step(0.5, fract(gl_FragCoord.y * 0.25));
  // energy field + aura
  col += c0 * exp(-max(d, 0.0) * 9.0) * 0.25 * (0.8 + uLevel);
  // particles shed during transformation
  for (int k = 0; k < 28; k++) {
    float fk = float(k);
    if (fk > 28.0 * uDensity) break;
    float tt = fract(t * 0.12 + hash(vec2(fk, 4.0)));
    vec2 pp = vec2(hash(vec2(fk, 1.0)) - 0.5, -0.8 + tt * 1.7) * vec2(0.9, 1.0);
    col += mix(vio, pink, hash(vec2(fk, 7.0))) * (1.0 - tt) * 0.00025 / (dot(p - pp, p - pp) + 0.0001) * (0.4 + abs(sin(PI * m)));
  }
  col *= uGlow;
  gl_FragColor = vec4(tonemap(col), 1.0);
}
