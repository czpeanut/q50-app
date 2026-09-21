package com.appgarage.dash;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Live signal watcher for the VQ35HR Hybrid, with three jobs:
 *
 *  1. LATCH min/max per signal. The head unit cannot export anything and the only record
 *     keeping available in the car is a camera, but discharge/regen testing has to be done
 *     while driving. Latching means you drive the test, park, and then read the extremes.
 *
 *  2. ANSWER "does type 26 VS_ID_REGENERATION actually carry data?". getSensorList() proved
 *     the signal is declared; only a live update count proves it is fed. The watch panel
 *     therefore shows the event count next to every value, and distinguishes "not present"
 *     (absent from getSensorList) from "no data yet" (declared but never delivered).
 *
 *  3. PROBE storage, then record CSV. The sensors' maximumRange fields are demonstrably
 *     placeholders on this unit (types 12/13/16/32 all report 879.0), so every scaling
 *     factor has to be re-derived from observed raw values. That is spreadsheet work, not
 *     staring-at-the-dashboard work, so if any directory turns out to be writable this
 *     screen logs every signal to CSV for analysis at home.
 *
 * API 10 notes: no anonymous inner classes anywhere in this project -- build-tools 34's d8
 * crashes on them -- so the Activity itself implements the listener, the tick Runnable and
 * the click handler. UI refresh is 4 Hz over plain widgets, which the sensor list screen
 * already proved the unit renders fine.
 */
