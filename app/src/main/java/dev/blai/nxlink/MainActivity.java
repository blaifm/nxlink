package dev.blai.nxlink;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.RouteInfo;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends androidx.appcompat.app.AppCompatActivity implements PushReceiver.Sink {

    private static final int SEARCH_TIMEOUT_MS = 90_000;

    private ConnectivityManager cm;
    private WifiManager.MulticastLock mlock;
    private ExecutorService exec;
    private ExecutorService thumbExec;
    private Network wifiNet;
    private CameraClient client;
    private ModeServer modeServer;
    private PushReceiver pushReceiver;

    private TextView tvStatus, tvLog;
    private ScrollView logScroll;
    private Button btnSearch, btnAll;
    private View logPanel;
    private android.widget.GridView grid;
    private EntryAdapter adapter;
    private final LruCache<String, Bitmap> thumbCache = new LruCache<>(200);

    private final List<Entry> current = new ArrayList<>();
    private final java.util.Set<Entry> selected = new java.util.HashSet<>();
    private final Deque<String> idStack = new ArrayDeque<>();
    private final AtomicBoolean searching = new AtomicBoolean(false);
    private int pushCount = 0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(this);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        mlock = wm.createMulticastLock("nxlink");
        mlock.setReferenceCounted(false);
        exec = Executors.newSingleThreadExecutor();
        thumbExec = Executors.newFixedThreadPool(2);

        tvStatus = findViewById(R.id.tvStatus);
        tvLog = findViewById(R.id.tvLog);
        logScroll = findViewById(R.id.logScroll);
        btnSearch = findViewById(R.id.btnSearch);
        btnAll = findViewById(R.id.btnAll);
        logPanel = findViewById(R.id.logPanel);
        grid = findViewById(R.id.grid);

        com.google.android.material.appbar.MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.menu_log) {
                boolean shown = logPanel.getVisibility() == View.VISIBLE;
                logPanel.setVisibility(shown ? View.GONE : View.VISIBLE);
                return true;
            }
            return false;
        });

        adapter = new EntryAdapter();
        grid.setAdapter(adapter);

        L.setSink(line -> runOnUiThread(() -> {
            tvLog.append(line + "\n");
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        }));

        btnSearch.setOnClickListener(v -> startSearch());
        btnAll.setOnClickListener(v -> downloadAll());
        findViewById(R.id.btnCopy).setOnClickListener(v -> {
            ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cb.setPrimaryClip(ClipData.newPlainText("nxlink-log", L.dump()));
            Toast.makeText(this, R.string.toast_log_copied, Toast.LENGTH_SHORT).show();
        });

        L.d("NX Link v2.3 started. Android " + android.os.Build.VERSION.RELEASE
                + " (" + android.os.Build.MODEL + ")");
    }

    @Override
    public void onBackPressed() {
        if (idStack.size() > 1) {
            idStack.pop();
            browseInto(idStack.peek(), true);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { if (pushReceiver != null) pushReceiver.stop(); } catch (Exception ignored) {}
        try { if (modeServer != null) modeServer.stop(); } catch (Exception ignored) {}
        try { cm.bindProcessToNetwork(null); } catch (Exception ignored) {}
        try { mlock.release(); } catch (Exception ignored) {}
        exec.shutdownNow();
        thumbExec.shutdownNow();
        L.setSink(null);
    }

    private void status(String s) {
        runOnUiThread(() -> tvStatus.setText(s));
    }

    private com.google.android.material.progressindicator.LinearProgressIndicator progressBar() {
        return (com.google.android.material.progressindicator.LinearProgressIndicator) findViewById(R.id.progress);
    }

    /** Indeterminate activity bar. */
    private void busy(boolean on) {
        runOnUiThread(() -> {
            com.google.android.material.progressindicator.LinearProgressIndicator p = progressBar();
            if (on) {
                if (!p.isIndeterminate()) {
                    p.setVisibility(View.INVISIBLE);
                    p.setIndeterminate(true);
                }
                p.setVisibility(View.VISIBLE);
            } else {
                p.setVisibility(View.INVISIBLE);
            }
        });
    }

    /** Determinate bar reset to 0 for downloads with a known size. */
    private void progressStart() {
        runOnUiThread(() -> {
            com.google.android.material.progressindicator.LinearProgressIndicator p = progressBar();
            p.setVisibility(View.INVISIBLE);
            p.setIndeterminate(false);
            p.setMax(1000);
            p.setProgress(0);
            p.setVisibility(View.VISIBLE);
        });
    }

    private void progressSet(long done, long total) {
        if (total <= 0) return;
        final int v = (int) Math.min(1000, done * 1000 / total);
        runOnUiThread(() -> progressBar().setProgress(v));
    }

    /** Cell tap: folder = open, photo = toggle selection. */
    private void onCellTap(Entry e) {
        if (e.container) {
            browseInto(e.id, false);
        } else {
            if (!selected.remove(e)) selected.add(e);
            adapter.notifyDataSetChanged();
            updateDownloadButton();
        }
    }

    private String clientId() {
        SharedPreferences sp = getSharedPreferences("nxlink", MODE_PRIVATE);
        String id = sp.getString("client_id", null);
        if (id == null) {
            // the original app used the phone's MAC; we generate a stable MAC-looking id
            Random r = new Random();
            StringBuilder sb = new StringBuilder("02");
            for (int i = 0; i < 5; i++) sb.append(String.format(Locale.US, ":%02x", r.nextInt(256)));
            id = sb.toString();
            sp.edit().putString("client_id", id).apply();
        }
        return id;
    }

    // ------------------------------------------------------------- discovery

    private void startSearch() {
        if (!searching.compareAndSet(false, true)) return;
        btnSearch.setEnabled(false);
        btnAll.setEnabled(false);
        if (pushReceiver != null) { pushReceiver.stop(); pushReceiver = null; }
        status(getString(R.string.st_searching));
        exec.submit(() -> {
            busy(true);
            try {
                searchFlow();
            } catch (Throwable t) {
                L.e("search failed", t);
                status(getString(R.string.st_fail, t.getMessage()));
            } finally {
                busy(false);
                searching.set(false);
                runOnUiThread(() -> btnSearch.setEnabled(true));
            }
        });
    }

    private void searchFlow() throws Exception {
        wifiNet = waitForWifi(12_000);
        if (wifiNet == null) {
            status(getString(R.string.st_no_wifi));
            L.d("no WiFi network after 12s. Settings > WiFi > camera network (AP_SSC_...)");
            return;
        }
        cm.bindProcessToNetwork(wifiNet);
        mlock.acquire();
        LinkProperties lp = cm.getLinkProperties(wifiNet);
        String gateway = null;
        String iface = null;
        String phoneIp = null;
        if (lp != null) {
            iface = lp.getInterfaceName();
            L.d("WiFi network (" + iface + "): " + lp.getLinkAddresses());
            for (RouteInfo ri : lp.getRoutes()) {
                if (ri.isDefaultRoute() && ri.getGateway() != null) {
                    gateway = ri.getGateway().getHostAddress();
                }
            }
            for (android.net.LinkAddress la : lp.getLinkAddresses()) {
                if (la.getAddress() instanceof java.net.Inet4Address) {
                    phoneIp = la.getAddress().getHostAddress();
                }
            }
            L.d("gateway (likely camera IP): " + gateway);
        }
        if (gateway == null || phoneIp == null) {
            status(getString(R.string.st_weird_net));
            L.d("gateway=" + gateway + " phoneIp=" + phoneIp);
            return;
        }

        client = new CameraClient(wifiNet, clientId(), iface);
        L.d("base User-Agent: " + client.getUserAgent());

        // callback server (the camera connects here to announce mode changes)
        if (modeServer != null) modeServer.stop();
        modeServer = new ModeServer();
        int cbPort = modeServer.start(java.net.InetAddress.getByName(phoneIp));

        long deadline = System.currentTimeMillis() + SEARCH_TIMEOUT_MS;
        Set<String> tried = new java.util.LinkedHashSet<>();
        boolean connected = false;
        int round = 0;
        int authRetries = 0;
        boolean idleNotified = false;

        while (System.currentTimeMillis() < deadline && !connected) {
            round++;
            status(getString(R.string.st_talking, gateway, round));
            String descUrl = null;
            try {
                CameraClient.ModeInfo mi = client.modeControl(gateway, phoneIp, cbPort);
                if (mi.defaultUrl != null) {
                    descUrl = mi.defaultUrl;
                } else {
                    // some firmwares say "unavailable" on 7788 but send the URL via NOTIFY
                    descUrl = descUrlFromEvent(modeServer.lastEvent.get());
                    if (descUrl != null) L.d("DescriptionURL taken from NOTIFY: " + descUrl);
                }
                if (descUrl == null && "mobilelink".equals(mi.mode)) {
                    descUrl = "http://" + gateway + ":7676/smp_2_";
                    L.d("MobileLink mode without announced URL; trying " + descUrl);
                }
                if ("selectivepush".equals(mi.mode)) {
                    startPushMode(gateway, phoneIp);
                    return;
                }
                if (descUrl == null && "idle".equals(mi.mode)) {
                    if (!idleNotified) {
                        status(getString(R.string.st_idle));
                        idleNotified = true;
                    }
                } else if (descUrl == null && !mi.mode.isEmpty()) {
                    status(getString(R.string.st_mode_unsupported, mi.mode));
                }
            } catch (Exception e) {
                L.d("no reply on 7788 (" + e.getMessage() + ")");
                // plan B: SSDP, in case this firmware actually uses it
                if (round % 3 == 0) {
                    Set<String> locs = client.ssdpSearch(2000);
                    if (!locs.isEmpty()) descUrl = locs.iterator().next();
                }
            }

            if (descUrl != null) {
                try {
                    if (client.fetchDescription(descUrl)) {
                        connected = true;
                        break;
                    } else if (tried.add(descUrl)) {
                        L.d("description without ContentDirectory at " + descUrl);
                    }
                } catch (CameraClient.AuthPendingException ape) {
                    status(getString(R.string.st_confirm));
                    authRetries++;
                    if (authRetries % 3 == 0) {
                        String next = client.getUaPrefix().equals("SEC_RVF_ML_") ? "SEC_DSC_" : "SEC_RVF_ML_";
                        client.setUaPrefix(next);
                        L.d("still 401; trying User-Agent " + next + "...");
                    } else {
                        L.d("401: confirm on the camera (or restart MobileLink)...");
                    }
                } catch (Exception e) {
                    L.d("failed fetching description " + descUrl + " -> " + e.getMessage());
                }
            }
            Thread.sleep(1500);
        }

        if (!connected) {
            status(getString(R.string.st_not_found));
            L.d("search timed out (" + (SEARCH_TIMEOUT_MS / 1000) + "s)");
            return;
        }

        status(getString(R.string.st_connected, client.friendlyName, client.modelName));
        autoDescend();
    }

    /** "Select on camera" mode: server on 18100 + /sp/control pairing. */
    private void startPushMode(String cameraIp, String phoneIp) {
        status(getString(R.string.st_push_detected));
        pushCount = 0;
        pushReceiver = new PushReceiver(wifiNet, clientId(), this);
        boolean ok = pushReceiver.start(cameraIp, phoneIp);
        if (ok) {
            status(getString(R.string.st_push_ready));
            L.d("push mode active; waiting for files on port " + PushReceiver.DATA_PORT);
        } else {
            status(getString(R.string.st_push_rejected));
        }
    }

    // PushReceiver callbacks
    @Override
    public boolean onFile(String name, long size, InputStream body) {
        if (size > 0) progressStart(); else busy(true);
        status(getString(R.string.st_receiving, name, size / 1048576));
        boolean ok = saveStream(name, size, body);
        busy(false);
        if (ok) {
            pushCount++;
            status(getString(R.string.st_received_n, pushCount));
        } else {
            status(getString(R.string.st_save_failed, name));
        }
        return ok;
    }

    @Override
    public void onEvent(String code) {
        L.d("push event: " + code);
        String msg = "done".equals(code) ? getString(R.string.ev_push_done)
                : "bye".equals(code) ? getString(R.string.ev_push_bye) : code;
        status(pushCount > 0 ? getString(R.string.ev_with_count, msg, pushCount) : msg);
    }

    private static String descUrlFromEvent(String ev) {
        if (ev == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<DescriptionURL>\\s*(http[^<\\s]+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(ev);
        return m.find() ? m.group(1).trim() : null;
    }

    private Network waitForWifi(long timeoutMs) throws InterruptedException {
        final Network[] result = new Network[1];
        final CountDownLatch latch = new CountDownLatch(1);
        NetworkRequest req = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        ConnectivityManager.NetworkCallback cb = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                if (result[0] == null) {
                    result[0] = network;
                    latch.countDown();
                }
            }
        };
        cm.registerNetworkCallback(req, cb);
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } finally {
            cm.unregisterNetworkCallback(cb);
        }
        return result[0];
    }

    // ------------------------------------------------------------- browsing

    /** From root, auto-descend while there is a single folder (straight to the photos). */
    private void autoDescend() {
        idStack.clear();
        idStack.push("0");
        exec.submit(() -> {
            busy(true);
            try {
                status(getString(R.string.st_connected_loading, client.friendlyName));
                List<Entry> entries = client.browseAll("0");
                int depth = 0;
                while (entries.size() == 1 && entries.get(0).container && depth < 6) {
                    Entry only = entries.get(0);
                    L.d("descending into '" + only.title + "'");
                    idStack.push(only.id);
                    entries = client.browseAll(only.id);
                    depth++;
                }
                show(entries);
            } catch (Exception e) {
                L.e("initial browse failed", e);
                status(getString(R.string.st_list_fail, e.getMessage()));
            } finally {
                busy(false);
            }
        });
    }

    private void browseInto(String objectId, boolean replaceStackTop) {
        if (!replaceStackTop) idStack.push(objectId);
        exec.submit(() -> {
            busy(true);
            try {
                status(getString(R.string.st_loading_folder));
                show(client.browseAll(objectId));
            } catch (Exception e) {
                L.e("browse failed", e);
                status(getString(R.string.st_list_fail, e.getMessage()));
            } finally {
                busy(false);
            }
        });
    }

    private void show(List<Entry> entries) {
        int files = 0, dirs = 0;
        for (Entry e : entries) { if (e.container) dirs++; else files++; }
        StringBuilder sb = new StringBuilder();
        if (files > 0) sb.append(getString(R.string.lbl_photos, files));
        if (files > 0 && dirs > 0) sb.append(" · ");
        if (dirs > 0) sb.append(getString(R.string.lbl_folders, dirs));
        if (files > 0) sb.append(" — ").append(getString(R.string.lbl_tap_hint));
        final String summary = sb.toString();
        runOnUiThread(() -> {
            current.clear();
            current.addAll(entries);
            selected.clear();
            adapter.notifyDataSetChanged();
            btnAll.setEnabled(!entries.isEmpty());
            updateDownloadButton();
            tvStatus.setText(entries.isEmpty() ? getString(R.string.st_empty_folder) : summary);
        });
    }

    private void updateDownloadButton() {
        btnAll.setText(selected.isEmpty()
                ? getString(R.string.btn_download_all)
                : getString(R.string.btn_download_n, selected.size()));
    }

    // ------------------------------------------------------------- thumbnail grid

    private class EntryAdapter extends ArrayAdapter<Entry> {
        EntryAdapter() {
            super(MainActivity.this, 0, current);
        }

        @Override
        public View getView(int pos, View convertView, ViewGroup parent) {
            View v = convertView != null ? convertView
                    : getLayoutInflater().inflate(R.layout.cell_entry, parent, false);
            com.google.android.material.card.MaterialCardView card = v.findViewById(R.id.card);
            ImageView iv = v.findViewById(R.id.ivThumb);
            TextView tv = v.findViewById(R.id.tvTitle);
            Entry e = current.get(pos);
            card.setOnClickListener(cv -> onCellTap(e));
            if (e.container) {
                tv.setText(e.title + (e.childCount >= 0 ? " (" + e.childCount + ")" : ""));
                iv.setTag(null);
                iv.clearColorFilter();
                iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                iv.setImageResource(android.R.drawable.ic_menu_gallery);
                card.setChecked(false);
                return v;
            }
            tv.setText(e.title);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            boolean isSel = selected.contains(e);
            card.setChecked(isSel);
            if (isSel) {
                iv.setColorFilter(0x66000000, android.graphics.PorterDuff.Mode.SRC_ATOP);
            } else {
                iv.clearColorFilter();
            }
            Entry.Res t = e.thumbRes();
            final String url = t != null ? t.url : null;
            iv.setTag(url);
            Bitmap cached = url != null ? thumbCache.get(url) : null;
            if (cached != null) {
                iv.setImageBitmap(cached);
            } else {
                iv.setImageResource(android.R.drawable.ic_menu_report_image);
                if (url != null && client != null) {
                    thumbExec.submit(() -> {
                        try {
                            if (thumbCache.get(url) == null) {
                                byte[] data = client.fetchBytes(url, 2 * 1024 * 1024);
                                Bitmap bm = decodeThumb(data);
                                if (bm != null) thumbCache.put(url, bm);
                            }
                            final Bitmap bm = thumbCache.get(url);
                            if (bm != null) {
                                runOnUiThread(() -> {
                                    if (url.equals(iv.getTag())) iv.setImageBitmap(bm);
                                });
                            }
                        } catch (Exception ex) {
                            // failed thumbnail: not a big deal
                        }
                    });
                }
            }
            return v;
        }
    }

    /** Decode downsampled to ~320px so the grid scrolls smoothly. */
    private static Bitmap decodeThumb(byte[] data) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= 320 && o.outHeight / (sample * 2) >= 320) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(data, 0, data.length, o2);
    }

    // ------------------------------------------------------------- downloads

    private void downloadAll() {
        if (!selected.isEmpty()) {
            List<Entry> pick = new ArrayList<>(selected);
            selected.clear();
            adapter.notifyDataSetChanged();
            updateDownloadButton();
            downloadBatch(pick);
            return;
        }
        List<Entry> snapshot = new ArrayList<>(current);
        exec.submit(() -> {
            List<Entry> files = new ArrayList<>();
            collectFiles(snapshot, files, 0);
            batchNow(files);
        });
    }

    private void downloadBatch(List<Entry> files) {
        exec.submit(() -> batchNow(files));
    }

    private void batchNow(List<Entry> files) {
        try {
            if (files.isEmpty()) { L.d("nothing to download here"); busy(false); return; }
            long total = 0;
            for (Entry e : files) {
                Entry.Res r = e.bestRes();
                if (r != null && r.size > 0) total += r.size;
            }
            final long totalBytes = total;
            if (totalBytes > 0) progressStart(); else busy(true);
            L.d("downloading " + files.size() + " files (" + (totalBytes / 1048576) + " MB)...");
            client.setTotalCopyItems(files.size());
            int ok = 0, fail = 0, i = 0;
            long cumul = 0;
            for (Entry e : files) {
                i++;
                final int idx = i;
                final int n = files.size();
                final long base = cumul;
                final String title = e.title;
                status(getString(R.string.st_downloading, idx, n, title));
                boolean res = saveEntry(e, (done, t) -> {
                    progressSet(base + done, totalBytes);
                    status(getString(R.string.st_downloading_mb, idx, n, title,
                            (base + done) / 1048576, totalBytes / 1048576));
                });
                if (res) ok++; else fail++;
                Entry.Res r = e.bestRes();
                cumul = base + (r != null && r.size > 0 ? r.size : 0);
                progressSet(cumul, totalBytes);
            }
            status(getString(R.string.st_batch_done, ok, fail));
            L.d("batch done: " + ok + " OK, " + fail + " failed");
        } catch (Exception ex) {
            L.e("batch download failed", ex);
        } finally {
            busy(false);
        }
    }

    private void collectFiles(List<Entry> entries, List<Entry> out, int depth) {
        if (depth > 6) return;
        for (Entry e : entries) {
            if (!e.container) {
                out.add(e);
            } else {
                try {
                    collectFiles(client.browseAll(e.id), out, depth + 1);
                } catch (Exception ex) {
                    L.d("could not open folder '" + e.title + "': " + ex.getMessage());
                }
            }
        }
    }

    /** Download the best <res> and store it via MediaStore. */
    private boolean saveEntry(Entry e, CameraClient.Progress extra) {
        Entry.Res res = e.bestRes();
        if (res == null) { L.d("'" + e.title + "' has no downloadable resources"); return false; }
        String name = e.title;
        String ext = e.ext();
        if (!name.toLowerCase(Locale.US).endsWith("." + ext)) name = name + "." + ext;
        return saveToMediaStore(name, os -> client.download(res, os, (done, total) -> {
            L.d("  ..." + (done / 1048576) + " MB" + (total > 0 ? " of " + (total / 1048576) + " MB" : ""));
            if (extra != null) extra.onProgress(done, total);
        }));
    }

    /** Store an incoming push-mode file reading exactly size bytes. */
    private boolean saveStream(String name, long size, InputStream in) {
        return saveToMediaStore(name, os -> {
            byte[] buf = new byte[65536];
            long remaining = size, done = 0, mark = 0;
            while (remaining > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (n <= 0) throw new java.io.IOException("stream cut with " + remaining + " bytes left");
                os.write(buf, 0, n);
                remaining -= n;
                done += n;
                if (done - mark > 524288) {
                    mark = done;
                    progressSet(done, size);
                    L.d("  ..." + (done / 1048576) + " MB of " + (size / 1048576) + " MB");
                }
            }
            L.d("received '" + name + "' (" + done + " bytes)");
            return done;
        });
    }

    private interface Writer {
        long write(OutputStream os) throws Exception;
    }

    private boolean saveToMediaStore(String name, Writer writer) {
        String lower = name.toLowerCase(Locale.US);
        String mime;
        Uri collection;
        String relPath;
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            mime = "image/jpeg";
            collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            relPath = "Pictures/NX300";
        } else if (lower.endsWith(".mp4")) {
            mime = "video/mp4";
            collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            relPath = "Movies/NX300";
        } else {
            mime = "application/octet-stream";
            collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            relPath = "Download/NX300";
        }
        ContentResolver cr = getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, relPath);
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = null;
        try {
            uri = cr.insert(collection, v);
            if (uri == null) throw new Exception("MediaStore.insert returned null");
            try (OutputStream os = cr.openOutputStream(uri)) {
                long got = writer.write(os);
                L.d("saved " + relPath + "/" + name + " (" + got + " bytes)");
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(uri, done, null, null);
            return true;
        } catch (Exception ex) {
            L.e("failed saving '" + name + "'", ex);
            if (uri != null) try { cr.delete(uri, null, null); } catch (Exception ignored) {}
            return false;
        }
    }
}
