package org.t3activity.companion;

import android.content.Context;
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
    static JSONObject snapshot;
    static long refreshed;
    static final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    static final int MAX_PAIR_BYTES = 32 * 1024;

    static void publish(JSONObject data) {
        snapshot = data;
        refreshed = System.currentTimeMillis();
        for (Runnable listener : listeners) listener.run();
    }

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

    static JSONObject read(Context context) throws Exception {
        JSONObject config;
        try (InputStream stream = pairingFile(context).openRead()) {
            config = validate(boundedRead(stream, MAX_PAIR_BYTES));
        }
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType()); trust.load(null, null);
        trust.setCertificateEntry("paired-mac", certificate(config));
        TrustManagerFactory tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); tm.init(trust);
        SSLContext tls = SSLContext.getInstance("TLS"); tls.init(null, tm.getTrustManagers(), null);
        HttpsURLConnection connection = (HttpsURLConnection) new URL(config.getString("url")).openConnection();
        connection.setSSLSocketFactory(tls.getSocketFactory());
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Authorization", "Bearer " + config.getString("token"));
        connection.setConnectTimeout(5000); connection.setReadTimeout(12000);
        try (InputStream stream = connection.getInputStream()) {
            return new JSONObject(new String(boundedRead(stream, 128 * 1024), StandardCharsets.UTF_8));
        } finally { connection.disconnect(); }
    }
}
