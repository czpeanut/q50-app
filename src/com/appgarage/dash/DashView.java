package com.appgarage.dash;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

import java.util.Random;

/**
 * The 800x480 driving screen, drawn to the owner's layout: a segmented tachometer column on
 * the left, a coolant column on the right, gear and steering across the top, the car in the
 * middle with a tyre-pressure callout at each corner, and a two-way brake/throttle bar along
 * the bottom.
 *
 * Only signals confirmed on this car are shown. The design's GPS block and map thumbnail have
 * no source -- all 39 signals are Ygomi CAN and none of them is a position -- so that corner
 * carries road speed instead, which the design was otherwise missing.
 *
 * Drawing strategy for a software-rendered API 10 unit with no GPU:
 *
 *   - Everything that never changes (background wash, starfield, frames, labels, the car
 *     outline, the callout leader lines) is rendered ONCE into an off-screen bitmap. Each
 *     frame blits that and then paints only the live parts on top. This is the difference
 *     between redrawing a few dozen shapes per frame and redrawing several hundred.
 *   - onDraw allocates nothing. Every Paint, Path, RectF and text buffer is built in the
 *     constructor, numbers are formatted into a reusable char[], and labels are constants.
 *     Dalvik's collector stops the world, so a per-frame allocation is a visible stutter.
 *   - Glow is two passes of stroke -- wide and faint, then narrow and bright -- rather than
 *     BlurMaskFilter, which is far too slow here.
 */
public class DashView extends View {

    // ---- signal types (all confirmed on this car) ----
    private static final int RPM = 13, COOLANT = 14, SPEED = 17, GEAR = 22,
            ACCEL = 23, BRAKE = 24, STEER = 25, ODO = 43,
            TP_FR = 36, TP_FL = 37, TP_RR = 38, TP_RL = 39;

    // ---- calibration ----
    private static final float REDLINE = 7000f;     // VQ35HR
    private static final float COOLANT_MIN = 40f, COOLANT_MAX = 120f, COOLANT_WARN = 105f;
    private static final float TPMS_LOW = 30f, TPMS_HIGH = 44f;
    private static final float STEER_FULL = 390f;   // measured full lock on this car
    private static final float PEDAL_FULL = 90f;    // type 24 declares max 90

    // ---- palette ----
    private static final int BG_TOP = 0xFF070B14, BG_BOT = 0xFF0D1522;
    private static final int CYAN = 0xFF3FD2FF, CYAN_DIM = 0xFF1B4E66, CYAN_GLOW = 0x553FD2FF;
    private static final int WHITE = 0xFFEAF6FF, GREY = 0xFF5A6B7C;
    private static final int AMBER = 0xFFFFB020, RED = 0xFFFF4545, GREEN = 0xFF46E08A;
    private static final int PANEL = 0xCC0A1420;

    // ---- live values ----
    private final float[] v = new float[64];
    private final boolean[] have = new boolean[64];

    // ---- preallocated drawing state ----
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path path = new Path();
    private final char[] buf = new char[20];
    private Bitmap staticLayer;
    private boolean cjk = true;

    // ---- geometry, resolved in onSizeChanged ----
    private float W, H;
    private float rpmX, rpmY0, rpmY1, barW;
    private float cooX;
    private float carCx, carCy, carW, carH;
    private float dialCx, dialCy, dialR;
    private final RectF[] tyreBox = new RectF[4];
    private final float[] tyreDotX = new float[4], tyreDotY = new float[4];
    private final int[] tyreType = { TP_FL, TP_FR, TP_RL, TP_RR };
    private float pedalL, pedalR, pedalY0, pedalY1;

    public DashView(Context c) {
        super(c);
        for (int i = 0; i < 4; i++) tyreBox[i] = new RectF();
        pText.setTypeface(Typeface.MONOSPACE);      // digits must not jitter as they change
        // Android 2.3 normally carries DroidSansFallback, but this unit's Android layer is a
        // guest system and its font set is not guaranteed. A zero-width measurement means the
        // glyph is missing, in which case fall back to English rather than drawing tofu.
        Paint probe = new Paint();
        cjk = probe.measureText("轉") > 0.5f;
    }

