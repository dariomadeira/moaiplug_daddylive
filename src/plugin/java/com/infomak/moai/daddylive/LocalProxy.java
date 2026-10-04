package com.infomak.moai.daddylive;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Servidor HTTP local en 127.0.0.1 para desempaquetar segmentos MPEG-TS
 * enmascarados como imágenes WebP/PNG (.image) de TikTok CDN.
 */
public final class LocalProxy implements Runnable {

    private static LocalProxy instance;

    private final ServerSocket serverSocket;
    private final int port;
    private volatile boolean running = true;

    private LocalProxy(ServerSocket serverSocket) {
        this.serverSocket = serverSocket;
        this.port = serverSocket.getLocalPort();
    }

    public static synchronized LocalProxy getInstance() {
        if (instance == null || !instance.running || instance.serverSocket.isClosed()) {
            try {
                ServerSocket ss = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
                instance = new LocalProxy(ss);
                Thread t = new Thread(instance, "Moai-DaddyLive-Proxy");
                t.setDaemon(true);
                t.start();
                System.err.println("[LocalProxy] Servidor iniciado en http://127.0.0.1:" + instance.port);
            } catch (Exception e) {
                throw new RuntimeException("Error iniciando LocalProxy: " + e.getMessage(), e);
            }
        }
        return instance;
    }

    public int getPort() {
        return port;
    }

    public String getPlaylistUrl(String channelId) {
        return "http://127.0.0.1:" + port + "/playlist.m3u8?id=" + channelId;
    }

    @Override
    public void run() {
        while (running && !serverSocket.isClosed()) {
            try {
                Socket client = serverSocket.accept();
                client.setSoTimeout(10000);
                new Thread(new RequestWorker(client)).start();
            } catch (Exception e) {
                if (!running) break;
            }
        }
    }

    private final class RequestWorker implements Runnable {
        private final Socket socket;

        RequestWorker(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try (InputStream is = socket.getInputStream();
                 OutputStream os = socket.getOutputStream()) {

                ByteArrayOutputStream lineBuf = new ByteArrayOutputStream();
                int b;
                String reqLine = null;
                while ((b = is.read()) != -1) {
                    if (b == '\n') {
                        reqLine = new String(lineBuf.toByteArray(), StandardCharsets.UTF_8).trim();
                        break;
                    }
                    if (b != '\r') {
                        lineBuf.write(b);
                    }
                }

                if (reqLine == null || !reqLine.startsWith("GET ")) {
                    sendResponse(os, 400, "text/plain", "Bad Request".getBytes(StandardCharsets.UTF_8));
                    return;
                }

                String path = reqLine.split(" ")[1];
                if (path.startsWith("/playlist.m3u8") || path.startsWith("/playlist")) {
                    handlePlaylist(path, os);
                } else if (path.startsWith("/segment")) {
                    handleSegment(path, os);
                } else {
                    sendResponse(os, 404, "text/plain", "Not Found".getBytes(StandardCharsets.UTF_8));
                }

            } catch (Exception e) {
                // Socket cerrado o timeout
            } finally {
                try {
                    socket.close();
                } catch (Exception ignored) {}
            }
        }

        private void handlePlaylist(String path, OutputStream os) throws Exception {
            String channelId = getParam(path, "id");
            if (channelId == null || channelId.length() == 0) {
                sendResponse(os, 400, "text/plain", "Falta parametro id".getBytes(StandardCharsets.UTF_8));
                return;
            }

            String cdnUrl = "https://edge.cowedd4855ws.sbs/premium" + channelId + "/index.m3u8";
            Map<String, String> headers = new HashMap<String, String>();
            headers.put("User-Agent", Config.USER_AGENT);
            headers.put("Referer", "https://daddyliveplayer.st/");

            Http.Response resp = Http.getResponse(cdnUrl, headers, true);
            if (resp.code != 200 || resp.body == null) {
                sendResponse(os, 502, "text/plain", ("Error CDN " + resp.code).getBytes(StandardCharsets.UTF_8));
                return;
            }

            String playlistText = new String(resp.body, StandardCharsets.UTF_8);
            String[] lines = playlistText.split("\n");
            StringBuilder sb = new StringBuilder();

            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.length() > 0 && !trimmed.startsWith("#")) {
                    String encUrl = URLEncoder.encode(trimmed, "UTF-8");
                    sb.append("http://127.0.0.1:").append(port)
                      .append("/segment?url=").append(encUrl).append("\n");
                } else {
                    sb.append(line).append("\n");
                }
            }

