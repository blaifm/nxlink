package dev.blai.nxlink;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Replica del ModeServer de la app oficial: la camara conecta al puerto 7789 del movil
 * y manda peticiones de cambio de modo ("samsungmodeserver ... mobilelink", "byebye"...).
 * Hay que contestar "HTTP/1.1 200 OK" o la camara se mosquea.
 */
public class ModeServer {

    private ServerSocket server;
    private Thread thread;
    private volatile boolean running;
    public final AtomicReference<String> lastEvent = new AtomicReference<>(null);
    private int port = -1;

    /** Arranca en 7789 (o siguiente libre hasta 7793). Devuelve el puerto o -1. */
    public int start(InetAddress bindAddr) {
        for (int p = 7789; p <= 7793; p++) {
            try {
                server = new ServerSocket(p, 0, bindAddr);
                port = p;
                break;
            } catch (Exception e) {
                L.d("ModeServer: puerto " + p + " ocupado, pruebo el siguiente");
            }
        }
        if (server == null) {
            L.d("ModeServer: no he podido abrir puerto de callback (sigo sin el)");
            return -1;
        }
        running = true;
        thread = new Thread(() -> {
            L.d("ModeServer escuchando en " + bindAddr.getHostAddress() + ":" + port);
            while (running) {
                try {
                    Socket client = server.accept();
                    handle(client);
                } catch (Exception e) {
                    if (running) L.d("ModeServer: " + e.getMessage());
                    break;
                }
            }
        });
        thread.start();
        return port;
    }

    private void handle(Socket client) {
        new Thread(() -> {
            try {
                client.setSoTimeout(3000);
                BufferedInputStream bis = new BufferedInputStream(client.getInputStream());
                StringBuilder sb = new StringBuilder();
                byte[] buf = new byte[1024];
                while (!sb.toString().contains("\r\n\r\n") && sb.length() < 8192) {
                    int n;
                    try { n = bis.read(buf); } catch (Exception te) { break; }
                    if (n <= 0) break;
                    sb.append(new String(buf, 0, n, StandardCharsets.US_ASCII));
                }
                String req = sb.toString();
                L.d("ModeServer <- camara (" + client.getInetAddress().getHostAddress() + "):\n"
                        + req.trim());
                lastEvent.set(req);
                BufferedOutputStream bos = new BufferedOutputStream(client.getOutputStream());
                bos.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                bos.flush();
                client.close();
            } catch (Exception e) {
                L.d("ModeServer handle: " + e.getMessage());
            }
        }).start();
    }

    public int getPort() { return port; }

    public void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        server = null;
    }
}
