package dev.blai.nxlink;

import android.net.Network;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Cliente MobileLink para Samsung NX300 (y familia Smart Camera).
 * Protocolo clonado de la app oficial (Samsung Smart Camera App 1.4.0):
 *  1. M-SEARCH SSDP "ssdp:all" al 239.255.255.250:1900
 *  2. GET de la LOCATION con "User-Agent: SEC_RVF_ML_<id>" + "Access-Method: manual"
 *     (esto es lo que dispara el emparejamiento en la camara; 401 = denegado/pendiente)
 *  3. UPnP ContentDirectory Browse desde ObjectID "0", en bloques de 100
 *  4. Descarga por HTTP del recurso <res> de mayor tamanyo
 */
public class CameraClient {

    public static final String SSDP_ADDR = "239.255.255.250";
    /** Camaras "antiguas" (NX300 incluida) hablan MobileLink por el 1901; las renovadas por el 1900. */
    public static final int[] SSDP_PORTS = {1901, 1900};
    private static final String CD_URN = "urn:schemas-upnp-org:service:ContentDirectory:1";

    private final Network network;
    private final String ifaceName;
    private final String clientId;
    private String uaPrefix = "SEC_RVF_ML_"; // NX3000 es camara "renovada"; SEC_DSC_ queda de alternativa

    public String friendlyName = "";
    public String modelName = "";
    public String controlUrl = null;

    public CameraClient(Network network, String clientId, String ifaceName) {
        this.network = network;
        this.clientId = clientId;
        this.ifaceName = ifaceName;
    }

    public void setUaPrefix(String p) { this.uaPrefix = p; }
    public String getUaPrefix() { return uaPrefix; }
    public String getUserAgent() { return uaPrefix + clientId; }

    // ---------------------------------------------------------------- SSDP

    /**
     * Una ronda de descubrimiento durante windowMs. Devuelve LOCATIONs unicas.
     * Hace M-SEARCH a 239.255.255.250 en los puertos 1901 y 1900, y ADEMAS escucha
     * los anuncios NOTIFY multicast en ambos puertos (las camaras antiguas se anuncian solas).
     */
    public Set<String> ssdpSearch(int windowMs) throws IOException {
        final Set<String> locations = java.util.Collections.synchronizedSet(new LinkedHashSet<String>());
        final long deadline = System.currentTimeMillis() + windowMs;
        List<DatagramSocket> sockets = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();

        // socket unicast para enviar M-SEARCH y recibir respuestas
        DatagramSocket usock = new DatagramSocket(null);
        usock.setReuseAddress(true);
        usock.bind(new InetSocketAddress(0));
        try {
            network.bindSocket(usock);
        } catch (IOException e) {
            L.d("warning: could not bind the SSDP socket to the WiFi network: " + e.getMessage());
        }
        sockets.add(usock);
        threads.add(receiver(usock, "M-SEARCH resp", locations, deadline));

        // sockets multicast para oir NOTIFY en 1900 y 1901
        NetworkInterface ni = null;
        try {
            if (ifaceName != null) ni = NetworkInterface.getByName(ifaceName);
        } catch (Exception e) {
            L.d("warning: no interface for multicast: " + e.getMessage());
        }
        for (int port : SSDP_PORTS) {
            try {
                MulticastSocket ms = new MulticastSocket(null);
                ms.setReuseAddress(true);
                ms.bind(new InetSocketAddress(port));
                InetSocketAddress group = new InetSocketAddress(InetAddress.getByName(SSDP_ADDR), port);
                if (ni != null) ms.joinGroup(group, ni);
                else ms.joinGroup(InetAddress.getByName(SSDP_ADDR));
                sockets.add(ms);
                threads.add(receiver(ms, "NOTIFY:" + port, locations, deadline));
            } catch (Exception e) {
                L.d("warning: cannot listen for NOTIFY on " + port + ": " + e.getMessage());
            }
        }
        for (Thread t : threads) t.start();

        // M-SEARCH a ambos puertos, 3 envios por puerto (UDP se pierde)
        InetAddress target = InetAddress.getByName(SSDP_ADDR);
        for (int i = 0; i < 3; i++) {
            for (int port : SSDP_PORTS) {
                String msg = "M-SEARCH * HTTP/1.1\r\n"
                        + "HOST: " + SSDP_ADDR + ":" + port + "\r\n"
                        + "MAN: \"ssdp:discover\"\r\n"
                        + "ST: ssdp:all\r\n"
                        + "MX: 2\r\n"
                        + "\r\n";
                byte[] data = msg.getBytes(StandardCharsets.US_ASCII);
                usock.send(new DatagramPacket(data, data.length, target, port));
            }
            try { Thread.sleep(150); } catch (InterruptedException ie) { break; }
        }

        for (Thread t : threads) {
            try { t.join(windowMs + 500); } catch (InterruptedException ignored) {}
        }
        for (DatagramSocket s : sockets) s.close();
        return new LinkedHashSet<>(locations);
    }

