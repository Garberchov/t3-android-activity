package org.t3activity.companion;

import android.content.Context;
import android.os.SystemClock;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.util.concurrent.CopyOnWriteArrayList;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.Certificate;
import javax.net.ssl.*;

final class ActivityFeed {
    static volatile boolean monitoring;
    private static volatile JSONObject snapshot;
    private static volatile long refreshed;
    static boolean connected, attempted;
    static final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private static final long MAX_AGE = 30_000;

    // Retain the last snapshot briefly on a transient outage, but explicitly mark it stale.
    static void publish(JSONObject data) { publish(data, 0); }
    static void publish(JSONObject data, long ageMs) {
        attempted = true;
        connected = data != null;
        if (data != null) {
            snapshot = data;
            refreshed = SystemClock.elapsedRealtime() - Math.min(Math.max(0, ageMs), MAX_AGE + 1);
        }
        for (Runnable listener : listeners) listener.run();
    }
    static JSONObject current() {
        return snapshot != null && SystemClock.elapsedRealtime() - refreshed < MAX_AGE ? snapshot : null;
    }
    static void heartbeat(long ageMs) {
        if (snapshot == null) return;
        publish(snapshot, ageMs);
    }
    static boolean wallpaperBehind() {
        return !monitoring || snapshot == null || SystemClock.elapsedRealtime() - refreshed > 12_000;
    }
    static boolean needsRefresh() {
        return !connected || current() == null || SystemClock.elapsedRealtime() - refreshed > 8_000;
    }
    static void clear() {
        snapshot = null; refreshed = 0; connected = false; attempted = false;
        for (Runnable listener : listeners) listener.run();
    }
    static String status(int count, org.json.JSONArray rows) {
        if (!attempted) return "Connecting";
        if (!connected) return current() == null ? "Disconnected" : "Reconnecting…";
        if (current() == null || SystemClock.elapsedRealtime() - refreshed > 8_000) return "Syncing…";
        return ActivityCard.summary(count, rows);
    }

    static final int MAX_PAIR_BYTES = 32 * 1024;

    static byte[] boundedRead(InputStream stream, int limit) throws IOException {
        byte[] data = stream.readNBytes(limit + 1);
        if (data.length > limit) throw new IOException("File is too large");
        return data;
    }

    private static AtomicFile pairingFile(Context context) {
        return new AtomicFile(context.getFileStreamPath("pairing.json"));
    }

    static boolean paired(Context context) { return pairingFile(context).getBaseFile().exists(); }

    private static Certificate certificate(JSONObject config) throws Exception {
        byte[] pem = config.getString("certificate").getBytes(StandardCharsets.UTF_8);
        return CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(pem));
    }

    private static JSONObject validate(byte[] data) throws Exception {
        JSONObject config = new JSONObject(new String(data, StandardCharsets.UTF_8));
        URL url = new URL(config.getString("url"));
        if (config.getInt("version") != 1 || !"https".equals(url.getProtocol())
                || url.getHost().isEmpty() || url.getUserInfo() != null || url.getRef() != null
                || url.getQuery() != null || !"/activity".equals(url.getPath())
                || !config.getString("token").matches("[A-Za-z0-9_-]{43,128}"))
            throw new IOException("Invalid pairing file");
        certificate(config);
        return config;
    }

    static void pair(Context context, InputStream source) throws Exception {
        byte[] data = boundedRead(source, MAX_PAIR_BYTES);
        validate(data);
        AtomicFile target = pairingFile(context);
        FileOutputStream out = target.startWrite();
        try {
            out.write(data);
            target.finishWrite(out);
        } catch (Exception e) {
            target.failWrite(out);
            throw e;
        }
    }

    static final class Response {
        final JSONObject data;
        final String revision;
        final long ageMs;
        Response(JSONObject data, String revision, long ageMs) {
            this.data = data; this.revision = revision; this.ageMs = ageMs;
        }
    }

    // Reuse the certificate and TLS context across change requests.
    static final class Client {
        private final URL url;
        private final String authorization;
        private final SSLSocketFactory socketFactory;
        Client(Context context) throws Exception {
            JSONObject config;
            try (InputStream stream = pairingFile(context).openRead()) {
                config = validate(boundedRead(stream, MAX_PAIR_BYTES));
            }
            url = new URL(config.getString("url"));
            authorization = "Bearer " + config.getString("token");
            KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType()); trust.load(null, null);
            trust.setCertificateEntry("paired-mac", certificate(config));
            TrustManagerFactory tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); tm.init(trust);
            SSLContext tls = SSLContext.getInstance("TLS"); tls.init(null, tm.getTrustManagers(), null);
            socketFactory = tls.getSocketFactory();
        }
        Response read(String since) throws Exception { return read(since, false); }
        Response read(String since, boolean fresh) throws Exception {
            HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
            connection.setSSLSocketFactory(socketFactory);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestProperty("Authorization", authorization);
            connection.setRequestProperty("X-T3-Feed-Version", "3");
            if (fresh) connection.setRequestProperty("X-T3-Fresh", "1");
            if (since != null) connection.setRequestProperty("X-T3-Revision", since);
            connection.setConnectTimeout(5000); connection.setReadTimeout(18000);
            boolean complete = false;
            try {
                int code = connection.getResponseCode();
                if (code != 200 && code != 204) throw new IOException("HTTP " + code);
                String age = connection.getHeaderField("X-T3-Age-Ms");
                String revision = connection.getHeaderField("X-T3-Revision");
                long ageMs = age == null ? 0 : Long.parseLong(age);
                JSONObject data = null;
                try (InputStream stream = connection.getInputStream()) {
                    if (code == 200) {
                        data = new JSONObject(new String(boundedRead(stream, 128 * 1024), StandardCharsets.UTF_8));
                        data.getInt("count"); data.getJSONArray("rows");
                    }
                }
                complete = true;
                return new Response(data, revision, ageMs);
            } finally {
                if (!complete) connection.disconnect();
            }
        }
    }

    static JSONObject read(Context context) throws Exception { return new Client(context).read(null).data; }
}
