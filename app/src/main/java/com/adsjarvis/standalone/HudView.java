package com.adsjarvis.standalone;

import android.content.Context;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;

/**
 * Animated, hardware-accelerated JARVIS HUD. One view, five visual styles.
 * Purely visual: no network, no AI, no microphone access. Animation stops
 * automatically while the view is not visible.
 */
public final class HudView extends View {
    public enum Style { ARC, AIRA, OFFICE, APEX, MIRA }
    public enum State { IDLE, LISTENING, THINKING, SPEAKING, ERROR }

    private static final String[] AGENTS = {"Chief", "Developer", "QA Tester", "Security", "Release"};

    private Style style = Style.ARC;
    private State state = State.IDLE;
    private float level;      // 0..1 mic / speech level
    private float energy = .6f;
    private float brightness = 1f;
    private boolean reduceMotion;
    private final long start = System.nanoTime();
    private long frames;

    private final Paint add = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();
    private final float[] nodes = fibSphere(140);

    public HudView(Context c) {
        super(c);
        setLayerType(LAYER_TYPE_HARDWARE, null);
        if (Build.VERSION.SDK_INT >= 29) { add.setBlendMode(BlendMode.PLUS); line.setBlendMode(BlendMode.PLUS); }
        else {
            add.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
            line.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        }
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        text.setColor(0xddffffff);
        text.setTextAlign(Paint.Align.CENTER);
        setContentDescription("JARVIS reactor. Tap to talk, long-press to customize.");
    }

    public void setStyle(Style s) { style = s; invalidate(); }
    public Style getStyle() { return style; }
    public void setState(State s) { state = s; invalidate(); }
    public State getState() { return state; }
    public void setLevel(float v) { level = Math.max(0, Math.min(1, v)); }
    public void setBrightness(float b) { brightness = Math.max(.5f, Math.min(1.6f, b)); }
    public void setReduceMotion(boolean r) { reduceMotion = r; }
    /** Frames drawn so far (diagnostics / tests). */
    public long getFrames() { return frames; }

    private static float[] fibSphere(int n) {
        float[] p = new float[n * 3];
        double g = Math.PI * (3 - Math.sqrt(5));
        for (int i = 0; i < n; i++) {
            double y = 1 - (i / (double) (n - 1)) * 2, r = Math.sqrt(1 - y * y), th = g * i;
            p[i * 3] = (float) (Math.cos(th) * r); p[i * 3 + 1] = (float) y; p[i * 3 + 2] = (float) (Math.sin(th) * r);
        }
        return p;
    }

    private int[] palette() {
        switch (style) {
            case AIRA: return new int[]{0xff34f5ff, 0xffff3bd4, 0xff8a7bff};
            case OFFICE: return new int[]{0xff00e5ff, 0xff6bffa0, 0xffffc14d};
            case APEX: return new int[]{0xff23eeff, 0xffffc84a, 0xffffffff};
            case MIRA: return new int[]{0xffb45cff, 0xffff4fd8, 0xff6fd8ff};
            default:
                if (state == State.SPEAKING) return new int[]{0xffffb300, 0xffffe082, 0xff00e5ff};
                if (state == State.THINKING) return new int[]{0xffba68c8, 0xffffd54f, 0xff00e5ff};
                if (state == State.ERROR) return new int[]{0xffff5a6a, 0xffffb3b3, 0xff00e5ff};
                return new int[]{0xff00e5ff, 0xffbff8ff, 0xff2979ff};
        }
    }

    private static int alpha(int color, float a) {
        int al = (int) (Math.max(0, Math.min(1, a)) * 255);
        return (color & 0x00ffffff) | (al << 24);
    }

    private void glow(Canvas c, float x, float y, float r, int color, float a) {
        if (r <= 0) return;
        float k = a * brightness * energy;
        add.setShader(new RadialGradient(x, y, r, new int[]{alpha(color, k), alpha(color, k * .3f), alpha(color, 0)},
            new float[]{0, .35f, 1}, Shader.TileMode.CLAMP));
        c.drawCircle(x, y, r, add);
        add.setShader(null);
    }

    private void stroke(int color, float a, float w) {
        line.setColor(alpha(color, a * brightness * energy));
        line.setStrokeWidth(w);
    }

