package dev.blai.nxlink;

import java.util.ArrayList;
import java.util.List;

/** Un elemento del ContentDirectory: carpeta (container) o fichero (item). */
public class Entry {
    public boolean container;
    public String id = "";
    public String title = "";
    public int childCount = -1;
    public final List<Res> resources = new ArrayList<>();

    public static class Res {
        public String url = "";
        public long size = -1;
        public String protocolInfo = "";
    }

    /** El recurso de mayor tamanyo = el original. */
    public Res bestRes() {
        Res best = null;
        for (Res r : resources) {
            if (best == null || r.size > best.size) best = r;
        }
        return best;
    }

    /** Recurso pequenyo para miniatura: DLNA TN/SM o el mas pequenyo razonable. */
    public Res thumbRes() {
        Res byName = null, smallest = null;
        for (Res r : resources) {
            String p = r.protocolInfo == null ? "" : r.protocolInfo;
            if (byName == null && (p.contains("_TN") || p.contains("_SM"))) byName = r;
            if (r.size > 0 && (smallest == null || r.size < smallest.size)) smallest = r;
        }
        if (byName != null) return byName;
        if (smallest != null && smallest.size < 1_500_000) return smallest;
        return null;
    }

    public String ext() {
        Res r = bestRes();
        if (r == null) return "";
        String path = r.url;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        int dot = path.lastIndexOf('.');
        if (dot >= 0 && path.length() - dot <= 5) return path.substring(dot + 1).toLowerCase();
        // fallback por protocolInfo: http-get:*:image/jpeg:*
        if (r.protocolInfo.contains("image/jpeg")) return "jpg";
        if (r.protocolInfo.contains("video/mp4")) return "mp4";
        return "bin";
    }

    private static String human(long b) {
        if (b < 0) return "?";
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format("%.0f KB", b / 1024.0);
        return String.format("%.1f MB", b / 1048576.0);
    }

    @Override
    public String toString() {
        if (container) {
            return "[DIR] " + title + (childCount >= 0 ? "  (" + childCount + ")" : "");
        }
        Res r = bestRes();
        return title + "  (" + (r != null ? human(r.size) : "?") + ")";
    }
}