    public void setValue(int t, float val) { if (t >= 0 && t < 64) { v[t] = val; have[t] = true; } }
    public boolean isCjk() { return cjk; }
    public void setCjk(boolean on) { cjk = on; staticLayer = null; invalidate(); }

    private float g(int t) { return have[t] ? v[t] : 0f; }
    private boolean h(int t) { return have[t]; }

    /** representative values so the layout can be judged off-car */
    public void seedDemo() {
        setValue(RPM, 3120f); setValue(COOLANT, 88f); setValue(SPEED, 64f); setValue(GEAR, 4f);
        setValue(ACCEL, 340f); setValue(BRAKE, 0f); setValue(STEER, 12f); setValue(ODO, 48213f);
        setValue(TP_FL, 39.25f); setValue(TP_FR, 39.25f);
        setValue(TP_RL, 38.5f); setValue(TP_RR, 29.5f);
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void onSizeChanged(int w, int hh, int ow, int oh) {
        super.onSizeChanged(w, hh, ow, oh);
        W = w; H = hh;
        staticLayer = null;

        // Laid out against the real 800x480 panel with every block in its own band, after a
        // desktop render of this same geometry caught the first attempt colliding in four
        // places at once: the odometer sat on the tachometer heading, the throttle arrow flew
        // into the header, "PSI" printed through its own number, and the speed block
        // overlapped the rear-right tyre callout.
        barW = W * 0.0625f;
        rpmX = W * 0.0225f;
        cooX = W - rpmX - barW;
        rpmY0 = H * 0.20f;                     // headings live above this line
        rpmY1 = H * 0.929f;

        carW = W * 0.2375f;
        carH = H * 0.479f;
        carCx = W * 0.5f;
        carCy = H * 0.604f;

        dialR = H * 0.0833f;
        dialCx = W * 0.75f;
        dialCy = H * 0.1083f;

        float bw = W * 0.1875f, bh = H * 0.125f;   // tall enough that the label clears the number
        float leftX = W * 0.11f, rightX = W * 0.7025f;
        float topY = H * 0.3646f, botY = H * 0.625f;
        tyreBox[0].set(leftX,  topY, leftX + bw,  topY + bh);   // front left
        tyreBox[1].set(rightX, topY, rightX + bw, topY + bh);   // front right
        tyreBox[2].set(leftX,  botY, leftX + bw,  botY + bh);   // rear left
        tyreBox[3].set(rightX, botY, rightX + bw, botY + bh);   // rear right

        // where each callout points to on the car
        tyreDotX[0] = carCx - carW * 0.46f; tyreDotY[0] = carCy - carH * 0.20f;
        tyreDotX[1] = carCx + carW * 0.46f; tyreDotY[1] = carCy - carH * 0.20f;
        tyreDotX[2] = carCx - carW * 0.46f; tyreDotY[2] = carCy + carH * 0.26f;
        tyreDotX[3] = carCx + carW * 0.46f; tyreDotY[3] = carCy + carH * 0.26f;

        pedalL = W * 0.3125f; pedalR = W * 0.6875f;
        pedalY0 = H * 0.8333f; pedalY1 = H * 0.90f;
    }

    // ------------------------------------------------------------------ static layer

    private void buildStatic() {
        Bitmap bm;
        try {
            bm = Bitmap.createBitmap((int) W, (int) H, Bitmap.Config.RGB_565);
        } catch (Throwable t) {
            staticLayer = null;                      // out of memory: fall back to live drawing
            return;
        }
        Canvas c = new Canvas(bm);
        drawStatic(c);
        staticLayer = bm;
    }

    private void drawStatic(Canvas c) {
        // background wash
        p.setShader(new LinearGradient(0, 0, 0, H, BG_TOP, BG_BOT, Shader.TileMode.CLAMP));
        p.setStyle(Paint.Style.FILL);
        c.drawRect(0, 0, W, H, p);
        p.setShader(null);

        // starfield, fixed seed so it never shimmers between rebuilds
        Random rnd = new Random(20260921L);
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 160; i++) {
            float x = rnd.nextFloat() * W, y = rnd.nextFloat() * H;
            float rad = 0.4f + rnd.nextFloat() * 1.1f;
            p.setColor(0xFF000000 | (0x203040 + rnd.nextInt(0x304050)));
            c.drawCircle(x, y, rad, p);
        }

        drawCar(c);

        // column frames and their headings
        frame(c, rpmX, rpmY0, rpmX + barW, rpmY1);
        frame(c, cooX, rpmY0, cooX + barW, rpmY1);
        pText.setColor(WHITE);
        pText.setTextSize(H * 0.071f);
        pText.setTextAlign(Paint.Align.LEFT);
        c.drawText(cjk ? "轉速" : "RPM", rpmX, H * 0.0833f, pText);
        pText.setTextAlign(Paint.Align.RIGHT);
        c.drawText(cjk ? "水溫" : "COOLANT", cooX + barW, H * 0.0833f, pText);

        // gear
        frame(c, W * 0.1875f, H * 0.0583f, W * 0.2875f, H * 0.1833f);
        label(c, cjk ? "檔位" : "GEAR", W * 0.2375f, H * 0.0458f);

        // steering readout heading + dial
        label(c, cjk ? "轉向角" : "STEERING", W * 0.475f, H * 0.0458f);
        p.setStyle(Paint.Style.STROKE);
        p.setColor(CYAN_DIM);
        p.setStrokeWidth(1.6f);
        c.drawCircle(dialCx, dialCy, dialR, p);
        for (int i = 0; i < 12; i++) {             // tick ring
            double a = Math.PI * 2 * i / 12.0;
            float sx = dialCx + (float) Math.cos(a) * dialR * 0.84f;
            float sy = dialCy + (float) Math.sin(a) * dialR * 0.84f;
            float ex = dialCx + (float) Math.cos(a) * dialR * 0.96f;
            float ey = dialCy + (float) Math.sin(a) * dialR * 0.96f;
            c.drawLine(sx, sy, ex, ey, p);
        }

        // tyre callouts: leader line, anchor dot, box, label
        for (int i = 0; i < 4; i++) {
            RectF b = tyreBox[i];
            boolean left = i == 0 || i == 2;
            float ax = left ? b.right : b.left;
            float ay = b.centerY();
            p.setStyle(Paint.Style.STROKE);
            p.setColor(CYAN_DIM);
            p.setStrokeWidth(1.4f);
            c.drawLine(ax, ay, tyreDotX[i], tyreDotY[i], p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(CYAN);
            c.drawCircle(tyreDotX[i], tyreDotY[i], 3.2f, p);
            panel(c, b);
            pText.setColor(GREY);
            pText.setTextSize(H * 0.038f);
            pText.setTextAlign(Paint.Align.LEFT);
            c.drawText(tyreLabel(i), b.left + W * 0.012f, b.top + H * 0.038f, pText);
        }

        // pedal bar
        r.set(pedalL, pedalY0, pedalR, pedalY1);
        panel(c, r);
        p.setStyle(Paint.Style.STROKE);
        p.setColor(CYAN_DIM);
        p.setStrokeWidth(1.4f);
        float mid = (pedalL + pedalR) * 0.5f;
        c.drawLine(mid, pedalY0, mid, pedalY1, p);
        pText.setColor(GREY);
        pText.setTextSize(H * 0.046f);
        pText.setTextAlign(Paint.Align.LEFT);
        c.drawText(cjk ? "刹車" : "BRAKE", pedalL, pedalY0 - H * 0.018f, pText);
        pText.setTextAlign(Paint.Align.RIGHT);
        c.drawText(cjk ? "加速" : "THROTTLE", pedalR, pedalY0 - H * 0.018f, pText);

        // speed block, standing in for the design's GPS panel, in the band below the callouts
        label(c, cjk ? "車速 km/h" : "SPEED km/h", W * 0.80f, H * 0.7917f);
    }

    /**
     * Rear three-quarter outline. Deliberately a plain, replaceable Path -- this is the one
     * piece meant to be redrawn by hand; nothing else depends on its shape.
     */
    private void drawCar(Canvas c) {
        float hw = carW * 0.5f, hh = carH * 0.5f;
        float x = carCx, y = carCy;
        path.rewind();
        // body
        path.moveTo(x - hw, y + hh * 0.52f);
        path.lineTo(x - hw, y - hh * 0.18f);
        path.quadTo(x - hw * 0.94f, y - hh * 0.52f, x - hw * 0.62f, y - hh * 0.62f);
        path.lineTo(x - hw * 0.44f, y - hh * 0.95f);
        path.quadTo(x, y - hh * 1.06f, x + hw * 0.44f, y - hh * 0.95f);
        path.lineTo(x + hw * 0.62f, y - hh * 0.62f);
        path.quadTo(x + hw * 0.94f, y - hh * 0.52f, x + hw, y - hh * 0.18f);
        path.lineTo(x + hw, y + hh * 0.52f);
        path.quadTo(x + hw * 0.86f, y + hh * 0.76f, x + hw * 0.54f, y + hh * 0.78f);
        path.lineTo(x - hw * 0.54f, y + hh * 0.78f);
        path.quadTo(x - hw * 0.86f, y + hh * 0.76f, x - hw, y + hh * 0.52f);
        path.close();
        // rear glass
        path.moveTo(x - hw * 0.52f, y - hh * 0.60f);
        path.quadTo(x, y - hh * 0.72f, x + hw * 0.52f, y - hh * 0.60f);
        path.lineTo(x + hw * 0.62f, y - hh * 0.22f);
        path.lineTo(x - hw * 0.62f, y - hh * 0.22f);
        path.close();
        // lamps and valance
        path.addRect(x - hw * 0.92f, y + hh * 0.02f, x - hw * 0.46f, y + hh * 0.22f,
                Path.Direction.CW);
        path.addRect(x + hw * 0.46f, y + hh * 0.02f, x + hw * 0.92f, y + hh * 0.22f,
                Path.Direction.CW);
        path.addRect(x - hw * 0.30f, y + hh * 0.52f, x + hw * 0.30f, y + hh * 0.66f,
                Path.Direction.CW);
        glowPath(c, path, CYAN, carW * 0.028f);
    }

    // ------------------------------------------------------------------ frame

    @Override
    protected void onDraw(Canvas c) {
        if (W <= 0) return;
        if (staticLayer == null) buildStatic();
        if (staticLayer != null) c.drawBitmap(staticLayer, 0, 0, null);
        else drawStatic(c);                          // no bitmap: pay the cost every frame

        drawTach(c);
        drawCoolant(c);
        drawGear(c);
        drawSteering(c);
        drawTyres(c);
        drawPedals(c);
        drawSpeed(c);
        drawTelemetry(c);
    }

    private void drawTach(Canvas c) {
        final int N = 24;
        float inner = barW * 0.18f;
        float x0 = rpmX + inner, x1 = rpmX + barW - inner;
        float span = (rpmY1 - rpmY0) - inner * 2f;
        float seg = span / N;
        float lit = h(RPM) ? g(RPM) / REDLINE : 0f;
        if (lit < 0) lit = 0; else if (lit > 1) lit = 1;
        int on = (int) (lit * N + 0.5f);
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < N; i++) {
            float t = rpmY1 - inner - (i + 1) * seg;
            boolean isOn = i < on;
            boolean isRed = i >= N - 4;
            if (isOn) {
                p.setColor(isRed ? RED : CYAN_GLOW);
                c.drawRect(x0 - 2f, t + seg * 0.10f - 2f, x1 + 2f, t + seg * 0.82f + 2f, p);
                p.setColor(isRed ? RED : CYAN);
            } else {
                p.setColor(isRed ? 0xFF3A1414 : 0xFF13212C);
            }
            c.drawRect(x0, t + seg * 0.10f, x1, t + seg * 0.82f, p);
        }
        pText.setColor(h(RPM) ? WHITE : GREY);
        pText.setTextSize(H * 0.058f);
        pText.setTextAlign(Paint.Align.LEFT);
        int n = h(RPM) ? fmt(g(RPM), 0) : dashes();
        c.drawText(buf, 0, n, rpmX, H * 0.1667f, pText);
    }