    private Thread receiver(final DatagramSocket sock, final String tag,
                            final Set<String> locations, final long deadline) {
        return new Thread(() -> {
            byte[] buf = new byte[4096];
            while (System.currentTimeMillis() < deadline) {
                int remain = (int) (deadline - System.currentTimeMillis());
                if (remain <= 0) break;
                try {
                    sock.setSoTimeout(remain);
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    sock.receive(p);
                    String resp = new String(p.getData(), 0, p.getLength(), StandardCharsets.US_ASCII);
                    String loc = header(resp, "LOCATION");
                    String st = header(resp, "ST");
                    String nt = header(resp, "NT");
                    String nts = header(resp, "NTS");
                    String usn = header(resp, "USN");
                    L.d("SSDP [" + tag + "] de " + p.getAddress().getHostAddress()
                            + (st != null ? "  ST=" + st : "")
                            + (nt != null ? "  NT=" + nt + " NTS=" + nts : "")
                            + "  USN=" + usn + "  LOCATION=" + loc);
                    if (loc != null && !loc.isEmpty()
                            && (nts == null || !nts.contains("byebye"))) {
                        locations.add(loc);
                    }
                } catch (SocketTimeoutException te) {
                    break;
                } catch (IOException e) {
                    break; // socket cerrado
                }
            }
        });
    }

    private static String header(String httpMsg, String name) {
        for (String line : httpMsg.split("\r\n")) {
            int c = line.indexOf(':');
            if (c > 0 && line.substring(0, c).trim().equalsIgnoreCase(name)) {
                return line.substring(c + 1).trim();
            }
        }
        return null;
    }

    // ------------------------------------------------- handshake /mode/control (puerto 7788)

    public static class ModeInfo {
        public String raw = "";
        public String mode = "";       // mobilelink, rvf, autoshare, idle, byebye...
        public String defaultUrl = null;
        public boolean ok = false;
        public boolean newCamera = false;
    }

    /**
     * El descubrimiento REAL de la app oficial: HEAD /mode/control por TCP al 7788 de la camara.
     * La camara contesta con su modo actual y la Default-URL de la descripcion UPnP.
     */
    public ModeInfo modeControl(String cameraIp, String phoneIp, int callbackPort) throws IOException {
        ModeInfo info = new ModeInfo();
        String uaId = clientId.toUpperCase(java.util.Locale.US);
        StringBuilder sb = new StringBuilder();
        sb.append("HEAD /mode/control HTTP/1.1").append("\r\n")
                .append("User-Agent: SEC_MODE_").append(uaId).append("\r\n")
                .append("Connection: Close").append("\r\n")
                .append("NTS : alive").append("\r\n")
                .append("Content-Length: 0").append("\r\n")
                .append("HOST-Mac : ").append(clientId.toLowerCase(java.util.Locale.US)).append("\r\n")
                .append("HOST-Address : ").append(phoneIp).append("\r\n")
                .append("HOST-port : 7788").append("\r\n")
                .append("HOST-PNumber : none").append("\r\n")
                .append("Access-Method : manual").append("\r\n");
        if (callbackPort > 0) {
            sb.append("CALLBACK: <http://").append(phoneIp).append(":").append(callbackPort)
                    .append("/eventCallback>").append("\r\n");
        }
        sb.append("\r\n");

        java.net.Socket sock = network.getSocketFactory().createSocket();
        try {
            sock.connect(new InetSocketAddress(cameraIp, 7788), 3000);
            sock.setSoTimeout(3000);
            OutputStream os = sock.getOutputStream();
            os.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
            os.flush();
            InputStream is = sock.getInputStream();
            StringBuilder resp = new StringBuilder();
            byte[] buf = new byte[1024];
            try {
                int n;
                while ((n = is.read(buf)) > 0 && resp.length() < 16384) {
                    resp.append(new String(buf, 0, n, StandardCharsets.US_ASCII));
                }
            } catch (SocketTimeoutException te) {
                // nos quedamos con lo leido
            }
            info.raw = resp.toString();
        } finally {
            try { sock.close(); } catch (Exception ignored) {}
        }

        L.d("/mode/control response:\n" + (info.raw.isEmpty() ? "(empty)" : info.raw.trim()));
        String low = info.raw.toLowerCase(java.util.Locale.US);
        info.ok = info.raw.contains("200 OK") || info.raw.contains("200 ok");
        info.newCamera = info.raw.contains("MVERSION");
        if (low.contains("mobilelink")) info.mode = "mobilelink";
        else if (low.contains("rvf")) info.mode = "rvf";
        else if (low.contains("autoshare")) info.mode = "autoshare";
        else if (low.contains("selectivepush")) info.mode = "selectivepush";
        else if (low.contains("idle")) info.mode = "idle";
        else if (low.contains("byebye")) info.mode = "byebye";
        for (String line : info.raw.split("\n")) {
            if (line.contains("Default-URL:")) {
                int i = line.indexOf("http");
                if (i >= 0) info.defaultUrl = line.substring(i).trim();
            }
        }
        L.d("camera mode='" + info.mode + "'  Default-URL=" + info.defaultUrl
                + "  newCamera=" + info.newCamera);
        return info;
    }

