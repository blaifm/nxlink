package dev.blai.nxlink;

import android.net.Network;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Modo "elegir fotos en la camara" (SelectivePush), clonado de la app oficial:
 *  1. HEAD /sp/control por TCP al 8100 (8100-8103) de la camara, anunciando
 *     Data-Server <ip movil>:18100. 200 OK = camara emparejada.
 *  2. La camara conecta al 18100 y manda cada foto como una peticion HTTP
 *     (primera linea con la ruta DCIM, Content-Length, Expect) -> contestamos
 *     "100 Continue", recibimos el cuerpo y cerramos con "200 OK Sub-ErrorCode: 0".
 *  3. "Command: Getout" o "Request: ByeBye*" = fin de sesion.
 */
public class PushReceiver {

    public static final int DATA_PORT = 18100;

    public interface Sink {
        /** Recibe el fichero; debe leer exactamente size bytes de body. true si se guardo bien. */
        boolean onFile(String name, long size, InputStream body);
        void onEvent(String msg);
    }

    private final Network network;
    private final String clientId;
    private final Sink sink;
    private ServerSocket server;
    private volatile boolean running;
    private String cameraIp;
    private String phoneIp = "0.0.0.0";
    private int cameraPort = -1;

    public PushReceiver(Network network, String clientId, Sink sink) {
        this.network = network;
        this.clientId = clientId;
        this.sink = sink;
    }

    public boolean start(String cameraIp, String phoneIp) {
        this.cameraIp = cameraIp;
        this.phoneIp = phoneIp;
        try {
            server = new ServerSocket(DATA_PORT, 2, InetAddress.getByName(phoneIp));
        } catch (Exception e) {
            L.d("PushReceiver: cannot open port " + DATA_PORT + ": " + e.getMessage());
            return false;
        }
        running = true;
        new Thread(() -> {
            L.d("PushReceiver listening on " + phoneIp + ":" + DATA_PORT);
            while (running) {
                try {
                    Socket c = server.accept();
                    new Thread(() -> handle(c)).start();
                } catch (Exception e) {
                    if (running) L.d("PushReceiver accept: " + e.getMessage());
                    break;
                }
            }
        }).start();

        // handshake /sp/control
        for (int port = 8100; port < 8104; port++) {
            try {
                String resp = control(port, phoneIp, "alive");
                L.d("/sp/control (" + port + ") response:\n" + (resp.isEmpty() ? "(empty)" : resp.trim()));
                if (resp.contains("200 OK") || resp.contains("200 ok")) {
                    cameraPort = port;
                    return true;
                }
                if (resp.contains("401") || resp.contains("503")) {
                    L.d("the camera rejected the push pairing");
                    stop();
                    return false;
                }
            } catch (Exception e) {
                L.d("no reply on " + port + " (" + e.getMessage() + ")");
            }
        }
        stop();
        return false;
    }

    private String control(int port, String phoneIp, String nts) throws IOException {
        String req = "HEAD /sp/control HTTP/1.1\r\n"
                + "Host: 192.168.104.1:8100\r\n"
                + "User-Agent: SEC_SP_" + clientId.toLowerCase(Locale.US) + "\r\n"
                + "Data-Server : " + phoneIp + ":" + DATA_PORT + "\r\n"
                + "Data-Port : " + DATA_PORT + "\r\n"
                + "NTS : " + nts + "\r\n"
                + "HOST-PNumber : none\r\n"
                + "Access-Method : manual\r\n\r\n";
        Socket s = network.getSocketFactory().createSocket();
        try {
            s.connect(new InetSocketAddress(cameraIp, port), 2000);
            s.setSoTimeout(3000);
            OutputStream os = s.getOutputStream();
            os.write(req.getBytes(StandardCharsets.US_ASCII));
            os.flush();
            InputStream is = s.getInputStream();
            StringBuilder resp = new StringBuilder();
            byte[] buf = new byte[512];
            try {
                int n;
                while ((n = is.read(buf)) > 0 && resp.length() < 8192) {
                    resp.append(new String(buf, 0, n, StandardCharsets.US_ASCII));
                }
            } catch (SocketTimeoutException ignored) {}
            return resp.toString();
        } finally {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    private void handle(Socket c) {
        try {
            c.setSoTimeout(60000);
            InputStream in = c.getInputStream();
            OutputStream out = c.getOutputStream();
            // leer hasta el fin de cabeceras
            ByteArrayOutputStream acc = new ByteArrayOutputStream();
            int headerEnd = -1;
            byte[] buf = new byte[4096];
            while (headerEnd < 0 && acc.size() < 16384) {
                int n = in.read(buf);
                if (n <= 0) break;
                acc.write(buf, 0, n);
                headerEnd = indexOfCrlfCrlf(acc.toByteArray());
            }
            byte[] all = acc.toByteArray();
            if (headerEnd < 0) {
                L.d("PushReceiver: request without header end (" + all.length + " bytes)");
                c.close();
                return;
            }
            String header = new String(all, 0, headerEnd, StandardCharsets.US_ASCII);
            L.d("PushReceiver <- camera:\n" + header.trim());

            String name = null;
            long size = -1;
            String command = null, request = null;
            String[] lines = header.split("\r\n");
            for (String tok : lines[0].split(" ")) {
                if (tok.contains("DCIM")) name = tok.substring(tok.lastIndexOf('/') + 1).trim();
            }
            for (int i = 1; i < lines.length; i++) {
                int col = lines[i].indexOf(':');
                if (col <= 0) continue;
                String k = lines[i].substring(0, col).trim();
                String v = lines[i].substring(col + 1).trim();
                if (k.equalsIgnoreCase("Content-Length")) try { size = Long.parseLong(v); } catch (Exception ignored) {}
                else if (k.contains("Command")) command = v;
                else if (k.contains("Request")) request = v;
            }

            if (command != null && command.equalsIgnoreCase("Getout")) {
                out.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nSub-ErrorCode: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                sink.onEvent("done");
                c.close();
                return;
            }
            if (request != null && request.toLowerCase(Locale.US).contains("bye")) {
                out.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                sink.onEvent("bye");
                c.close();
                return;
            }
            if (name == null || size < 0) {
                L.d("PushReceiver: header without recognizable file");
                out.write("HTTP/1.1 500 Internal Server Error\r\nSub-ErrorCode: 2501\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                c.close();
                return;
            }

            out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            byte[] remainder = new byte[all.length - headerEnd - 4];
            System.arraycopy(all, headerEnd + 4, remainder, 0, remainder.length);
            InputStream body = new SequenceInputStream(new ByteArrayInputStream(remainder), in);
            boolean ok = sink.onFile(name, size, body);
            String resp = ok
                    ? "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nSub-ErrorCode: 0\r\n\r\n"
                    : "HTTP/1.1 500 Internal Server Error\r\nSub-ErrorCode: 2504\r\n\r\n";
            out.write(resp.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            c.close();
        } catch (Exception e) {
            L.e("PushReceiver handle", e);
            try { c.close(); } catch (Exception ignored) {}
        }
    }

    private static int indexOfCrlfCrlf(byte[] a) {
        for (int i = 0; i + 3 < a.length; i++) {
            if (a[i] == 13 && a[i + 1] == 10 && a[i + 2] == 13 && a[i + 3] == 10) return i;
        }
        return -1;
    }

    public void stop() {
        boolean wasRunning = running;
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        server = null;
        if (wasRunning && cameraPort > 0) {
            final int port = cameraPort;
            new Thread(() -> {
                try { control(port, phoneIp, "byebye"); } catch (Exception ignored) {}
            }).start();
        }
    }
}
