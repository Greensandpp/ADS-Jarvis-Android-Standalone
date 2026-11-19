// Agent Office: JARVIS core with five internal agents; brightness = real agent activity.
void main() {
  vec2 p = uvc();
  float t = uTime;
  vec3 cyan = vec3(0.1, 0.9, 1.0), green = vec3(0.4, 1.0, 0.6);
  vec3 col = vec3(0.0);
  float r = length(p);
  col += vec3(1.0) * exp(-r * 30.0) * 1.8 + cyan * exp(-r * 8.0) * 0.8;
  for (int i = 0; i < 5; i++) {
    float fi = float(i);
    float act = uAgents[i];
    float ang = PI * 0.5 + fi * TAU / 5.0;
    vec2 n = vec2(cos(ang), sin(ang)) * 0.62;
    // link
    vec2 pa = p, ba = n;
    float h = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
    float dl = length(pa - ba * h);
    col += cyan * exp(-dl * 220.0) * (0.15 + 0.6 * act);
    // data pulses travel along active links
    float tp = fract(t * 0.8 + fi * 0.2);
    col += mix(cyan, vec3(1.0), 0.5) * act * 0.0004 / (dot(p - n * tp, p - n * tp) + 0.0002);
    // node ring
    float dn = length(p - n);
    vec3 nc = mix(vec3(0.35, 0.42, 0.5), green, act);
    col += nc * exp(-abs(dn - 0.1) * 160.0) * (0.6 + 0.8 * act);
    col += nc * exp(-dn * 18.0) * (0.15 + 0.9 * act * (0.7 + 0.3 * sin(t * 6.0)));
  }
  gl_FragColor = vec4(tonemap(col * uGlow), 1.0);
}