    // ------------------------------------------------- descripcion de dispositivo

    /** La camara ha respondido 401: esta denegando o esperando confirmacion. */
    public static class AuthPendingException extends IOException {
        public AuthPendingException(String m) { super(m); }
    }

    /**
     * Baja y parsea la descripcion UPnP. AQUI va el handshake Samsung:
     * User-Agent SEC_RVF_ML_* + Access-Method: manual.
     * Devuelve true si encontro un ContentDirectory.
     */
    public boolean fetchDescription(String location) throws IOException {
        L.d("GET description: " + location + "  (UA=" + getUserAgent() + ")");
        HttpURLConnection con = open(location);
        con.setRequestMethod("GET");
        con.setRequestProperty("User-Agent", getUserAgent());
        con.setRequestProperty("Access-Method", "manual");
        con.setConnectTimeout(5000);
        con.setReadTimeout(15000);
        int code;
        try {
            code = con.getResponseCode();
        } catch (IOException e) {
            con.disconnect();
            throw e;
        }
        L.d("HTTP " + code + " from the camera");
        if (code == 401) {
            con.disconnect();
            throw new AuthPendingException("the camera returned 401 (pending acceptance or denied)");
        }
        if (code != 200) {
            con.disconnect();
            throw new IOException("HTTP " + code + " fetching the description");
        }
        String xml = readAll(con.getInputStream());
        con.disconnect();
        parseDescription(xml, location);
        return controlUrl != null;
    }