    private void dot(Canvas c, float x, float y, float r, int color, float a) {
        add.setColor(alpha(color, a * brightness * energy));
        c.drawCircle(x, y, r, add);
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        frames++;
        c.drawColor(Color.BLACK);
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h * .45f, R = Math.min(w, h) * .31f;
        float t = (System.nanoTime() - start) / 1e9f * (reduceMotion ? .25f : 1f);
        float targetEnergy = state == State.IDLE ? .75f : (state == State.ERROR ? .6f : 1f);
        energy += (targetEnergy - energy) * .08f;
        float d = getResources().getDisplayMetrics().density;
        int[] p = palette();

        glow(c, cx, cy, R * 2.0f, p[0], .22f);
        switch (style) {
            case AIRA: drawAira(c, cx, cy, R, t, p, d); break;
            case OFFICE: drawOffice(c, cx, cy, R, t, p, d); break;
            case APEX: drawApex(c, cx, cy, R, t, p, d); break;
            case MIRA: drawMira(c, cx, cy, R, t, p, d); break;
            default: drawArc(c, cx, cy, R, t, p, d);
        }
        drawStateRing(c, cx, cy, R, t, p, d);
        drawGlitter(c, cx, cy, R, t, p, d);
        if (isShown()) postInvalidateOnAnimation();
    }

