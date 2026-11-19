package com.adsjarvis.standalone;

import android.content.Context;
import android.content.res.AssetManager;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * GPU visual engine (OpenGL ES 2.0 fragment shaders from assets/shaders).
 * Styles: ARC, AIRA, MIRA, APEX, OFFICE. Animation state follows the assistant
 * state; quality presets render at reduced resolution; FPS is capped and
 * measured; battery saver / thermal status lower quality automatically.
 */
public final class VisualEngine extends GLSurfaceView implements GLSurfaceView.Renderer {
    public interface FailureListener { void onGlFailure(String reason); }

    private static final String TAG = "JarvisVisual";
    private static final String[] STYLES = {"ARC", "AIRA", "MIRA", "APEX", "OFFICE"};

    private final AssetManager assets;
    private final Map<String, Integer> programs = new HashMap<>();
    private FloatBuffer quad;
    private volatile String style = "ARC";
    private volatile float state, level, glow = 1f, density = 1f, speed = 1f, morph;
    private volatile float[] accent = {0.1f, 0.9f, 1f};
    private final float[] agents = new float[5];
    private volatile int fpsCap = 60;
    private volatile float quality = 1f;
    private volatile boolean reduceMotion, batterySaver = true;
    private volatile float measuredFps;
    private volatile String lastError = "";
    private FailureListener failure;
    private long lastNanos, fpsWindowStart;
    private int fpsFrames;
    private float t;
    private int surfaceW, surfaceH;

    public VisualEngine(Context c) {
        super(c);
        assets = c.getAssets();
        setEGLContextClientVersion(2);
        setPreserveEGLContextOnPause(true);
        setRenderer(this);
        setRenderMode(RENDERMODE_CONTINUOUSLY);
        setContentDescription("JARVIS visual");
    }

    public void setFailureListener(FailureListener l) { failure = l; }
    public void setStyle(String s) { for (String x : STYLES) if (x.equalsIgnoreCase(s)) style = x; }
    public String getStyle() { return style; }
    /** 0 ready/idle, 1 listening, 2 thinking, 3 speaking, 4 error */
    public void setStateCode(int s) { state = s; }
    public void setLevel(float v) { level = Math.max(0, Math.min(1, v)); }
    public void setGlow(float g) { glow = g; }
    public void setDensity(float d) { density = d; }
    public void setSpeed(float s) { speed = s; }
    public void setAccent(int rgb) { accent = new float[]{((rgb >> 16) & 255) / 255f, ((rgb >> 8) & 255) / 255f, (rgb & 255) / 255f}; }
    public void setFpsCap(int fps) { fpsCap = Math.max(15, Math.min(120, fps)); }
    public void setReduceMotion(boolean r) { reduceMotion = r; }
    public void setBatterySaverAware(boolean b) { batterySaver = b; }
    public void setAgentActivity(int idx, float v) { if (idx >= 0 && idx < 5) agents[idx] = v; }
    public float getMeasuredFps() { return measuredFps; }
    public String getLastError() { return lastError; }

    /** 0.5 low, 0.75 balanced, 1.0 high — applied as surface resolution scale. */
    public void setQuality(float q) {
        quality = q;
        if (surfaceW > 0) post(this::applyResolution);
    }