    private void drawCoolant(Canvas c) {
        float inner = barW * 0.18f;
        float x0 = cooX + inner, x1 = cooX + barW - inner;
        float y0 = rpmY0 + inner, y1 = rpmY1 - inner;
        float frac = h(COOLANT)
                ? (g(COOLANT) - COOLANT_MIN) / (COOLANT_MAX - COOLANT_MIN) : 0f;
        if (frac < 0) frac = 0; else if (frac > 1) frac = 1;
        float top = y1 - (y1 - y0) * frac;
        int col = (h(COOLANT) && g(COOLANT) >= COOLANT_WARN) ? RED : CYAN;

        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF10202B);
        c.drawRect(x0, y0, x1, y1, p);
        if (h(COOLANT) && frac > 0.01f) {
            p.setColor(col & 0x66FFFFFF);
            c.drawRect(x0, top, x1, y1, p);
            // surface line, a cheap stand-in for the design's liquid meniscus
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2.4f);
            p.setColor(col);
            path.rewind();
            path.moveTo(x0, top);
            path.quadTo((x0 + x1) * 0.5f, top - 4f, x1, top);
            c.drawPath(path, p);
        }
        pText.setColor(h(COOLANT) ? (col == RED ? RED : WHITE) : GREY);
        pText.setTextSize(H * 0.058f);
        pText.setTextAlign(Paint.Align.RIGHT);
        int n = h(COOLANT) ? fmt(g(COOLANT), 0) : dashes();
        c.drawText(buf, 0, n, cooX + barW, H * 0.1667f, pText);
    }

    private void drawGear(Canvas c) {
        pText.setColor(h(GEAR) ? CYAN : GREY);
        pText.setTextSize(H * 0.105f);
        pText.setTextAlign(Paint.Align.CENTER);
        String s = gearText();
        c.drawText(s, W * 0.2375f, H * 0.1583f, pText);
    }

    /** P=1 R=2 N=3 D=4, M1..M7 = 16..22, confirmed on-car */
    private String gearText() {
        if (!h(GEAR)) return "--";
        int x = (int) (g(GEAR) + 0.5f);
        if (x == 1) return "P";
        if (x == 2) return "R";
        if (x == 3) return "N";
        if (x == 4) return "D";
        if (x >= 16 && x <= 22) {
            switch (x) {
                case 16: return "M1"; case 17: return "M2"; case 18: return "M3";
                case 19: return "M4"; case 20: return "M5"; case 21: return "M6";
                default: return "M7";
            }
        }
        return "--";
    }

    private void drawSteering(Canvas c) {
        float deg = g(STEER);                        // degrees directly, right positive
        pText.setColor(h(STEER) ? WHITE : GREY);
        pText.setTextSize(H * 0.0958f);
        pText.setTextAlign(Paint.Align.CENTER);
        int n = h(STEER) ? fmt(deg, 0) : dashes();
        c.drawText(buf, 0, n, W * 0.475f, H * 0.1625f, pText);

        c.save();
        c.rotate(h(STEER) ? deg : 0f, dialCx, dialCy);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dialR * 0.16f);
        p.setColor(h(STEER) ? CYAN : GREY);
        c.drawCircle(dialCx, dialCy, dialR * 0.62f, p);      // rim
        p.setStrokeWidth(dialR * 0.13f);
        c.drawLine(dialCx - dialR * 0.62f, dialCy, dialCx + dialR * 0.62f, dialCy, p);
        c.drawLine(dialCx, dialCy, dialCx, dialCy + dialR * 0.62f, p);
        c.restore();

        // lock indicator: how far round it is, and which way
        if (h(STEER)) {
            float frac = deg / STEER_FULL;
            if (frac < -1f) frac = -1f; else if (frac > 1f) frac = 1f;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(3f);
            p.setColor(CYAN);
            r.set(dialCx - dialR, dialCy - dialR, dialCx + dialR, dialCy + dialR);
            c.drawArc(r, -90f, frac * 180f, false, p);
        }
    }

    private void drawTyres(Canvas c) {
        for (int i = 0; i < 4; i++) {
            RectF b = tyreBox[i];
            int t = tyreType[i];
            boolean ok = h(t);
            float psi = g(t);
            boolean low = ok && psi < TPMS_LOW;
            boolean high = ok && psi > TPMS_HIGH;
            int col = (!ok) ? GREY : (low || high) ? AMBER : WHITE;

            pText.setColor(col);
            pText.setTextSize(H * 0.070f);
            pText.setTextAlign(Paint.Align.LEFT);
            int n = ok ? fmt(psi, 1) : dashes();
            float nx = b.left + W * 0.012f, ny = b.bottom - H * 0.018f;
            float nw = pText.measureText(buf, 0, n);     // measure it, do not guess the width
            c.drawText(buf, 0, n, nx, ny, pText);

            pText.setTextSize(H * 0.040f);
            pText.setColor(GREY);
            c.drawText("PSI", nx + nw + W * 0.012f, ny, pText);

            // status pip, echoing the design's heart icon
            p.setStyle(Paint.Style.FILL);
            p.setColor(!ok ? GREY : (low || high) ? AMBER : GREEN);
            c.drawCircle(b.right - W * 0.018f, b.top + H * 0.032f, H * 0.014f, p);
        }
    }

    private void drawPedals(Canvas c) {
        float mid = (pedalL + pedalR) * 0.5f;
        float half = (pedalR - pedalL) * 0.5f - 3f;
        p.setStyle(Paint.Style.FILL);

        float brk = h(BRAKE) ? g(BRAKE) / PEDAL_FULL : 0f;
        if (brk < 0) brk = 0; else if (brk > 1) brk = 1;
        if (brk > 0.005f) {
            p.setColor(0x66FF4545);
            c.drawRect(mid - half * brk, pedalY0 + 3f, mid - 2f, pedalY1 - 3f, p);
            p.setColor(RED);
            c.drawRect(mid - half * brk, pedalY0 + 3f, mid - half * brk + 4f, pedalY1 - 3f, p);
        }
        // type 23 reports 0..1000 for 0..100%; treat 1000 as full travel
        float thr = h(ACCEL) ? g(ACCEL) / 1000f : 0f;
        if (thr < 0) thr = 0; else if (thr > 1) thr = 1;
        if (thr > 0.005f) {
            p.setColor(0x663FD2FF);
            c.drawRect(mid + 2f, pedalY0 + 3f, mid + half * thr, pedalY1 - 3f, p);
            p.setColor(CYAN);
            c.drawRect(mid + half * thr - 4f, pedalY0 + 3f, mid + half * thr, pedalY1 - 3f, p);
        }

        // the design's arrow above the car, given a job: which pedal is winning, and how hard
        float net = thr - brk;
        if (net > 0.02f || net < -0.02f) {
            boolean up = net > 0;
            float mag = up ? net : -net;
            float ay = carCy - carH * 0.60f;             // just above the roof, not in the header
            float aw = carW * 0.15f * (0.55f + mag * 0.45f);
            float ah = carH * 0.13f * (0.55f + mag * 0.45f);
            path.rewind();
            if (up) {
                path.moveTo(carCx, ay - ah);
                path.lineTo(carCx + aw, ay);
                path.lineTo(carCx + aw * 0.42f, ay);
                path.lineTo(carCx + aw * 0.42f, ay + ah * 0.55f);
                path.lineTo(carCx - aw * 0.42f, ay + ah * 0.55f);
                path.lineTo(carCx - aw * 0.42f, ay);
                path.lineTo(carCx - aw, ay);
            } else {
                path.moveTo(carCx, ay + ah * 0.55f);
                path.lineTo(carCx + aw, ay - ah * 0.30f);
                path.lineTo(carCx + aw * 0.42f, ay - ah * 0.30f);
                path.lineTo(carCx + aw * 0.42f, ay - ah);
                path.lineTo(carCx - aw * 0.42f, ay - ah);
                path.lineTo(carCx - aw * 0.42f, ay - ah * 0.30f);
                path.lineTo(carCx - aw, ay - ah * 0.30f);
            }
            path.close();
            p.setStyle(Paint.Style.FILL);
            p.setColor(up ? 0x553FD2FF : 0x55FF4545);
            c.drawPath(path, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2f);
            p.setColor(up ? CYAN : RED);
            c.drawPath(path, p);
        }
    }

    private void drawSpeed(Canvas c) {
        pText.setColor(h(SPEED) ? WHITE : GREY);
        pText.setTextSize(H * 0.129f);
        pText.setTextAlign(Paint.Align.CENTER);
        int n = h(SPEED) ? fmt(g(SPEED), 0) : dashes();
        c.drawText(buf, 0, n, W * 0.80f, H * 0.929f, pText);
    }

    private void drawTelemetry(Canvas c) {
        pText.setTextSize(H * 0.038f);
        pText.setTextAlign(Paint.Align.LEFT);
        float x = W * 0.11f;                         // bottom-left band, clear of everything
        pText.setColor(GREY);
        c.drawText(cjk ? "總里程 km" : "ODO km", x, H * 0.8167f, pText);
        pText.setColor(CYAN);
        int n = h(ODO) ? fmt(g(ODO), 0) : dashes();
        c.drawText(buf, 0, n, x, H * 0.8833f, pText);
    }

    // ------------------------------------------------------------------ helpers

    private String tyreLabel(int i) {
        if (cjk) {
            switch (i) {
                case 0: return "左前胎壓";   // front left
                case 1: return "右前胎壓";   // front right
                case 2: return "左後胎壓";   // rear left -- the design said
                default: return "右後胎壓";  // "front left" here by mistake
            }
        }
        switch (i) {
            case 0: return "FRONT LEFT";
            case 1: return "FRONT RIGHT";
            case 2: return "REAR LEFT";
            default: return "REAR RIGHT";
        }
    }

    private void frame(Canvas c, float l, float t, float rr, float b) {
        r.set(l, t, rr, b);
        p.setStyle(Paint.Style.FILL);
        p.setColor(PANEL);
        c.drawRect(r, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3.2f);
        p.setColor(CYAN_GLOW);
        c.drawRect(r, p);
        p.setStrokeWidth(1.4f);
        p.setColor(CYAN_DIM);
        c.drawRect(r, p);
    }

    private void panel(Canvas c, RectF b) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(PANEL);
        c.drawRect(b, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3.2f);
        p.setColor(CYAN_GLOW);
        c.drawRect(b, p);
        p.setStrokeWidth(1.4f);
        p.setColor(CYAN_DIM);
        c.drawRect(b, p);
    }

    private void label(Canvas c, String s, float cx, float baseline) {
        pText.setColor(GREY);
        pText.setTextSize(H * 0.045f);
        pText.setTextAlign(Paint.Align.CENTER);
        c.drawText(s, cx, baseline, pText);
    }

    /** two-pass glow: wide and faint, then narrow and bright. Cheaper than BlurMaskFilter. */
    private void glowPath(Canvas c, Path pathIn, int color, float width) {
        p.setStyle(Paint.Style.STROKE);
        p.setColor((color & 0x00FFFFFF) | 0x40000000);
        p.setStrokeWidth(width);
        c.drawPath(pathIn, p);
        p.setColor(color);
        p.setStrokeWidth(width * 0.28f);
        c.drawPath(pathIn, p);
    }

    private int dashes() { buf[0] = '-'; buf[1] = '-'; return 2; }

    /** format into the shared char[]; no allocation, so it is safe to call from onDraw */
    private int fmt(float value, int dec) {
        long unit = 1;
        for (int i = 0; i < dec; i++) unit *= 10;
        long sc = Math.round((double) value * unit);
        boolean neg = sc < 0;
        if (neg) sc = -sc;
        int end = buf.length;
        for (int i = 0; i < dec; i++) { buf[--end] = (char) ('0' + (sc % 10)); sc /= 10; }
        if (dec > 0) buf[--end] = '.';
        if (sc == 0) buf[--end] = '0';
        else while (sc > 0) { buf[--end] = (char) ('0' + (sc % 10)); sc /= 10; }
        if (neg) buf[--end] = '-';
        int n = buf.length - end;
        System.arraycopy(buf, end, buf, 0, n);
        return n;
    }
}