    // ---------------- JARVIS ARC reactor ----------------
    private void drawArc(Canvas c, float cx, float cy, float R, float t, int[] p, float d) {
        float pulse = 1 + .05f * (float) Math.sin(t * 2.4) + level * .18f;
        // Outer rotating dashed rings.
        for (int i = 0; i < 3; i++) {
            float rr = R * (1.05f + i * .14f);
            rect.set(cx - rr, cy - rr, cx + rr, cy + rr);
            stroke(i == 1 ? p[1] : p[0], .55f - i * .12f, (i == 0 ? 2.6f : 1.4f) * d);
            float rot = t * (i % 2 == 0 ? 26 : -19) + i * 40;
            int segs = 12 + i * 6;
            for (int s = 0; s < segs; s++) c.drawArc(rect, rot + s * 360f / segs, 360f / segs * .55f, false, line);
        }
        // Degree ticks.
        stroke(p[0], .35f, 1f * d);
        for (int k = 0; k < 120; k++) {
            double a = Math.PI * 2 * k / 120 + t * .05;
            float r1 = R * 1.5f, r2 = R * (k % 10 == 0 ? 1.58f : 1.53f);
            c.drawLine(cx + (float) Math.cos(a) * r1, cy + (float) Math.sin(a) * r1,
                cx + (float) Math.cos(a) * r2, cy + (float) Math.sin(a) * r2, line);
        }
        // Reactor casing and 10 coil segments.
        stroke(p[1], .8f, 3f * d);
        c.drawCircle(cx, cy, R * .78f * pulse, line);
        for (int k = 0; k < 10; k++) {
            double a = Math.PI * 2 * k / 10 + t * .35;
            float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
            float r1 = R * .46f, r2 = R * .72f;
            path.reset();
            float wdt = .13f;
            path.moveTo(cx + (float) Math.cos(a - wdt) * r1, cy + (float) Math.sin(a - wdt) * r1);
            path.lineTo(cx + (float) Math.cos(a - wdt * .8) * r2, cy + (float) Math.sin(a - wdt * .8) * r2);
            path.lineTo(cx + (float) Math.cos(a + wdt * .8) * r2, cy + (float) Math.sin(a + wdt * .8) * r2);
            path.lineTo(cx + (float) Math.cos(a + wdt) * r1, cy + (float) Math.sin(a + wdt) * r1);
            path.close();
            add.setColor(alpha(p[0], .35f * brightness * energy));
            c.drawPath(path, add);
            stroke(p[1], .9f, 1.2f * d);
            c.drawPath(path, line);
            dot(c, cx + ca * r2 * 1.05f, cy + sa * r2 * 1.05f, 2.2f * d, p[1], .9f);
        }
        // Inner triangle + white-hot core.
        path.reset();
        for (int k = 0; k < 3; k++) {
            double a = -Math.PI / 2 + Math.PI * 2 * k / 3 - t * .6;
            float x = cx + (float) Math.cos(a) * R * .36f, y = cy + (float) Math.sin(a) * R * .36f;
            if (k == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        path.close();
        stroke(p[1], .95f, 2.4f * d);
        c.drawPath(path, line);
        glow(c, cx, cy, R * .62f * pulse, p[0], .75f);
        glow(c, cx, cy, R * .30f * pulse, p[1], .95f);
        glow(c, cx, cy, R * .14f * pulse, Color.WHITE, 1f);
        // Orbiting photons.
        for (int k = 0; k < 36; k++) {
            double a = k * 2.399 + t * (.4 + (k % 5) * .12);
            float rr = R * (1.0f + (k % 7) * .07f);
            dot(c, cx + (float) Math.cos(a) * rr, cy + (float) Math.sin(a) * rr * .96f, (1.2f + (k % 3) * .6f) * d,
                k % 4 == 0 ? p[1] : p[0], .8f);
        }
    }

    // ---------------- AIRA neural reactor ----------------
    private void drawAira(Canvas c, float cx, float cy, float R, float t, int[] p, float d) {
        int n = nodes.length / 3;
        float[] sx = new float[n], sy = new float[n], sz = new float[n];
        double yaw = t * .35, pitch = .35 * Math.sin(t * .2);
        float cyw = (float) Math.cos(yaw), syw = (float) Math.sin(yaw), cp = (float) Math.cos(pitch), sp = (float) Math.sin(pitch);
        for (int i = 0; i < n; i++) {
            float wob = .9f + .05f * (float) Math.sin(t * 1.3 + i * .7) + level * .1f;
            float x = nodes[i * 3] * wob, y = nodes[i * 3 + 1] * wob, z = nodes[i * 3 + 2] * wob;
            float x1 = x * cyw + z * syw, z1 = -x * syw + z * cyw;
            float y2 = y * cp - z1 * sp, z2 = y * sp + z1 * cp;
            float f = 3.2f / (3.2f - z2);
            sx[i] = cx + x1 * R * f; sy[i] = cy + y2 * R * f; sz[i] = (z2 + 1) / 2;
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < Math.min(n, i + 9); j += 4) {
                stroke(sz[i] > .5f ? p[0] : p[2], .2f + .45f * sz[i], .9f * d);
                c.drawLine(sx[i], sy[i], sx[j], sy[j], line);
            }
        }
        for (int i = 0; i < n; i++) dot(c, sx[i], sy[i], (1.1f + 2.2f * sz[i]) * d, i % 7 == 0 ? p[1] : p[0], .4f + .6f * sz[i]);
        for (int k = 0; k < 24; k++) {
            int i = (k * 37) % n, j = (i + 4) % n;
            float q = (float) ((t * (.5 + (k % 5) * .1) + k * .13) % 1);
            float x = sx[i] + (sx[j] - sx[i]) * q, y = sy[i] + (sy[j] - sy[i]) * q;
            glow(c, x, y, R * .07f, p[1], .6f);
            dot(c, x, y, 2.6f * d, Color.WHITE, 1f);
        }
        glow(c, cx, cy, R * .7f, p[0], .55f);
        glow(c, cx, cy, R * .32f, p[1], .8f);
        glow(c, cx, cy, R * .14f, Color.WHITE, 1f);
    }

    // ---------------- Agent Office (honest: not connected) ----------------
    private void drawOffice(Canvas c, float cx, float cy, float R, float t, int[] p, float d) {
        text.setTextSize(12 * d);
        for (int k = 0; k < 5; k++) {
            double a = -Math.PI / 2 + Math.PI * 2 * k / 5;
            float x = cx + (float) Math.cos(a) * R * 1.15f, y = cy + (float) Math.sin(a) * R * 1.15f;
            stroke(p[0], .25f, 1.2f * d);
            c.drawLine(cx, cy, x, y, line);
            stroke(0xff7a8a99, .7f, 1.5f * d);
            c.drawCircle(x, y, R * .2f, line);
            glow(c, x, y, R * .22f, 0xff5c6b7a, .35f);
            text.setColor(0xeeffffff);
            c.drawText(AGENTS[k], x, y - R * .26f, text);
            text.setColor(0xaaff8a8a);
            c.drawText("NOT CONNECTED", x, y + R * .34f, text);
        }
        glow(c, cx, cy, R * .45f, p[0], .7f);
        glow(c, cx, cy, R * .18f, Color.WHITE, .9f);
        text.setColor(0xccffffff);
        text.setTextSize(11 * d);
        c.drawText("No agent provider configured · no simulated progress", cx, cy + R * 1.75f, text);
    }

    // ---------------- APEX cyan/gold techno ----------------
    private void drawApex(Canvas c, float cx, float cy, float R, float t, int[] p, float d) {
        for (int layer = 0; layer < 7; layer++) {
            float rr = R * (.28f + layer * .14f);
            rect.set(cx - rr, cy - rr, cx + rr, cy + rr);
            float dir = layer % 2 == 0 ? 1 : -1;
            int segs = 18 + layer * 6;
            stroke(layer % 2 == 0 ? p[1] : p[0], .75f + .08f * (layer % 3), (layer == 3 ? 3.2f : 1.8f) * d);
            for (int s = 0; s < segs; s++)
                if ((s * 7 + layer) % 5 != 0) c.drawArc(rect, t * 20 * dir * (.5f + layer * .08f) + s * 360f / segs, 360f / segs * .4f, false, line);
        }
        // Radar sweep.
        float sweep = (t * 70) % 360;
        rect.set(cx - R * 1.2f, cy - R * 1.2f, cx + R * 1.2f, cy + R * 1.2f);
        stroke(p[2], .9f, 2f * d);
        c.drawArc(rect, sweep, 26, false, line);
        // Hexagonal core plate and bright gold/cyan bloom.
        path.reset();
        for (int k = 0; k <= 6; k++) {
            double a = Math.PI / 3 * k + t * .4;
            float x = cx + (float) Math.cos(a) * R * .3f, y = cy + (float) Math.sin(a) * R * .3f;
            if (k == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        stroke(p[1], 1f, 2.6f * d);
        c.drawPath(path, line);
        for (int k = 0; k < 24; k++) {
            double a = Math.PI * 2 * k / 24 - t * .9;
            float rr = R * (1.0f + .06f * (k % 3));
            dot(c, cx + (float) Math.cos(a) * rr, cy + (float) Math.sin(a) * rr, (1.6f + (k % 3)) * d, k % 2 == 0 ? p[1] : p[0], 1f);
        }
        glow(c, cx, cy, R * .9f, p[0], .45f);
        glow(c, cx, cy, R * .55f, p[1], .9f);
        glow(c, cx, cy, R * .22f, Color.WHITE, 1f);
    }

    // ---------------- MIRA violet neural orbits ----------------
    private void drawMira(Canvas c, float cx, float cy, float R, float t, int[] p, float d) {
        for (int k = 0; k < 7; k++) {
            c.save();
            c.rotate((float) (t * (8 + k * 3) * (k % 2 == 0 ? 1 : -1) + k * 26), cx, cy);
            float rx = R * (1.25f - k * .05f), ry = R * (.35f + .08f * (float) Math.sin(t * .7 + k));
            rect.set(cx - rx, cy - ry, cx + rx, cy + ry);
            stroke(p[k % 3], .5f, (k == 0 ? 2.6f : 1.4f) * d);
            c.drawOval(rect, line);
            double a = t * (1 + k * .2) + k;
            glow(c, cx + (float) Math.cos(a) * rx, cy + (float) Math.sin(a) * ry, R * .1f, p[k % 3], .8f);
            c.restore();
        }
        glow(c, cx, cy, R * .6f, p[0], .7f);
        glow(c, cx, cy, R * .25f, p[1], .9f);
        glow(c, cx, cy, R * .11f, Color.WHITE, 1f);
    }

    private void drawStateRing(Canvas c, float cx, float cy, float R, float t, int[] p, float d) {
        switch (state) {
            case LISTENING: {
                path.reset();
                for (int k = 0; k <= 120; k++) {
                    double a = Math.PI * 2 * k / 120;
                    float rr = R * (1.72f + (.03f + level * .16f) * (float) Math.sin(a * 14 + t * 9));
                    float x = cx + (float) Math.cos(a) * rr, y = cy + (float) Math.sin(a) * rr;
                    if (k == 0) path.moveTo(x, y); else path.lineTo(x, y);
                }
                stroke(p[0], .8f, 2f * d);
                c.drawPath(path, line);
                break;
            }
            case THINKING: {
                for (int k = 0; k < 3; k++) {
                    float rr = R * (1.7f + k * .08f);
                    rect.set(cx - rr, cy - rr, cx + rr, cy + rr);
                    stroke(p[2], .7f, (2.4f - k * .5f) * d);
                    c.drawArc(rect, t * (120 + k * 50) * (k % 2 == 0 ? 1 : -1), 60 - k * 12, false, line);
                }
                break;
            }
            case SPEAKING: {
                for (int k = 0; k < 4; k++) {
                    float q = (t * .7f + k / 4f) % 1f;
                    stroke(p[k % 2], (1 - q) * .7f * (.7f + level), 2f * d);
                    c.drawCircle(cx, cy, R * (1.0f + q * .9f), line);
                }
                break;
            }
            default: break;
        }
    }

    private void drawGlitter(Canvas c, float cx, float cy, float R, float t, int[] p, float d) {
        for (int k = 0; k < 40; k++) {
            double a = k * 2.399963;
            float rr = R * (.4f + 1.5f * (float) Math.sqrt(((k * 7919) % 100) / 100f));
            float tw = (float) Math.pow(Math.max(0, Math.sin(t * (1.4 + (k % 7) * .35) + k * 2.1)), 8);
            float x = cx + (float) Math.cos(a) * rr, y = cy + (float) Math.sin(a) * rr;
            dot(c, x, y, (.8f + tw * 1.8f) * d, p[k % 3], .25f + .75f * tw);
            if (tw > .75f) {
                stroke(Color.WHITE, tw * .8f, .8f * d);
                c.drawLine(x - 6 * d * tw, y, x + 6 * d * tw, y, line);
                c.drawLine(x, y - 6 * d * tw, x, y + 6 * d * tw, line);
            }
        }
    }
}