    private float effectiveQuality() {
        float q = quality;
        PowerManager pm = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            if (batterySaver && pm.isPowerSaveMode()) q = Math.min(q, .5f);
            if (Build.VERSION.SDK_INT >= 29 && pm.getCurrentThermalStatus() >= PowerManager.THERMAL_STATUS_MODERATE) q = Math.min(q, .5f);
        }
        return q;
    }

    private void applyResolution() {
        float q = effectiveQuality();
        if (getWidth() > 0 && getHeight() > 0) getHolder().setFixedSize(Math.max(1, (int) (getWidth() * q)), Math.max(1, (int) (getHeight() * q)));
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        post(this::applyResolution);
    }

    private String asset(String name) throws Exception {
        try (InputStream in = assets.open("shaders/" + name)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[4096]; int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return bo.toString("UTF-8");
        }
    }

    private int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) { String log = GLES20.glGetShaderInfoLog(s); GLES20.glDeleteShader(s); throw new IllegalStateException(log); }
        return s;
    }

    @Override public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        programs.clear();
        float[] v = {-1, -1, 1, -1, -1, 1, 1, 1};
        quad = ByteBuffer.allocateDirect(v.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(v).position(0);
        try {
            String common = asset("common.glsl");
            int vs = compile(GLES20.GL_VERTEX_SHADER, "attribute vec2 aPos; void main(){gl_Position=vec4(aPos,0.0,1.0);}");
            for (String s : STYLES) {
                int fs = compile(GLES20.GL_FRAGMENT_SHADER, common + "\n" + asset(s.toLowerCase() + ".frag"));
                int p = GLES20.glCreateProgram();
                GLES20.glAttachShader(p, vs); GLES20.glAttachShader(p, fs);
                GLES20.glBindAttribLocation(p, 0, "aPos");
                GLES20.glLinkProgram(p);
                int[] ok = new int[1];
                GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
                if (ok[0] == 0) throw new IllegalStateException(s + " link: " + GLES20.glGetProgramInfoLog(p));
                programs.put(s, p);
            }
            Log.i(TAG, "JARVIS_GL ok programs=" + programs.size() + " renderer=" + GLES20.glGetString(GLES20.GL_RENDERER));
        } catch (Exception e) {
            lastError = String.valueOf(e.getMessage());
            Log.e(TAG, "Shader setup failed: " + lastError);
            FailureListener f = failure;
            if (f != null) post(() -> f.onGlFailure(lastError));
        }
        lastNanos = System.nanoTime();
        fpsWindowStart = SystemClock.elapsedRealtime();
    }

    @Override public void onSurfaceChanged(GL10 unused, int w, int h) {
        surfaceW = w; surfaceH = h;
        GLES20.glViewport(0, 0, w, h);
    }

    @Override public void onDrawFrame(GL10 unused) {
        long now = System.nanoTime();
        float dt = Math.min(.1f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        t += dt * speed * (reduceMotion ? .25f : 1f) * (state == 1 || state == 3 ? 1.25f : 1f);
        // MIRA/APEX transform: humanoid while engaged, orb at rest (eased).
        float targetMorph = state >= 1 && state <= 3 ? 1f : ("APEX".equals(style) ? 1f : 0f);
        morph += (targetMorph - morph) * Math.min(1f, dt * 1.5f);
        Integer p = programs.get(style);
        GLES20.glClearColor(0, 0, 0, 1);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        if (p != null) {
            GLES20.glUseProgram(p);
            GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uRes"), surfaceW, surfaceH);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uTime"), t);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uState"), state);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uLevel"), level);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uGlow"), glow);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uDensity"), density);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uMorph"), morph);
            GLES20.glUniform3f(GLES20.glGetUniformLocation(p, "uAccent"), accent[0], accent[1], accent[2]);
            GLES20.glUniform1fv(GLES20.glGetUniformLocation(p, "uAgents"), 5, agents, 0);
            GLES20.glEnableVertexAttribArray(0);
            GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, quad);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        }
        // FPS measurement and cap.
        fpsFrames++;
        long wall = SystemClock.elapsedRealtime();
        if (wall - fpsWindowStart >= 1000) {
            measuredFps = fpsFrames * 1000f / (wall - fpsWindowStart);
            fpsFrames = 0; fpsWindowStart = wall;
        }
        int cap = reduceMotion ? Math.min(fpsCap, 30) : fpsCap;
        long frameNs = 1_000_000_000L / cap;
        long spent = System.nanoTime() - now;
        if (spent < frameNs) {
            try { Thread.sleep((frameNs - spent) / 1_000_000L); } catch (InterruptedException ignored) {}
        }
    }
}