    private void parseDescription(String xml, String baseLocation) throws IOException {
        friendlyName = "";
        modelName = "";
        controlUrl = null;
        String urlBase = null;
        try {
            XmlPullParser p = Xml.newPullParser();
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            p.setInput(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "UTF-8");
            String curServiceType = null, curControl = null;
            int depth0ForService = -1;
            for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
                if (ev == XmlPullParser.START_TAG) {
                    String tag = stripNs(p.getName());
                    if (tag.equals("friendlyName") && friendlyName.isEmpty()) friendlyName = text(p);
                    else if (tag.equals("modelName") && modelName.isEmpty()) modelName = text(p);
                    else if (tag.equals("URLBase")) urlBase = text(p);
                    else if (tag.equals("service")) { curServiceType = null; curControl = null; depth0ForService = p.getDepth(); }
                    else if (tag.equals("serviceType")) curServiceType = text(p);
                    else if (tag.equals("controlURL")) curControl = text(p);
                } else if (ev == XmlPullParser.END_TAG) {
                    if (stripNs(p.getName()).equals("service") && p.getDepth() == depth0ForService) {
                        L.d("service: " + curServiceType + "  controlURL=" + curControl);
                        if (curServiceType != null && curServiceType.contains("ContentDirectory")
                                && curControl != null && controlUrl == null) {
                            controlUrl = resolve(baseLocation, urlBase, curControl);
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new IOException("error parsing description: " + e.getMessage(), e);
        }
        L.d("camera: '" + friendlyName + "' model='" + modelName + "'  ContentDirectory=" + controlUrl);
    }

    private static String resolve(String location, String urlBase, String path) throws IOException {
        if (path.startsWith("http://") || path.startsWith("https://")) return path;
        URL base = new URL((urlBase != null && !urlBase.isEmpty()) ? urlBase : location);
        return new URL(base, path).toString();
    }

    private static String stripNs(String tag) {
        int c = tag.indexOf(':');
        return c >= 0 ? tag.substring(c + 1) : tag;
    }

    private static String text(XmlPullParser p) throws Exception {
        String t = p.nextText();
        return t == null ? "" : t.trim();
    }

    // ---------------------------------------------------------------- SOAP

    public static class BrowseResult {
        public final List<Entry> entries = new ArrayList<>();
        public int numberReturned;
        public int totalMatches;
    }

    /** Browse BrowseDirectChildren paginado (identico a la app original: bloques de 100). */
    public List<Entry> browseAll(String objectId) throws IOException {
        List<Entry> all = new ArrayList<>();
        int start = 0;
        while (true) {
            BrowseResult r = browse(objectId, start, 100);
            all.addAll(r.entries);
            start += r.numberReturned;
            if (r.numberReturned == 0) break;
            if (r.totalMatches > 0 && start >= r.totalMatches) break;
            if (all.size() > 20000) break; // cinturon de seguridad
        }
        return all;
    }

    public BrowseResult browse(String objectId, int startIndex, int count) throws IOException {
        String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\" "
                + "xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>"
                + "<u:Browse xmlns:u=\"" + CD_URN + "\">"
                + "<ObjectID>" + xmlEscape(objectId) + "</ObjectID>"
                + "<BrowseFlag>BrowseDirectChildren</BrowseFlag>"
                + "<Filter>*</Filter>"
                + "<StartingIndex>" + startIndex + "</StartingIndex>"
                + "<RequestedCount>" + count + "</RequestedCount>"
                + "<SortCriteria></SortCriteria>"
                + "</u:Browse></s:Body></s:Envelope>";
        L.d("SOAP Browse ObjectID=" + objectId + " start=" + startIndex + " count=" + count);
        String resp = soap("Browse", body);
        return parseBrowseResponse(resp);
    }

    /** Accion opcional de cortesia: la camara muestra el progreso de copia. Ignora fallos. */
    public void setTotalCopyItems(int n) {
        try {
            String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                    + "<s:Envelope s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\" "
                    + "xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>"
                    + "<u:SetTotalCopyItems xmlns:u=\"" + CD_URN + "\">"
                    + "<TotalNumber>" + n + "</TotalNumber>"
                    + "</u:SetTotalCopyItems></s:Body></s:Envelope>";
            soap("SetTotalCopyItems", body);
            L.d("SetTotalCopyItems(" + n + ") OK");
        } catch (Exception e) {
            L.d("SetTotalCopyItems not supported (harmless): " + e.getMessage());
        }
    }

    private String soap(String action, String body) throws IOException {
        if (controlUrl == null) throw new IOException("no controlURL (not connected)");
        HttpURLConnection con = open(controlUrl);
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        con.setRequestMethod("POST");
        con.setDoOutput(true);
        con.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
        con.setRequestProperty("SOAPACTION", "\"" + CD_URN + "#" + action + "\"");
        con.setRequestProperty("User-Agent", getUserAgent());
        con.setFixedLengthStreamingMode(payload.length);
        con.setConnectTimeout(5000);
        con.setReadTimeout(30000);
        OutputStream os = con.getOutputStream();
        os.write(payload);
        os.flush();
        os.close();
        int code = con.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? con.getInputStream() : con.getErrorStream();
        String resp = is != null ? readAll(is) : "";
        con.disconnect();
        if (code < 200 || code >= 300) {
            throw new IOException("SOAP " + action + " -> HTTP " + code + " : " + snip(resp));
        }
        return resp;
    }

    private BrowseResult parseBrowseResponse(String soapXml) throws IOException {
        String didl = null;
        BrowseResult out = new BrowseResult();
        try {
            XmlPullParser p = Xml.newPullParser();
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            p.setInput(new ByteArrayInputStream(soapXml.getBytes(StandardCharsets.UTF_8)), "UTF-8");
            for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
                if (ev == XmlPullParser.START_TAG) {
                    String tag = stripNs(p.getName());
                    if (tag.equals("Result")) didl = p.nextText();
                    else if (tag.equals("NumberReturned")) out.numberReturned = parseInt(p.nextText());
                    else if (tag.equals("TotalMatches")) out.totalMatches = parseInt(p.nextText());
                }
            }
        } catch (Exception e) {
            throw new IOException("error parsing SOAP response: " + e.getMessage(), e);
        }
        if (didl == null) throw new IOException("Browse response without <Result>. Response: " + snip(soapXml));
        parseDidl(didl, out);
        L.d("Browse -> " + out.entries.size() + " entries (returned=" + out.numberReturned
                + ", total=" + out.totalMatches + ")");
        return out;
    }

    private void parseDidl(String didl, BrowseResult out) throws IOException {
        try {
            XmlPullParser p = Xml.newPullParser();
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            p.setInput(new ByteArrayInputStream(didl.getBytes(StandardCharsets.UTF_8)), "UTF-8");
            Entry cur = null;
            for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
                if (ev == XmlPullParser.START_TAG) {
                    String tag = stripNs(p.getName());
                    if (tag.equals("container") || tag.equals("item")) {
                        cur = new Entry();
                        cur.container = tag.equals("container");
                        cur.id = attr(p, "id");
                        cur.childCount = parseInt(attr(p, "childCount"));
                    } else if (cur != null && tag.equals("title")) {
                        cur.title = p.nextText();
                    } else if (cur != null && tag.equals("res")) {
                        Entry.Res r = new Entry.Res();
                        r.protocolInfo = attr(p, "protocolInfo");
                        long sz = -1;
                        try { sz = Long.parseLong(attr(p, "size")); } catch (Exception ignored) {}
                        r.size = sz;
                        r.url = p.nextText();
                        if (r.url != null && !r.url.trim().isEmpty()) {
                            r.url = r.url.trim();
                            cur.resources.add(r);
                        }
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    String tag = stripNs(p.getName());
                    if ((tag.equals("container") || tag.equals("item")) && cur != null) {
                        out.entries.add(cur);
                        cur = null;
                    }
                }
            }
        } catch (Exception e) {
            throw new IOException("error parsing DIDL: " + e.getMessage() + " DIDL: " + snip(didl), e);
        }
    }

    private static String attr(XmlPullParser p, String name) {
        for (int i = 0; i < p.getAttributeCount(); i++) {
            if (stripNs(p.getAttributeName(i)).equals(name)) return p.getAttributeValue(i);
        }
        return "";
    }

    // ---------------------------------------------------------------- descarga

    public interface Progress { void onProgress(long done, long total); }

    /** Abre el stream del recurso. El que llama escribe donde toque. */
    public long download(Entry.Res res, OutputStream dst, Progress cb) throws IOException {
        HttpURLConnection con = open(res.url);
        con.setRequestMethod("GET");
        con.setRequestProperty("User-Agent", getUserAgent());
        con.setConnectTimeout(5000);
        con.setReadTimeout(60000);
        int code = con.getResponseCode();
        if (code != 200) {
            con.disconnect();
            throw new IOException("HTTP " + code + " downloading " + res.url);
        }
        long total = con.getContentLengthLong();
        if (total <= 0) total = res.size;
        InputStream is = con.getInputStream();
        byte[] buf = new byte[65536];
        long done = 0, lastMark = 0;
        int n;
        while ((n = is.read(buf)) > 0) {
            dst.write(buf, 0, n);
            done += n;
            if (cb != null && (done - lastMark > 1048576)) { lastMark = done; cb.onProgress(done, total); }
        }
        is.close();
        con.disconnect();
        if (cb != null) cb.onProgress(done, total);
        return done;
    }

    /** Baja un recurso pequenyo (miniaturas) a memoria. */
    public byte[] fetchBytes(String url, int maxBytes) throws IOException {
        HttpURLConnection con = open(url);
        con.setRequestMethod("GET");
        con.setRequestProperty("User-Agent", getUserAgent());
        con.setConnectTimeout(4000);
        con.setReadTimeout(10000);
        int code = con.getResponseCode();
        if (code != 200) { con.disconnect(); throw new IOException("HTTP " + code); }
        InputStream is = con.getInputStream();
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = is.read(buf)) > 0 && bos.size() < maxBytes) bos.write(buf, 0, n);
        is.close();
        con.disconnect();
        return bos.toByteArray();
    }

    // ---------------------------------------------------------------- util

    private HttpURLConnection open(String url) throws IOException {
        return (HttpURLConnection) network.openConnection(new URL(url));
    }

    private static String readAll(InputStream is) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return -1; }
    }

    private static String xmlEscape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static String snip(String s) {
        if (s == null) return "null";
        s = s.replaceAll("\\s+", " ");
        return s.length() > 400 ? s.substring(0, 400) + "..." : s;
    }
}