            byte[] outBytes = sb.toString().getBytes(StandardCharsets.UTF_8);
            sendResponse(os, 200, "application/vnd.apple.mpegurl", outBytes);
        }

        private void handleSegment(String path, OutputStream os) throws Exception {
            String targetUrl = getParam(path, "url");
            if (targetUrl == null || targetUrl.length() == 0) {
                sendResponse(os, 400, "text/plain", "Falta parametro url".getBytes(StandardCharsets.UTF_8));
                return;
            }

            Map<String, String> headers = new HashMap<String, String>();
            headers.put("User-Agent", Config.USER_AGENT);
            headers.put("Referer", "https://daddyliveplayer.st/");

            Http.Response resp = Http.getResponse(targetUrl, headers, true);
            if (resp.code != 200 || resp.body == null) {
                sendResponse(os, 502, "text/plain", ("Error segment " + resp.code).getBytes(StandardCharsets.UTF_8));
                return;
            }

            byte[] unwrapped = unwrapBytes(resp.body);
            sendResponse(os, 200, "video/mp2t", unwrapped);
        }

        private void sendResponse(OutputStream os, int code, String contentType, byte[] body) throws Exception {
            String header = "HTTP/1.1 " + code + " OK\r\n" +
                            "Content-Type: " + contentType + "\r\n" +
                            "Content-Length: " + body.length + "\r\n" +
                            "Access-Control-Allow-Origin: *\r\n" +
                            "Connection: close\r\n\r\n";
            os.write(header.getBytes(StandardCharsets.UTF_8));
            os.write(body);
            os.flush();
        }

        private String getParam(String path, String name) {
            int q = path.indexOf('?');
            if (q == -1) return null;
            String query = path.substring(q + 1);
            for (String pair : query.split("&")) {
                String[] kv = pair.split("=");
                if (kv.length == 2 && kv[0].equals(name)) {
                    try {
                        return URLDecoder.decode(kv[1], "UTF-8");
                    } catch (Exception e) {
                        return kv[1];
                    }
                }
            }
            return null;
        }
    }

    private static final byte[] TRAW = "TIKTIKRAW".getBytes(StandardCharsets.UTF_8);
    private static final byte[] TSGZ = "TIKTIKTSGZ".getBytes(StandardCharsets.UTF_8);

    public static byte[] unwrapBytes(byte[] raw) {
        if (raw == null || raw.length < 188) return raw;

        // 1. TRAW (TikTok TS Raw)
        int trawIdx = indexOf(raw, TRAW);
        if (trawIdx != -1) {
            int start = trawIdx + TRAW.length;
            if (start < raw.length && raw[start] == 0x47) {
                byte[] out = new byte[raw.length - start];
                System.arraycopy(raw, start, out, 0, out.length);
                return out;
            }
        }

        // 2. TSGZ (TikTok TS Gzip)
        int tsgzIdx = indexOf(raw, TSGZ);
        if (tsgzIdx != -1) {
            int start = tsgzIdx + TSGZ.length;
            try {
                ByteArrayInputStream bais = new ByteArrayInputStream(raw, start, raw.length - start);
                GZIPInputStream gzis = new GZIPInputStream(bais);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int len;
                while ((len = gzis.read(buf)) != -1) {
                    baos.write(buf, 0, len);
                }
                byte[] out = baos.toByteArray();
                if (out.length > 0 && out[0] == 0x47) {
                    return out;
                }
            } catch (Exception ignored) {}
        }

        // 3. Buscar primer sync byte MPEG-TS 0x47 valido
        int maxScan = Math.min(raw.length - 188, 32768);
        for (int i = 0; i < maxScan; i++) {
            if (raw[i] == 0x47 && raw[i + 188] == 0x47) {
                byte[] out = new byte[raw.length - i];
                System.arraycopy(raw, i, out, 0, out.length);
                return out;
            }
        }

        return raw;
    }

    private static int indexOf(byte[] array, byte[] target) {
        if (target.length == 0) return 0;
        outer:
        for (int i = 0; i < array.length - target.length + 1; i++) {
            for (int j = 0; j < target.length; j++) {
                if (array[i + j] != target[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static final class ByteArrayInputStream extends java.io.InputStream {
        private final byte[] buf;
        private int pos;
        private final int count;

        ByteArrayInputStream(byte[] buf, int offset, int length) {
            this.buf = buf;
            this.pos = offset;
            this.count = Math.min(offset + length, buf.length);
        }

        @Override
        public int read() {
            return (pos < count) ? (buf[pos++] & 0xff) : -1;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (pos >= count) return -1;
            int avail = count - pos;
            if (len > avail) len = avail;
            System.arraycopy(buf, pos, b, off, len);
            pos += len;
            return len;
        }
    }
}
