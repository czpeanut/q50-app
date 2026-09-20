package com.appgarage.dash;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Sensor discovery screen — a plain dump of everything SensorManager reports on this head
 * unit. This is the reconnaissance step for the VQ35HR Hybrid: the vehicle CAN signals are
 * exposed as ordinary Android Sensors (vendor "Ygomi", names VS_ID_*), so simply listing
 * them answers "does this car publish an HV battery SOC / motor power signal at all?" —
 * no registerListener, no live values, no driving required.
 *
 * Deliberately independent of MainActivity / GaugeView: this activity only reads the static
 * sensor inventory and prints it, so nothing here can disturb the gauge screen.
 */
public class SensorListActivity extends Activity {

    /** Name fragments worth a second look while hunting for the hybrid-only signals. */
    private static final String[] HINTS = {
        "BATT", "SOC", "HV", "CHARGE", "MOTOR", "HYBRID", "ELEC", "STEER", "ANGLE", "MODE"
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        TextView tv = new TextView(this);
        tv.setTextSize(11);
        tv.setTypeface(Typeface.MONOSPACE);       // keeps the columns lined up
        tv.setTextColor(0xFFE6EDF3);
        tv.setPadding(6, 6, 6, 6);
        tv.setText(dumpSensors());

        // vertical scroll for the list, horizontal for over-long sensor names
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.addView(tv);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.BLACK);
        sv.addView(hs);
        setContentView(sv);
    }

    private CharSequence dumpSensors() {
        StringBuilder sb = new StringBuilder(4096);
        SensorManager sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sm == null) return "SENSOR_SERVICE = null";

        List<Sensor> all;
        try {
            all = sm.getSensorList(Sensor.TYPE_ALL);
        } catch (Throwable t) {
            return "getSensorList() failed: " + t;
        }
        if (all == null || all.isEmpty()) return "getSensorList(TYPE_ALL) returned nothing.";

        // Stable, readable order: ascending type number. Hand-rolled insertion sort rather
        // than Collections.sort(list, new Comparator<Sensor>(){...}) on purpose — build-tools
        // 34's d8 crashes on ANY anonymous inner class produced by a modern javac, so this
        // project keeps to named classes only. The list is ~50 entries; insertion sort is free.
        List<Sensor> sorted = new ArrayList<Sensor>(all);
        for (int i = 1; i < sorted.size(); i++) {
            Sensor key = sorted.get(i);
            int j = i - 1;
            while (j >= 0 && sorted.get(j).getType() > key.getType()) {
                sorted.set(j + 1, sorted.get(j));
                j--;
            }
            sorted.set(j + 1, key);
        }

        sb.append(sorted.size()).append(" sensors  (* = possible hybrid/steering signal)\n");
        sb.append("type | name | vendor | max | res\n");
        sb.append("--------------------------------\n");
        for (int i = 0; i < sorted.size(); i++) {
            Sensor s = sorted.get(i);
            // one hostile entry must not blank the whole screen — this is the screen whose
            // only job is to come up and show something on an unknown head unit
            try {
                String name = s.getName();
                sb.append(isHint(name) ? "* " : "  ")
                  .append(s.getType()).append(" | ")
                  .append(name).append(" | ")
                  .append(s.getVendor()).append(" | max=")
                  .append(s.getMaximumRange()).append(" res=")
                  .append(s.getResolution()).append('\n');
            } catch (Throwable t) {
                sb.append("  ?? | read failed: ").append(t).append('\n');
            }
        }
        return sb.toString();
    }

    private static boolean isHint(String name) {
        if (name == null) return false;
        String up = name.toUpperCase(Locale.US);   // locale-independent (tr: i -> dotted I)
        for (int i = 0; i < HINTS.length; i++) {
            if (up.indexOf(HINTS[i]) >= 0) return true;
        }
        return false;
    }
}