public class SensorWatchActivity extends Activity
        implements SensorEventListener, Runnable, View.OnClickListener {

    private static final int N = 64;                // this unit's types run 12..50
    private static final int TICK_MS = 250;         // 4 Hz UI refresh and CSV row rate
    private static final int SENSOR_US = 100000;    // request ~10 Hz delivery
    private static final long FLUSH_MS = 1000;      // the car can cut power at any moment
    private static final long MAX_ROWS = 400000;    // ~28 h at 4 Hz; a forgotten recording
                                                    // must not be able to fill the unit

    /**
     * The signals with open questions, shown large at the top.
     *
     * Confirmed on-car so far: 17 is km/h directly (so maximumRange 655340 was meaningless,
     * as were all the other max fields), and 25 is degrees directly, +-390 at full lock, with
     * one decimal -- not the 0.1-degree units the resolution field suggested.
     *
     * 26 stays on the list only so a second look costs nothing while driving; it delivered no
     * events at all on the first attempt, which leaves nothing in the inventory carrying
     * hybrid state. 28 and the two G axes are still untested.
     */
    private static final int[] WATCH = { 13, 17, 25, 26, 28, 23, 24, 20, 21 };
    private static final String[] WATCH_LABEL = {
        "13 RPM", "17 SPEED", "25 STEER", "26 REGEN", "28 ECO_MODE",
        "23 ACCEL", "24 BRAKE", "20 G_LAT", "21 G_LONG"
    };

    /** engine considered off below this; the hybrid sits at a true 0 rpm in EV drive */
    private static final float EV_RPM = 50f;
    private static final float EV_KMH = 3f;

    private final float[] now = new float[N];
    private final float[] lo = new float[N];
    private final float[] hi = new float[N];
    private final int[] hits = new int[N];
    private final String[] name = new String[N];
    private int[] types = new int[0];               // types present, ascending

    private SensorManager sm;
    private final Handler handler = new Handler();
    private final StringBuilder sb = new StringBuilder(8192);

    private TextView bigTv, allTv, statusTv;
    private Button resetBtn, recBtn, diagBtn, listBtn;

    private boolean showDiag;
    private String diagText = "";
    private final List<String> probeLog = new ArrayList<String>();
    private final List<File> writable = new ArrayList<File>();
    private BufferedWriter writer;
    private File logFile;
    private long rows, lastFlush, started;
    private String note = "";

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        resetBtn = addButton(bar, "RESET");
        recBtn = addButton(bar, "REC");
        diagBtn = addButton(bar, "STORAGE?");
        listBtn = addButton(bar, "LIST");
        root.addView(bar, wrap());

        statusTv = newText(10, 0xFFFFB020);         // amber: unverified / diagnostic
        root.addView(statusTv, wrap());

        bigTv = newText(17, 0xFF39C0FF);
        root.addView(bigTv, wrap());

        allTv = newText(11, 0xFFE6EDF3);
        ScrollView sv = new ScrollView(this);
        sv.addView(allTv);
        root.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.FILL_PARENT, 0, 1f));

        setContentView(root);

        sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        collectTypes();
        resetLatch();
        probeStorage();
        started = System.currentTimeMillis();
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerAll();
        handler.removeCallbacks(this);
        handler.post(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(this);
        try { if (sm != null) sm.unregisterListener(this); } catch (Throwable ignored) {}
        // close the log here: leaving the screen (or the unit powering down) must not be
        // able to lose buffered rows
        if (writer != null) stopRecording("paused");
    }

    // ---------------------------------------------------------------- sensors

    private void collectTypes() {
        if (sm == null) { note = "SENSOR_SERVICE = null"; return; }
        List<Sensor> all;
        try { all = sm.getSensorList(Sensor.TYPE_ALL); }
        catch (Throwable t) { note = "getSensorList failed: " + t; return; }
        if (all == null) { note = "getSensorList returned null"; return; }

        int n = 0;
        int[] tmp = new int[all.size()];
        for (int i = 0; i < all.size(); i++) {
            try {
                Sensor s = all.get(i);
                int t = s.getType();
                if (t < 0 || t >= N) continue;      // out of our array range; ignore
                name[t] = shortName(s.getName());
                tmp[n++] = t;
            } catch (Throwable ignored) {}
        }
        types = new int[n];
        System.arraycopy(tmp, 0, types, 0, n);
        // ascending by type (insertion sort; ~39 entries, and no Comparator -> no d8 crash)
        for (int i = 1; i < types.length; i++) {
            int key = types[i], j = i - 1;
            while (j >= 0 && types[j] > key) { types[j + 1] = types[j]; j--; }
            types[j + 1] = key;
        }
    }

    private void registerAll() {
        if (sm == null) return;
        for (int i = 0; i < types.length; i++) {
            try {
                Sensor s = sm.getDefaultSensor(types[i]);
                if (s != null) sm.registerListener(this, s, SENSOR_US);
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        try {
            int t = e.sensor.getType();
            if (t < 0 || t >= N) return;
            float v = (e.values != null && e.values.length > 0) ? e.values[0] : 0f;
            now[t] = v;
            if (hits[t] == 0) { lo[t] = v; hi[t] = v; }
            else { if (v < lo[t]) lo[t] = v; if (v > hi[t]) hi[t] = v; }
            hits[t]++;
        } catch (Throwable ignored) {}
    }

    @Override public void onAccuracyChanged(Sensor s, int a) {}

    private void resetLatch() {
        for (int i = 0; i < N; i++) { lo[i] = 0f; hi[i] = 0f; hits[i] = 0; }
    }

    // ---------------------------------------------------------------- tick / render

    public void run() {
        try { render(); } catch (Throwable ignored) {}
        try { if (writer != null) writeRow(); } catch (Throwable t) { stopRecording("write error: " + t); }
        handler.postDelayed(this, TICK_MS);
    }

    private void render() {
        sb.setLength(0);
        sb.append(evLine()).append('\n');
        for (int i = 0; i < WATCH.length; i++) appendRow(WATCH[i], WATCH_LABEL[i], 13);
        bigTv.setText(sb.toString());

        if (showDiag) {
            allTv.setText(diagText);
        } else {
            sb.setLength(0);
            sb.append(types.length).append(" signals   elapsed ")
              .append((System.currentTimeMillis() - started) / 1000).append(" s\n");
            for (int i = 0; i < types.length; i++) {
                int t = types[i];
                sb.append(t < 10 ? " " : "").append(t).append(' ');
                appendRow(t, name[t] == null ? "?" : name[t], 26);
            }
            allTv.setText(sb.toString());
        }

        statusTv.setText(status());
    }

    /**
     * Derived EV-drive indicator: rolling with the engine at a standstill is the one piece of
     * hybrid state still reachable, now that nothing in the inventory reports the battery.
     * It needs no new signal -- types 13 and 17 are both confirmed.
     */
    private String evLine() {
        if (hits[13] == 0 || hits[17] == 0) return "now / min / max / updates";
        boolean moving = now[17] > EV_KMH;
        boolean engineOff = now[13] < EV_RPM;
        if (moving && engineOff) return "now / min / max / updates      >>> EV DRIVE <<<";
        if (moving) return "now / min / max / updates      engine running";
        return "now / min / max / updates      stationary";
    }

    /** one "label now min max n=" row, columns aligned from the start of the line */
    private void appendRow(int t, String label, int valueCol) {
        int start = sb.length();
        sb.append(label);
        pad(start, valueCol);
        if (name[t] == null) {
            sb.append("not present in getSensorList");
        } else if (hits[t] == 0) {
            sb.append("no data yet");
        } else {
            appendNum(now[t]);
            pad(start, valueCol + 13);
            appendNum(lo[t]);
            pad(start, valueCol + 26);
            appendNum(hi[t]);
            pad(start, valueCol + 39);
            sb.append("n=").append(hits[t]);
        }
        sb.append('\n');
    }

    private void pad(int lineStart, int col) {
        while (sb.length() - lineStart < col) sb.append(' ');
    }

    /**
     * Number append with adaptive precision; avoids String.format on Dalvik.
     * Types 19 and 23 report resolution 0.001, so two decimals would flatten exactly the
     * signals that need calibrating -- small values keep 3 decimals. Large ones drop to 1 so
     * the columns stay narrow (type 17 ranges to 655340).
     */
    private void appendNum(float v) {
        if (Float.isNaN(v) || Float.isInfinite(v)) { sb.append("n/a"); return; }
        long unit = (Math.abs(v) >= 1000f) ? 10L : 1000L;
        long scaled = Math.round((double) v * unit);
        // a tiny negative must not print as a bare 0.000 -- on type 26 the sign is the
        // entire point of the measurement
        if (v < 0f && scaled == 0L) sb.append('-');
        if (scaled < 0) { sb.append('-'); scaled = -scaled; }
        sb.append(scaled / unit).append('.');
        long fp = scaled % unit;
        for (long p = unit / 10; p > 1; p /= 10) { if (fp < p) sb.append('0'); }
        sb.append(fp);
    }

    private String status() {
        if (note.length() > 0) return note;
        if (writer != null) return "REC -> " + logFile.getAbsolutePath() + "   rows=" + rows;
        if (writable.isEmpty()) return "WRITE TEST: nothing writable -- press STORAGE? for the reason per path";
        StringBuilder s = new StringBuilder("WRITE TEST OK: ");
        for (int i = 0; i < writable.size(); i++) {
            if (i > 0) s.append("  |  ");
            s.append(writable.get(i).getAbsolutePath());
        }
        return s.toString();
    }

    // ---------------------------------------------------------------- storage

    /**
     * Probe everything that could hold a log file, and -- unlike the first attempt -- record
     * WHY each candidate failed. Swallowing the exceptions turned "no writable directory"
     * into a dead end: getFilesDir() needs no permission and should never fail, so the
     * failure itself is the interesting part.
     *
     * Guessing USB mount paths was also the wrong approach. /proc/mounts says where this
     * firmware actually mounts things, so every mount point it lists gets probed too.
     */
    private void probeStorage() {
        writable.clear();
        probeLog.clear();
        try {
            probeLog.add("externalStorageState = " + Environment.getExternalStorageState());
        } catch (Throwable t) { probeLog.add("externalStorageState threw " + t); }

        probe(getFilesDir(), "getFilesDir");
        try { probe(Environment.getExternalStorageDirectory(), "externalStorageDir"); }
        catch (Throwable t) { probeLog.add("externalStorageDir threw " + t); }

        String[] paths = {
            "/sdcard", "/mnt/sdcard", "/mnt/usb", "/mnt/usbdisk", "/mnt/udisk",
            "/mnt/usb_storage", "/mnt/usbhost1", "/mnt/sda1", "/mnt/external_sd",
            "/storage/usb", "/data/local/tmp", "/cache"
        };
        for (int i = 0; i < paths.length; i++) probe(new File(paths[i]), "guess");

        List<String> mounts = readMounts();
        for (int i = 0; i < mounts.size(); i++) {
            String m = mounts.get(i);
            // /dev, /sys and friends accept a file and are still nowhere to put a log
            if (m.startsWith("/dev") || m.startsWith("/sys") || m.startsWith("/proc")
                    || m.startsWith("/run") || m.equals("/")) continue;
            probe(new File(m), "mount");
        }
    }

    /** mount points from /proc/mounts, minus the pseudo-filesystems that can never hold a file */
    private List<String> readMounts() {
        List<String> out = new ArrayList<String>();
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader("/proc/mounts"));
            String line;
            while ((line = r.readLine()) != null && out.size() < 64) {
                String[] f = line.split(" ");
                if (f.length < 3) continue;
                String point = f[1], type = f[2];
                if (type.equals("proc") || type.equals("sysfs") || type.equals("devpts")
                        || type.equals("cgroup") || type.equals("debugfs")
                        || type.equals("usbfs") || type.equals("rootfs")) continue;
                out.add(point);
            }
        } catch (Throwable t) {
            probeLog.add("/proc/mounts unreadable: " + t);
        } finally {
            try { if (r != null) r.close(); } catch (Throwable ignored) {}
        }
        return out;
    }

    private void probe(File dir, String how) {
        if (dir == null) { probeLog.add(how + ": null"); return; }
        String path = dir.getAbsolutePath();
        try {
            String canon = dir.getCanonicalPath();
            for (int i = 0; i < writable.size(); i++) {
                if (writable.get(i).getCanonicalPath().equals(canon)) return;   // already have it
            }
            if (!dir.isDirectory()) {
                if (!how.equals("guess")) probeLog.add("NO  " + path + "  (not a directory)");
                return;
            }
            File p = new File(dir, "dash_wtest.tmp");
            FileWriter w = new FileWriter(p);
            w.write("x");
            w.close();
            boolean ok = p.exists() && p.length() > 0;
            p.delete();
            if (ok) { writable.add(dir); probeLog.add("OK  " + path + "  [" + how + "]"); }
            else probeLog.add("NO  " + path + "  (wrote nothing)");
        } catch (Throwable t) {
            probeLog.add("NO  " + path + "  " + t);
        }
    }

    private void buildDiag() {
        StringBuilder d = new StringBuilder(4096);
        d.append("STORAGE PROBE -- every candidate and why it failed\n");
        d.append("if nothing here is writable, CSV logging is impossible on this unit\n\n");
        for (int i = 0; i < probeLog.size(); i++) d.append(probeLog.get(i)).append('\n');
        File pick = pickLogDir();
        d.append("\nREC would write to: ").append(pick == null ? "nowhere" : pick.getAbsolutePath()).append('\n');
        d.append("\n/proc/mounts\n");
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader("/proc/mounts"));
            String line;
            int n = 0;
            while ((line = r.readLine()) != null && n++ < 60) d.append(line).append('\n');
        } catch (Throwable t) {
            d.append("unreadable: ").append(t).append('\n');
        } finally {
            try { if (r != null) r.close(); } catch (Throwable ignored) {}
        }
        diagText = d.toString();
    }

    /** prefer removable media, so the CSV can just be carried indoors on the stick */
    private File pickLogDir() {
        File best = null;
        int bestScore = -1;
        for (int i = 0; i < writable.size(); i++) {
            File f = writable.get(i);
            int score = scoreDir(f.getAbsolutePath());
            if (score > bestScore) { bestScore = score; best = f; }
        }
        return best;
    }

    private static int scoreDir(String path) {
        String p = path.toLowerCase(Locale.US);
        if (p.indexOf("usb") >= 0 || p.indexOf("sda") >= 0 || p.indexOf("udisk") >= 0) return 4;
        if (p.indexOf("sdcard") >= 0 || p.indexOf("storage") >= 0 || p.indexOf("media") >= 0) return 3;
        if (p.indexOf("/data/data/") >= 0) return 2;        // the app's own directory
        if (p.indexOf("tmp") >= 0 || p.indexOf("cache") >= 0) return 1;  // survives, but not a reboot
        return 0;
    }

    private void startRecording() {
        File dir = pickLogDir();
        if (dir == null) { note = "cannot record: nowhere writable"; return; }
        try {
            logFile = new File(dir, "dash_" + System.currentTimeMillis() + ".csv");
            writer = new BufferedWriter(new FileWriter(logFile), 8192);
            writer.write("# AppGarage Dash raw signal log. values are RAW sensor values, unscaled.\n");
            writer.write("#");
            for (int i = 0; i < types.length; i++) {
                writer.write(" " + types[i] + "=" + name[types[i]]);
            }
            writer.write("\n");
            writer.write("ms");
            for (int i = 0; i < types.length; i++) writer.write("," + types[i]);
            writer.write("\n");
            rows = 0;
            lastFlush = System.currentTimeMillis();
            note = "";
            recBtn.setText("STOP");
        } catch (Throwable t) {
            writer = null;
            note = "cannot record: " + t;
        }
    }

    private void writeRow() throws Exception {
        if (rows >= MAX_ROWS) { stopRecording("row limit reached"); return; }
        long ms = System.currentTimeMillis();
        writer.write(Long.toString(ms - started));
        for (int i = 0; i < types.length; i++) {
            int t = types[i];
            writer.write(',');
            if (hits[t] > 0) writer.write(Float.toString(now[t]));
        }
        writer.write('\n');
        rows++;
        if (ms - lastFlush >= FLUSH_MS) { writer.flush(); lastFlush = ms; }
    }

    private void stopRecording(String why) {
        try { if (writer != null) { writer.flush(); writer.close(); } } catch (Throwable ignored) {}
        writer = null;
        recBtn.setText("REC");
        if (logFile != null) note = "saved " + rows + " rows -> " + logFile.getAbsolutePath()
                + (why.length() > 0 ? "  (" + why + ")" : "");
    }

    // ---------------------------------------------------------------- input

    public void onClick(View v) {
        if (v == resetBtn) {
            resetLatch();
            note = "";
        } else if (v == recBtn) {
            if (writer == null) startRecording(); else stopRecording("");
        } else if (v == diagBtn) {
            showDiag = !showDiag;
            if (showDiag) { probeStorage(); buildDiag(); }       // re-probe: a stick may have
            diagBtn.setText(showDiag ? "SIGNALS" : "STORAGE?");  // been plugged in since boot
        } else if (v == listBtn) {
            try { startActivity(new Intent(this, SensorListActivity.class)); }
            catch (Throwable t) { note = "cannot open list: " + t; }
        }
    }

    // ---------------------------------------------------------------- view helpers

    private Button addButton(LinearLayout parent, String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(12);
        b.setOnClickListener(this);
        parent.addView(b, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return b;
    }

    private TextView newText(int size, int color) {
        TextView tv = new TextView(this);
        tv.setTextSize(size);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextColor(color);
        tv.setPadding(6, 2, 6, 2);
        return tv;
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.FILL_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private static String shortName(String n) {
        if (n == null) return "?";
        return n.startsWith("VS_ID_") ? n.substring(6) : n;
    }
}
