package com.infomak.moai.daddylive;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.cert.X509Certificate;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** HTTP mínimo con HttpURLConnection (mismo patrón que moaiplug_telefe). */
final class Http {

    private static final SSLSocketFactory TRUST_ALL_SSL;
    private static final HostnameVerifier TRUST_ALL_HOSTS = new HostnameVerifier() {
        @Override
        public boolean verify(String hostname, SSLSession session) {
            return true;
        }
    };

    static {
        SSLSocketFactory sf = null;
        try {
            TrustManager[] trustAll = new TrustManager[] {
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] c, String a) {}
                    public void checkServerTrusted(X509Certificate[] c, String a) {}
                }
            };
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAll, new java.security.SecureRandom());
            sf = sc.getSocketFactory();
        } catch (Exception ignored) {
        }
        TRUST_ALL_SSL = sf;
    }

    private Http() {
    }

    static final class Response {
        final int code;
        final String headers;
        final byte[] body;

        Response(int code, String headers, byte[] body) {
            this.code = code;
            this.headers = headers;
            this.body = body;
        }
    }

    /** GET y devuelve el body completo como String UTF-8. Lanza si no es 2xx. */
    static String get(String url, Map<String, String> headers) {
        Response r = getResponse(url, headers, true);
        if (r.code < 200 || r.code >= 300) {
            throw new IllegalStateException("HTTP " + r.code + " en " + url);
        }
        return new String(r.body, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** GET devolviendo la Response completa (no lanza por código de estado). */
    static Response getResponse(String url, Map<String, String> headers, boolean readBody) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            if (conn instanceof HttpsURLConnection && TRUST_ALL_SSL != null) {
                HttpsURLConnection sconn = (HttpsURLConnection) conn;
                sconn.setSSLSocketFactory(TRUST_ALL_SSL);
                sconn.setHostnameVerifier(TRUST_ALL_HOSTS);
            }
            conn.setConnectTimeout(Config.CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(Config.READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", Config.USER_AGENT);
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            conn.connect();
            int code = conn.getResponseCode();
            byte[] body;
            InputStream in = code >= 200 && code < 300
                ? conn.getInputStream()
                : conn.getErrorStream();
            if (readBody) {
                body = readFully(in);
            } else {
                body = new byte[0];
            }
            return new Response(code, headersToString(conn), body);
        } catch (IOException e) {
            throw new IllegalStateException("Error HTTP en " + url + ": "
                + e.getMessage(), e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String headersToString(HttpURLConnection conn) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; ; i++) {
            String k = conn.getHeaderFieldKey(i);
            String v = conn.getHeaderField(i);
            if (k == null && v == null) {
                break;
            }
            sb.append(k != null ? k + ": " + v : v).append('\n');
        }
        return sb.toString();
    }

    private static byte[] readFully(InputStream in) throws IOException {
        if (in == null) {
            return new byte[0];
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
        byte[] buf = new byte[8192];
        int n;
        try {
            while ((n = in.read(buf)) != -1) {
                bos.write(buf, 0, n);
                if (bos.size() > 2 * 1024 * 1024) {
                    break;
                }
            }
        } finally {
            in.close();
        }
        return bos.toByteArray();
    }
}