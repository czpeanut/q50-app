package vtd.survey;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import dalvik.system.DexFile;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Set;

/**
 * A throwaway, read-only survey of what this head unit offers an App Garage app beyond the
 * vehicle sensors: which permissions exist, which other apps expose providers or services,
 * whether the Android layer has a network at all, and whether it can reach a phone.
 *
 * Nothing here talks to the vehicle bus. The only things it sends anywhere are TCP connection
 * attempts, and only when the network button is pressed.
 *
 * The screen is the only way anything leaves this unit, so the report is plain text sized to
 * be photographed. Same rules as the dash: API 10, and no anonymous classes -- d8 from
 * build-tools 34 crashes on any anonymous class produced by a modern javac.
 */
public class SurveyActivity extends Activity implements View.OnClickListener, SensorEventListener {

    private static final String CAN_READ = "com.ygomi.permission.IVI_CAN_READ";
    private static final int T_A = 46, T_B = 47;    // the two whose photos disagreed

    private final Handler ui = new Handler();
    private TextView live, link, report, net;
    // the "ivi" network type is the phone link; its state is watched live so a tethering toggle
    // on the phone shows up here within two seconds, with the moment it happened
    private final Poll poll = new Poll(this);
    private final List<String> linkLog = new ArrayList<String>();
    private String lastLink = "";
    private Button bScan, bNet;
    private SensorManager sm;
    private float v46 = Float.NaN, v47 = Float.NaN;
    private int n46, n47;
    private boolean probing;
    private String sensorErr;      // variant B may be refused the sensors outright; that IS a result

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF000000);

        // Only the button row is pinned. Everything else scrolls together: pinning the status
        // lines is what made the old watcher screen impossible to scroll.
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bScan = button("重新掃描 RESCAN");
        bNet = button("網路測試 NET TEST");
        bar.addView(bScan, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(bNet, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(bar);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(8, 4, 8, 24);
        live = text(0xFFFFD27A);
        link = text(0xFF9CFF9C);
        net = text(0xFF7AE0FF);
        report = text(0xFFE0E0E0);
        net.setText("[網路測試] 尚未執行。按上面的按鈕開始，約需 15 秒。\n"
                + "  請記下這次開機時「允許應用程式存取網路？」按的是「是」還是「否」。");
        body.addView(live);
        body.addView(link);
        body.addView(net);
        body.addView(report);

        ScrollView sv = new ScrollView(this);
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.FILL_PARENT, 0, 1f));
        setContentView(root);

        sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        scan();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sm == null) return;
        try {
            List<Sensor> all = sm.getSensorList(Sensor.TYPE_ALL);
            for (int i = 0; i < all.size(); i++) {
                Sensor s = all.get(i);
                if (s.getType() == T_A || s.getType() == T_B)
                    sm.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL);
            }
        } catch (Throwable t) { sensorErr = t.toString(); }
        showLive();
        ui.removeCallbacks(poll);
        ui.post(poll);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(poll);
        try { if (sm != null) sm.unregisterListener(this); } catch (Throwable ignored) { }
    }

    public void onSensorChanged(SensorEvent e) {
        int t = e.sensor.getType();
        if (t == T_A) { v46 = e.values[0]; n46++; }
        else if (t == T_B) { v47 = e.values[0]; n47++; }
        showLive();
    }

    public void onAccuracyChanged(Sensor s, int a) { }

    private void showLive() {
        if (sensorErr != null) { live.setText("[即時] 感測器註冊失敗 / sensor registration refused:\n  " + sensorErr); return; }
        live.setText("[即時] type 46 SELECT_PRESSURE_SUPPORT = " + num(v46) + "  n=" + n46
                + "\n[即時] type 47 RESET_TPMS             = " + num(v47) + "  n=" + n47);
    }

    public void onClick(View v) {
        if (v == bScan) scan();
        else if (v == bNet && !probing) {
            probing = true;
            net.setText("[網路測試] 進行中…約 15 秒");
            new Thread(new Probe(this)).start();
        }
    }

    // ---------------------------------------------------------------- report

    private void scan() {
        StringBuilder sb = new StringBuilder();
        long t0 = SystemClock.uptimeMillis();
        // the network prompt is answered once per boot, so every photo needs to say which boot
        sb.append("開機後 / since boot: ").append(SystemClock.elapsedRealtime() / 1000).append(" s\n");
        for (int i = 0; i < TITLES.length; i++) {
            section(sb, TITLES[i]);
            // one section failing must not cost the rest of the report
            try { part(i, sb); } catch (Throwable t) { sb.append("  FAILED: ").append(t).append('\n'); }
        }
        sb.append("\n-- 掃描耗時 ").append(SystemClock.uptimeMillis() - t0).append(" ms --\n");
        report.setText(sb.toString());
    }

    private static final String[] TITLES = {
        "0. 這個變體 / THIS VARIANT", "1. 顯示 / DISPLAY", "2. 車輛感測器 / SENSORS",
        "3. 網路介面 / NETWORK", "4. 藍牙 / BLUETOOTH", "5. 非 android 權限 / VENDOR PERMISSIONS",
        "6. 對外開放的元件 / EXPORTED COMPONENTS", "7. 已安裝套件 / PACKAGES", "8. 系統 / SYSTEM",
        "9. 系統服務 / BINDER SERVICES", "10. 共用函式庫 / SHARED LIBRARIES",
        "11. Ygomi 框架類別 / VENDOR FRAMEWORK CLASSES",
    };

    private void part(int i, StringBuilder sb) {
        switch (i) {
            case 0: variant(sb); break;
            case 1: display(sb); break;
            case 2: sensors(sb); break;
            case 3: network(sb); break;
            case 4: bluetooth(sb); break;
            case 5: permissions(sb); break;
            case 6: components(sb); break;
            case 7: packages(sb); break;
            case 8: system(sb); break;
            case 9: services(sb); break;
            case 10: libraries(sb); break;
            default: vendorClasses(sb); break;
        }
    }

    private void variant(StringBuilder sb) {
        sb.append("package  ").append(getPackageName()).append('\n');
        PackageManager pm = getPackageManager();
        boolean declared = false;
        try {
            PackageInfo me = pm.getPackageInfo(getPackageName(), PackageManager.GET_PERMISSIONS);
            if (me.requestedPermissions != null)
                for (int i = 0; i < me.requestedPermissions.length; i++)
                    if (CAN_READ.equals(me.requestedPermissions[i])) declared = true;
        } catch (Throwable t) { sb.append("  (getPackageInfo: ").append(t).append(")\n"); }
        sb.append("IVI_CAN_READ declared  ").append(declared ? "YES" : "NO").append('\n');
        perm(sb, CAN_READ);
        perm(sb, "android.permission.INTERNET");
        perm(sb, "android.permission.ACCESS_NETWORK_STATE");
        perm(sb, "android.permission.ACCESS_WIFI_STATE");
        perm(sb, "android.permission.BLUETOOTH");
    }

    private void perm(StringBuilder sb, String p) {
        int r = checkCallingOrSelfPermission(p);
        sb.append("  ").append(r == PackageManager.PERMISSION_GRANTED ? "GRANTED " : "DENIED  ")
          .append(p).append('\n');
    }

    private void display(StringBuilder sb) {
        try {
            Display d = getWindowManager().getDefaultDisplay();
            DisplayMetrics m = new DisplayMetrics();
            d.getMetrics(m);
            sb.append(d.getWidth()).append('x').append(d.getHeight())
              .append("  density=").append(m.density)
              .append("  dpi=").append(m.densityDpi)
              .append("  rotation=").append(d.getRotation())
              .append("  id=").append(d.getDisplayId()).append('\n');
        } catch (Throwable t) { sb.append("  ").append(t).append('\n'); }
        sb.append("（這個 app 出現在哪個螢幕，請拍照時一起拍進去）\n");
    }

    private void sensors(StringBuilder sb) {
        if (sm == null) { sb.append("  no SensorManager\n"); return; }
        List<Sensor> all = sm.getSensorList(Sensor.TYPE_ALL);
        int n = all.size();
        int[] types = new int[n];
        int otherVendor = 0;
        for (int i = 0; i < n; i++) {
            types[i] = all.get(i).getType();
            String vd = all.get(i).getVendor();
            if (vd == null || vd.indexOf("Ygomi") < 0) otherVendor++;
        }
        Arrays.sort(types);
        sb.append("count=").append(n);
        if (n > 0) sb.append("  types ").append(types[0]).append("..").append(types[n - 1]);
        sb.append("  non-Ygomi=").append(otherVendor).append('\n');
        StringBuilder gaps = new StringBuilder();
        for (int i = 1; i < n; i++)
            for (int t = types[i - 1] + 1; t < types[i]; t++) gaps.append(t).append(' ');
        sb.append("gaps: ").append(gaps.length() == 0 ? "none" : gaps.toString()).append('\n');
        for (int i = 0; i < n; i++) {
            Sensor s = all.get(i);
            if (s.getType() < 12 || s.getType() > 50 || s.getVendor() == null
                    || s.getVendor().indexOf("Ygomi") < 0)
                sb.append("  UNEXPECTED ").append(s.getType()).append(' ').append(s.getName())
                  .append(" / ").append(s.getVendor()).append('\n');
        }
    }

    private void network(StringBuilder sb) {
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            if (en == null) sb.append("  (no interfaces)\n");
            while (en != null && en.hasMoreElements()) {
                NetworkInterface ni = en.nextElement();
                sb.append("  ").append(ni.getName());
                try { sb.append(ni.isUp() ? "  UP" : "  down"); } catch (Throwable t) { sb.append("  ?"); }
                Enumeration<InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) sb.append("  ").append(as.nextElement().getHostAddress());
                sb.append('\n');
            }
        } catch (Throwable t) { sb.append("  interfaces: ").append(t).append('\n'); }

        sb.append("default gateways: ");
        List<String> gw = gateways();
        sb.append(gw.isEmpty() ? "none" : gw.toString()).append('\n');
        sb.append("dns: ").append(prop("net.dns1")).append(' ').append(prop("net.dns2")).append('\n');
        dump(sb, "/proc/net/route", 12);
        dump(sb, "/proc/net/arp", 12);

        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo act = cm.getActiveNetworkInfo();
            sb.append("active: ").append(act == null ? "none" : info(act)).append('\n');
            NetworkInfo[] all = cm.getAllNetworkInfo();
            for (int i = 0; all != null && i < all.length; i++)
                sb.append("  ").append(info(all[i])).append('\n');
        } catch (Throwable t) { sb.append("  connectivity: ").append(t).append('\n'); }

        try {
            WifiManager wm = (WifiManager) getSystemService(Context.WIFI_SERVICE);
            if (wm == null) sb.append("wifi: no service\n");
            else {
                sb.append("wifi: enabled=").append(wm.isWifiEnabled());
                WifiInfo wi = wm.getConnectionInfo();
                if (wi != null) sb.append("  ssid=").append(wi.getSSID()).append("  ip=").append(ip(wi.getIpAddress()));
                sb.append('\n');
            }
        } catch (Throwable t) { sb.append("wifi: ").append(t).append('\n'); }
    }

    private static String info(NetworkInfo n) {
        return n.getTypeName() + "/" + n.getSubtypeName() + " " + n.getState()
                + (n.isAvailable() ? " avail" : " unavail")
                + (n.isConnected() ? " CONNECTED" : "")
                + (n.getExtraInfo() != null ? " extra=" + n.getExtraInfo() : "")
                + (n.getReason() != null ? " reason=" + n.getReason() : "");
    }

    private void bluetooth(StringBuilder sb) {
        try {
            BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
            if (ba == null) { sb.append("adapter: NONE（Android 層看不到藍牙，可能由 host Linux 管）\n"); return; }
            sb.append("adapter: state=").append(ba.getState()).append(" enabled=").append(ba.isEnabled())
              .append(" name=").append(ba.getName()).append(" addr=").append(ba.getAddress()).append('\n');
            Set<BluetoothDevice> bonded = ba.getBondedDevices();
            if (bonded == null || bonded.isEmpty()) sb.append("  bonded: none\n");
            else {
                Object[] arr = bonded.toArray();
                for (int i = 0; i < arr.length; i++) {
                    BluetoothDevice d = (BluetoothDevice) arr[i];
                    sb.append("  bonded: ").append(d.getName()).append("  ").append(d.getAddress());
                    if (d.getBluetoothClass() != null)
                        sb.append("  class=0x").append(Integer.toHexString(d.getBluetoothClass().getDeviceClass()));
                    sb.append('\n');
                }
            }
        } catch (Throwable t) { sb.append("  ").append(t).append('\n'); }
    }

    /** every permission defined by any installed package outside the android.* namespace */
    private void permissions(StringBuilder sb) {
        List<String> lines = new ArrayList<String>();
        PackageManager pm = getPackageManager();
        List<PackageInfo> pkgs = pm.getInstalledPackages(0);
        for (int i = 0; i < pkgs.size(); i++) {
            String name = pkgs.get(i).packageName;
            try {
                PackageInfo pi = pm.getPackageInfo(name, PackageManager.GET_PERMISSIONS);
                if (pi.permissions == null) continue;
                for (int j = 0; j < pi.permissions.length; j++) {
                    PermissionInfo p = pi.permissions[j];
                    if (p.name.startsWith("android.")) continue;
                    lines.add(p.name + "  [" + level(p.protectionLevel) + "]  by " + name);
                }
            } catch (Throwable t) { lines.add("(" + name + ": " + t + ")"); }
        }
        Collections.sort(lines);
        if (lines.isEmpty()) sb.append("  none\n");
        for (int i = 0; i < lines.size(); i++) sb.append("  ").append(lines.get(i)).append('\n');
    }

    private static String level(int l) {
        switch (l & 0x0f) {
            case 0: return "normal";
            case 1: return "dangerous";
            case 2: return "signature";
            case 3: return "sig|system";
            default: return "level " + l;
        }
    }

    /** providers (all of them, with their guard permissions) and exported services/receivers */
    private void components(StringBuilder sb) {
        List<String> prov = new ArrayList<String>(), svc = new ArrayList<String>(), rcv = new ArrayList<String>();
        PackageManager pm = getPackageManager();
        List<PackageInfo> pkgs = pm.getInstalledPackages(0);
        int flags = PackageManager.GET_PROVIDERS | PackageManager.GET_SERVICES | PackageManager.GET_RECEIVERS;
        for (int i = 0; i < pkgs.size(); i++) {
            String name = pkgs.get(i).packageName;
            PackageInfo pi;
            try { pi = pm.getPackageInfo(name, flags); }
            catch (Throwable t) { prov.add("(" + name + ": " + t + ")"); continue; }
            if (pi.providers != null)
                for (int j = 0; j < pi.providers.length; j++) {
                    ProviderInfo p = pi.providers[j];
                    prov.add(p.authority + (p.exported ? "  EXPORTED" : "")
                            + (p.readPermission != null ? "  r=" + p.readPermission : "")
                            + "  (" + name + ")");
                }
            if (pi.services != null)
                for (int j = 0; j < pi.services.length; j++) {
                    ServiceInfo s = pi.services[j];
                    if (!s.exported) continue;
                    svc.add(s.name + (s.permission != null ? "  p=" + s.permission : ""));
                }
            if (pi.receivers != null)
                for (int j = 0; j < pi.receivers.length; j++) {
                    ActivityInfo r = pi.receivers[j];
                    if (!r.exported || r.name.startsWith("com.android.") || r.name.startsWith("android."))
                        continue;
                    rcv.add(r.name + (r.permission != null ? "  p=" + r.permission : ""));
                }
        }
        list(sb, "providers", prov);
        list(sb, "exported services", svc);
        list(sb, "exported receivers (non-AOSP)", rcv);
    }

    private static void list(StringBuilder sb, String title, List<String> l) {
        Collections.sort(l);
        sb.append(title).append(" (").append(l.size()).append("):\n");
        for (int i = 0; i < l.size(); i++) sb.append("  ").append(l.get(i)).append('\n');
    }

    /** one line per package; vendor permissions each package USES, which hints at what they read */
    private void packages(StringBuilder sb) {
        List<String> lines = new ArrayList<String>();
        PackageManager pm = getPackageManager();
        List<PackageInfo> pkgs = pm.getInstalledPackages(0);
        for (int i = 0; i < pkgs.size(); i++) {
            String name = pkgs.get(i).packageName;
            StringBuilder l = new StringBuilder();
            try {
                PackageInfo pi = pm.getPackageInfo(name, PackageManager.GET_PERMISSIONS);
                boolean sys = pi.applicationInfo != null
                        && (pi.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                l.append(sys ? "[S] " : "[U] ").append(name).append(' ').append(pi.versionName);
                if (pi.requestedPermissions != null)
                    for (int j = 0; j < pi.requestedPermissions.length; j++) {
                        String p = pi.requestedPermissions[j];
                        if (!p.startsWith("android.")) l.append("\n      uses ").append(p);
                    }
            } catch (Throwable t) { l.append("(").append(name).append(": ").append(t).append(")"); }
            lines.add(l.toString());
        }
        Collections.sort(lines);
        sb.append("count=").append(lines.size()).append("  ([S]=system  [U]=user)\n");
        for (int i = 0; i < lines.size(); i++) sb.append("  ").append(lines.get(i)).append('\n');
    }

    private void system(StringBuilder sb) {
        sb.append("Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT)
          .append(")  ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
          .append("  hw=").append(Build.HARDWARE).append('\n');
        sb.append(Build.FINGERPRINT).append('\n');
        // only lines that could name the platform; the full file is long and mostly AOSP noise
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader("/system/build.prop"));
            String line;
            while ((line = r.readLine()) != null) {
                String lo = line.toLowerCase();
                if (lo.indexOf("ygomi") >= 0 || lo.indexOf("ivi") >= 0 || lo.indexOf("nissan") >= 0
                        || lo.indexOf("infiniti") >= 0 || lo.indexOf("airbiq") >= 0
                        || lo.indexOf("display") >= 0 || lo.indexOf("tether") >= 0)
                    sb.append("  ").append(line).append('\n');
            }
        } catch (Throwable t) { sb.append("  build.prop: ").append(t).append('\n'); }
        finally { if (r != null) try { r.close(); } catch (Throwable ignored) { } }
    }

    // ---------------------------------------------------------------- phone link, live

    /** every 2 s while visible: the ivi network, the interfaces, the default route */
    private static final class Poll implements Runnable {
        private final SurveyActivity a;
        Poll(SurveyActivity a) { this.a = a; }
        public void run() {
            a.watchLink();
            a.ui.postDelayed(this, 2000);
        }
    }

    private void watchLink() {
        StringBuilder now = new StringBuilder();
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo[] all = cm.getAllNetworkInfo();
            boolean found = false;
            for (int i = 0; all != null && i < all.length; i++)
                if ("ivi".equalsIgnoreCase(all[i].getTypeName())) { now.append(info(all[i])); found = true; }
            if (!found) now.append("ivi type not present");
        } catch (Throwable t) { now.append(t); }
        now.append("\n  if:");
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            while (en != null && en.hasMoreElements()) {
                NetworkInterface ni = en.nextElement();
                if ("lo".equals(ni.getName())) continue;
                now.append(' ').append(ni.getName());
                Enumeration<InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) now.append('=').append(as.nextElement().getHostAddress());
            }
        } catch (Throwable t) { now.append(' ').append(t); }
        List<String> gw = gateways();
        now.append("\n  gw: ").append(gw.isEmpty() ? "none" : gw.toString());

        String cur = now.toString();
        if (!cur.equals(lastLink)) {
            lastLink = cur;
            linkLog.add(0, "  @" + SystemClock.elapsedRealtime() / 1000 + "s  " + cur.replace("\n  ", " | "));
            while (linkLog.size() > 6) linkLog.remove(linkLog.size() - 1);
        }
        StringBuilder out = new StringBuilder("[手機連線 即時] ivi: ").append(cur)
                .append("\n  變化紀錄 / changes (newest first):\n");
        for (int i = 0; i < linkLog.size(); i++) out.append(linkLog.get(i)).append('\n');
        link.setText(out.toString());
    }

    // ---------------------------------------------------------------- platform internals, read only

    /** binder services registered with the service manager; hidden API, reached by reflection */
    private void services(StringBuilder sb) {
        Class<?> c;
        String[] names;
        try {
            c = Class.forName("android.os.ServiceManager");
            names = (String[]) c.getMethod("listServices").invoke(null);
        } catch (Throwable t) { sb.append("  ").append(t).append('\n'); return; }
        if (names == null) { sb.append("  none\n"); return; }
        List<String> l = new ArrayList<String>(Arrays.asList(names));
        Collections.sort(l);
        sb.append("count=").append(l.size()).append('\n');
        StringBuilder row = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {           // several to a line: most are AOSP noise
            if (row.length() + l.get(i).length() > 90) { sb.append("  ").append(row).append('\n'); row.setLength(0); }
            row.append(l.get(i)).append("  ");
        }
        if (row.length() > 0) sb.append("  ").append(row).append('\n');
    }

    /** shared libraries and features the platform declares; a vendor SDK would be listed here */
    private void libraries(StringBuilder sb) {
        File[] xml = new File("/system/etc/permissions").listFiles();
        if (xml == null) { sb.append("  /system/etc/permissions unreadable\n"); return; }
        Arrays.sort(xml);
        for (int i = 0; i < xml.length; i++) {
            sb.append("  ").append(xml[i].getName()).append('\n');
            BufferedReader r = null;
            try {
                r = new BufferedReader(new FileReader(xml[i]));
                String line;
                while ((line = r.readLine()) != null) {
                    String t = line.trim();
                    if (t.startsWith("<library") || t.startsWith("<feature")) sb.append("      ").append(t).append('\n');
                }
            } catch (Throwable t) { sb.append("      ").append(t).append('\n'); }
            finally { if (r != null) try { r.close(); } catch (Throwable ignored) { } }
        }
        sb.append("BOOTCLASSPATH=").append(System.getenv("BOOTCLASSPATH")).append('\n');
    }

    /**
     * Class names in every jar under /system/framework, filtered to the vendors, and the public
     * methods of the ones whose names suggest an API worth reading. Classes are looked up with
     * initialize=false, so no static initializer runs and nothing binds to any service.
     */
    private void vendorClasses(StringBuilder sb) {
        File[] jars = new File("/system/framework").listFiles();
        if (jars == null) { sb.append("  /system/framework unreadable\n"); return; }
        Arrays.sort(jars);
        List<String> hits = new ArrayList<String>();
        for (int i = 0; i < jars.length; i++) {
            String path = jars[i].getPath();
            if (!path.endsWith(".jar")) continue;
            int n = 0;
            try {
                DexFile dx = new DexFile(path);
                Enumeration<String> en = dx.entries();
                while (en.hasMoreElements()) {
                    String c = en.nextElement();
                    if (c.indexOf('$') >= 0) continue;
                    if (c.startsWith("com.ygomi.") || c.startsWith("com.connexis.")
                            || c.indexOf(".ivi.") >= 0 || c.indexOf("nissan") >= 0
                            || c.indexOf("infiniti") >= 0) { hits.add(c); n++; }
                }
                dx.close();
            } catch (Throwable t) { sb.append("  ").append(jars[i].getName()).append(": ").append(t).append('\n'); continue; }
            if (n > 0) sb.append("  ").append(jars[i].getName()).append(": ").append(n).append(" vendor classes\n");
        }
        Collections.sort(hits);
        sb.append("vendor classes (").append(hits.size()).append("):\n");
        for (int i = 0; i < hits.size() && i < 300; i++) sb.append("  ").append(hits.get(i)).append('\n');

        int shown = 0;
        ClassLoader cl = getClassLoader();
        for (int i = 0; i < hits.size() && shown < 30; i++) {
            String name = hits.get(i);
            String simple = name.substring(name.lastIndexOf('.') + 1);
            if (!simple.matches(".*(Manager|Navi|Display|Vehicle|Battery|Hybrid|Energy|Connect|Network|Tether|Phone|Can|Sensor).*"))
                continue;
            Class<?> k;
            try { k = Class.forName(name, false, cl); }
            catch (Throwable t) { sb.append("  [not loadable here] ").append(name).append('\n'); continue; }
            shown++;
            sb.append("  ").append(name).append(":\n");
            Method[] ms;
            try { ms = k.getDeclaredMethods(); } catch (Throwable t) { sb.append("      ").append(t).append('\n'); continue; }
            int m = 0;
            for (int j = 0; j < ms.length && m < 25; j++) {
                if (!Modifier.isPublic(ms[j].getModifiers())) continue;
                m++;
                sb.append("      ").append(ms[j].getReturnType().getSimpleName()).append(' ').append(ms[j].getName()).append('(');
                Class<?>[] ps = ms[j].getParameterTypes();
                for (int q = 0; q < ps.length; q++) sb.append(q > 0 ? ", " : "").append(ps[q].getSimpleName());
                sb.append(")\n");
            }
        }
    }

    // ---------------------------------------------------------------- network probe

    /**
     * Connection attempts, nothing more. A refused connection is as good as an open one for
     * this purpose: something answered, so the route exists. A timeout or "unreachable" means
     * there is no path. Runs off the UI thread; Dalvik on this unit will not forgive a 15 s
     * stall on the main thread.
     */
    private static final class Probe implements Runnable {
        private final SurveyActivity a;
        Probe(SurveyActivity a) { this.a = a; }

        public void run() {
            StringBuilder sb = new StringBuilder("[網路測試]  開機後 ")
                .append(SystemClock.elapsedRealtime() / 1000).append(" 秒\n");
            List<String> gw = gateways();
            for (int i = 0; i < gw.size(); i++) {
                sb.append(line(gw.get(i), 53)).append(line(gw.get(i), 80));
            }
            // the usual addresses of a phone sharing its connection, in case no route is listed
            sb.append(line("192.168.44.1", 53));   // Android, Bluetooth tethering
            sb.append(line("192.168.43.1", 53));   // Android, Wi-Fi hotspot
            sb.append(line("172.20.10.1", 53));    // iPhone, Personal Hotspot
            // Last time 8.8.8.8:53 was refused in 9 ms with no default route, which only
            // something local could do. Two more addresses and ports show whether that is a
            // blanket rule.
            sb.append(line("8.8.8.8", 53));
            sb.append(line("8.8.8.8", 80));
            sb.append(line("1.1.1.1", 443));
            // and www.google.com resolved to 224.0.0.1 in 1 ms: check whether every name does
            dns(sb, "www.google.com");
            dns(sb, "example.com");
            a.ui.post(new Deliver(a, sb.toString()));
        }

        private static void dns(StringBuilder sb, String name) {
            long t0 = SystemClock.uptimeMillis();
            sb.append("  DNS ").append(name).append(" -> ");
            try {
                InetAddress[] all = InetAddress.getAllByName(name);
                for (int i = 0; i < all.length; i++) sb.append(all[i].getHostAddress()).append(' ');
            } catch (Throwable t) { sb.append(t); }
            sb.append(" (").append(SystemClock.uptimeMillis() - t0).append(" ms)\n");
        }

        private static String line(String host, int port) {
            return "  " + host + ":" + port + "  " + probe(host, port) + "\n";
        }
    }

    private static final class Deliver implements Runnable {
        private final SurveyActivity a;
        private final String s;
        Deliver(SurveyActivity a, String s) { this.a = a; this.s = s; }
        public void run() { a.net.setText(s); a.probing = false; }
    }

    static String probe(String host, int port) {
        Socket s = new Socket();
        long t0 = SystemClock.uptimeMillis();
        try {
            s.connect(new InetSocketAddress(host, port), 1500);
            return "OPEN  " + (SystemClock.uptimeMillis() - t0) + " ms";
        } catch (SocketTimeoutException e) {
            return "TIMEOUT";
        } catch (ConnectException e) {
            String m = String.valueOf(e.getMessage());
            long ms = SystemClock.uptimeMillis() - t0;
            if (m.indexOf("refused") >= 0 || m.indexOf("ECONNREFUSED") >= 0)
                return "REFUSED " + ms + " ms  (host answered: route exists)";
            if (m.indexOf("nreachable") >= 0) return "NO ROUTE  " + m;
            return "CONNECT ERR  " + m;
        } catch (Throwable e) {
            return e.getClass().getSimpleName() + "  " + e.getMessage();
        } finally {
            try { s.close(); } catch (Throwable ignored) { }
        }
    }

    // ---------------------------------------------------------------- helpers

    /** default-route gateways from /proc/net/route, which stores them little-endian in hex */
    static List<String> gateways() {
        List<String> out = new ArrayList<String>();
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader("/proc/net/route"));
            String line = r.readLine();                     // header
            while ((line = r.readLine()) != null) {
                String[] f = line.trim().split("\\s+");
                if (f.length < 3 || !"00000000".equals(f[1])) continue;
                String g = ip((int) Long.parseLong(f[2], 16));
                if (!"0.0.0.0".equals(g) && !out.contains(g)) out.add(g);
            }
        } catch (Throwable ignored) {
        } finally { if (r != null) try { r.close(); } catch (Throwable ignored) { } }
        return out;
    }

    static String ip(int le) {
        return (le & 0xff) + "." + ((le >> 8) & 0xff) + "." + ((le >> 16) & 0xff) + "." + ((le >> 24) & 0xff);
    }

    private static void dump(StringBuilder sb, String path, int max) {
        sb.append(path).append(":\n");
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(path));
            String line;
            int n = 0;
            while ((line = r.readLine()) != null && n++ < max) sb.append("  ").append(line).append('\n');
        } catch (Throwable t) { sb.append("  ").append(t).append('\n'); }
        finally { if (r != null) try { r.close(); } catch (Throwable ignored) { } }
    }

    /** android.os.SystemProperties is hidden on API 10, so reach it by reflection */
    private static String prop(String key) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            Object v = c.getMethod("get", String.class).invoke(null, key);
            String s = v == null ? "" : v.toString();
            return s.length() == 0 ? "-" : s;
        } catch (Throwable t) { return "?"; }
    }

    private static String num(float f) {
        return Float.isNaN(f) ? "no data yet" : String.valueOf(f);
    }

    private static void section(StringBuilder sb, String title) {
        sb.append("\n==== ").append(title).append(" ====\n");
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(15);
        b.setOnClickListener(this);
        return b;
    }

    private TextView text(int color) {
        TextView t = new TextView(this);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextSize(12);
        t.setTextColor(color);
        return t;
    }
}
