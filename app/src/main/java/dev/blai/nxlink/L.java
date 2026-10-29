package dev.blai.nxlink;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Logger simple: logcat + buffer para la pantalla. */
public class L {
    public interface Sink { void onLine(String line); }

    private static final StringBuilder BUF = new StringBuilder();
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private static volatile Sink sink;

    public static void setSink(Sink s) { sink = s; }

    public static synchronized void d(String msg) {
        String line = FMT.format(new Date()) + "  " + msg;
        Log.d("NXLink", msg);
        BUF.append(line).append('\n');
        if (BUF.length() > 200000) BUF.delete(0, 50000);
        Sink s = sink;
        if (s != null) s.onLine(line);
    }

    public static void e(String msg, Throwable t) {
        d("ERROR " + msg + (t != null ? " :: " + t.getClass().getSimpleName() + ": " + t.getMessage() : ""));
        if (t != null) Log.e("NXLink", msg, t);
    }

    public static synchronized String dump() { return BUF.toString(); }
}
