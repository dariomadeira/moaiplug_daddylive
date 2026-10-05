package com.infomak.moai.daddylive;

import java.io.ByteArrayInputStream;
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
import java.util.zip.DataFormatException;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;

/**
 * Servidor HTTP local en 127.0.0.1 para desempaquetar segmentos MPEG-TS
 * enmascarados del CDN de DaddyLive.
 *
 * Los segmentos llegan como archivos ".image" y el layout vigente (verificado
 * en vivo el 2026-10-05) es:
 *
 *   PNG (IHDR 8-bit RGB/RGBA, no entrelazado)
 *     -> IDAT concatenado -> inflate zlib -> scanlines con filtro PNG
 *     -> se deshace el filtro de cada fila -> pixeles RGB
 *     -> pixeles[0..7] == "TIKTIKPX"
 *     -> pixeles[8..11] = uint32 BE = longitud del bloque gzip
 *     -> pixeles[12 .. 12+n) = gzip( MPEG-TS )
 *
 * Se aceptan tambien los layouts legacy (TRAW / TSGZ / TS tras IEND de PNG /
 * TS en el chunk EXIF de un WebP) replicando el unwrap() del player oficial
 * (daddyliveplayer.st/premiumtv/*.php), para no depender de un unico formato.
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
                    if (b != '\n') {
                        if (b != '\r' && lineBuf.size() < 8192) {
                            lineBuf.write(b);
                        }
                        continue;
                    }
                    String line = new String(lineBuf.toByteArray(), StandardCharsets.UTF_8).trim();
                    lineBuf.reset();
                    if (reqLine == null) {
                        reqLine = line;
                        if (!line.startsWith("GET ")) {
                            break;
                        }
                    } else if (line.length() == 0) {
                        // Fin de cabeceras. Hay que drenarlas antes de responder y
                        // cerrar: si quedan bytes sin leer en el socket, el close()
                        // genera RST y el cliente (ExoPlayer) pierde la respuesta.
                        break;
                    }
                }

                if (reqLine == null || !reqLine.startsWith("GET ")) {
                    sendResponse(os, 400, "Bad Request", "text/plain", "Bad Request".getBytes(StandardCharsets.UTF_8));
                    return;
                }

                String path = reqLine.split(" ")[1];
                if (path.startsWith("/playlist.m3u8") || path.startsWith("/playlist")) {
                    handlePlaylist(path, os);
                } else if (path.startsWith("/segment")) {
                    handleSegment(path, os);
                } else {
                    sendResponse(os, 404, "Not Found", "text/plain", "Not Found".getBytes(StandardCharsets.UTF_8));
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
                sendResponse(os, 400, "Bad Request", "text/plain", "Falta parametro id".getBytes(StandardCharsets.UTF_8));
                return;
            }

            String cdnUrl = "https://edge.cowedd4855ws.sbs/premium" + channelId + "/index.m3u8";
            Http.Response resp;
            try {
                resp = Http.getResponse(cdnUrl, cdnHeaders(), true, Config.PLAYLIST_TIMEOUT_MS);
            } catch (Exception e) {
                sendResponse(os, 502, "Bad Gateway", "text/plain", ("Error playlist: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (resp.code != 200 || resp.body == null || !startsWith(resp.body, "#EXTM3U")) {
                int errCode = (resp.code >= 400 && resp.code < 600) ? resp.code : 404;
                sendResponse(os, errCode, reason(errCode), "text/plain",
                    ("Canal fuera de emision (HTTP " + errCode + ")").getBytes(StandardCharsets.UTF_8));
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
            sendResponse(os, 200, "OK", "application/vnd.apple.mpegurl", outBytes);
        }

        private void handleSegment(String path, OutputStream os) throws Exception {
            String targetUrl = getParam(path, "url");
            if (targetUrl == null || targetUrl.length() == 0) {
                sendResponse(os, 400, "Bad Request", "text/plain", "Falta parametro url".getBytes(StandardCharsets.UTF_8));
                return;
            }

            Http.Response resp;
            try {
                resp = Http.getResponse(targetUrl, cdnHeaders(), true, Config.SEGMENT_TIMEOUT_MS);
            } catch (Exception e) {
                sendResponse(os, 502, "Bad Gateway", "text/plain", ("Error segment: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (resp.code != 200 || resp.body == null) {
                sendResponse(os, 502, "Bad Gateway", "text/plain", ("Error segment " + resp.code).getBytes(StandardCharsets.UTF_8));
                return;
            }

            byte[] unwrapped = unwrapBytes(resp.body);
            if (unwrapped == null || unwrapped.length < 188 || (unwrapped[0] & 0xFF) != 0x47) {
                sendResponse(os, 502, "Bad Gateway", "text/plain",
                    ("Segmento no MPEG-TS (" + resp.body.length + " bytes raw)").getBytes(StandardCharsets.UTF_8));
                return;
            }
            sendResponse(os, 200, "OK", "video/mp2t", unwrapped);
        }

        private Map<String, String> cdnHeaders() {
            Map<String, String> headers = new HashMap<String, String>();
            headers.put("User-Agent", Config.USER_AGENT);
            headers.put("Referer", "https://daddyliveplayer.st/");
            return headers;
        }

        private void sendResponse(OutputStream os, int code, String reasonPhrase,
            String contentType, byte[] body) throws Exception {
            String header = "HTTP/1.1 " + code + " " + reasonPhrase + "\r\n" +
                            "Content-Type: " + contentType + "\r\n" +
                            "Content-Length: " + body.length + "\r\n" +
                            "Access-Control-Allow-Origin: *\r\n" +
                            "Cache-Control: no-store\r\n" +
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
                int eq = pair.indexOf('=');
                if (eq > 0 && pair.substring(0, eq).equals(name)) {
                    String raw = pair.substring(eq + 1);
                    try {
                        return URLDecoder.decode(raw, "UTF-8");
                    } catch (Exception e) {
                        return raw;
                    }
                }
            }
            return null;
        }
    }

    private static String reason(int code) {
        switch (code) {
            case 400: return "Bad Request";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 410: return "Gone";
            case 500: return "Internal Server Error";
            case 502: return "Bad Gateway";
            case 503: return "Service Unavailable";
            default: return "Error";
        }
    }

    // =====================================================================
    // Desempaquetado de segmentos
    // =====================================================================

    private static final byte[] TRAW = ascii("TIKTIKRAW");
    private static final byte[] TSGZ = ascii("TIKTIKTSGZ");
    private static final byte[] TPIX = ascii("TIKTIKPX");

    private static final int TAG_IHDR = 0x49484452;
    private static final int TAG_IDAT = 0x49444154;
    private static final int TAG_IEND = 0x49454E44;

    /** Techo defensivo de buffers intermedios (evita OOM por datos corruptos). */
    private static final int MAX_INTERMEDIATE = 32 * 1024 * 1024;

    /**
     * Devuelve el MPEG-TS real del segmento enmascarado, o el buffer original
     * si no se reconoce ningún layout conocido.
     */
    public static byte[] unwrapBytes(byte[] raw) {
        if (raw == null || raw.length < 188) return raw;

        byte[] ts;

        ts = webpExifTs(raw);
        if (ts != null) return ts;

        ts = pngIendTs(raw);
        if (ts != null) return ts;

        if (isPng(raw)) {
            try {
                ts = pngPixelTs(raw);
                if (ts != null) return ts;
            } catch (Throwable t) {
                // layout de pixeles no soportado: seguir con los demas
            }
        }

        ts = tsAfterMarker(raw, TRAW);
        if (ts != null) return ts;

        ts = gunzipAfterMarker(raw, TSGZ);
        if (ts != null) return ts;

        ts = syncScanTs(raw);
        if (ts != null) return ts;

        System.err.println("[LocalProxy] Layout de segmento no reconocido (" + raw.length + " bytes)");
        return raw;
    }

    /** Layout vigente: pixeles PNG -> "TIKTIKPX" + uint32BE + gzip(TS). */
    private static byte[] pngPixelTs(byte[] png) {
        int w = 0;
        int h = 0;
        int depth = 0;
        int ctype = 0;
        int interlace = 0;
        int[] idatRanges = new int[64];
        int idatCount = 0;

        int off = 8;
        while (off + 8 <= png.length) {
            int len = readIntBE(png, off);
            if (len < 0 || len > png.length - off - 12) return null;
            int type = readIntBE(png, off + 4);
            if (type == TAG_IHDR) {
                if (len < 13) return null;
                w = readIntBE(png, off + 8);
                h = readIntBE(png, off + 12);
                depth = png[off + 16] & 0xFF;
                ctype = png[off + 17] & 0xFF;
                interlace = png[off + 20] & 0xFF;
            } else if (type == TAG_IDAT) {
                if (idatCount * 2 + 2 > idatRanges.length) {
                    int[] bigger = new int[Math.max(idatRanges.length * 2, idatCount * 2 + 2)];
                    System.arraycopy(idatRanges, 0, bigger, 0, idatRanges.length);
                    idatRanges = bigger;
                }
                idatRanges[idatCount * 2] = off + 8;
                idatRanges[idatCount * 2 + 1] = len;
                idatCount++;
            } else if (type == TAG_IEND) {
                break;
            }
            off += 12 + len;
        }

        if (w <= 0 || h <= 0 || idatCount == 0) return null;
        if (depth != 8 || interlace != 0 || (ctype != 2 && ctype != 6)) return null;

        int bpp = ctype == 6 ? 4 : 3;
        long strideL = (long) w * bpp;
        if (strideL > Integer.MAX_VALUE / 4) return null;
        int stride = (int) strideL;
        long totalL = strideL * h;
        if (totalL <= 0 || totalL > MAX_INTERMEDIATE) return null;

        byte[] scanlines = inflateIdat(png, idatRanges, idatCount);
        if (scanlines == null) return null;
        if (scanlines.length < (long) h * (stride + 1)) return null;

        byte[] pixels = unfilter(scanlines, w, h, stride, bpp);
        scanlines = null;
        if (pixels == null) return null;

        if (ctype == 6) {
            byte[] rgb = new byte[w * h * 3];
            int di = 0;
            for (int i = 0; i < pixels.length; i += 4) {
                rgb[di++] = pixels[i];
                rgb[di++] = pixels[i + 1];
                rgb[di++] = pixels[i + 2];
            }
            pixels = rgb;
        }

        byte[] ts = tpixPayloadTs(pixels);
        return ts;
    }

    /** "TIKTIKPX" + uint32BE(len) + gzip(TS) sobre el buffer de pixeles. */
    private static byte[] tpixPayloadTs(byte[] pixels) {
        if (pixels.length < 12) return null;
        for (int i = 0; i < TPIX.length; i++) {
            if (pixels[i] != TPIX[i]) return null;
        }
        long n = readUInt32BE(pixels, 8);
        if (n <= 0 || 12L + n > pixels.length) return null;

        int start = 12;
        int len = (int) n;
        if ((pixels[start] & 0xFF) != 0x1F || (pixels[start + 1] & 0xFF) != 0x8B) return null;

        byte[] ts = gunzip(pixels, start, len);
        if (ts == null || ts.length < 188 || (ts[0] & 0xFF) != 0x47) return null;
        return ts;
    }

    /** WebP con chunk EXIF que contiene el TS (layout alterno). */
    private static byte[] webpExifTs(byte[] b) {
        if (b.length < 16 || !asciiEquals(b, 0, "RIFF") || !asciiEquals(b, 8, "WEBP")) return null;
        int off = 12;
        while (off + 8 <= b.length) {
            int tag = readIntBE(b, off);
            long n = readUInt32LE(b, off + 4);
            off += 8;
            if (n < 0 || n > b.length - off) return null;
            if (tag == asciiInt("EXIF")) {
                if (n >= 188 && (b[off] & 0xFF) == 0x47 && (b[off + 188] & 0xFF) == 0x47) {
                    return slice(b, off, (int) n);
                }
                return null;
            }
            off += (int) n + ((int) n & 1);
        }
        return null;
    }

    /** PNG con el TS concatenado despues del IEND (layout alterno). */
    private static byte[] pngIendTs(byte[] b) {
        if (b.length < 16 || !isPng(b)) return null;
        int off = 8;
        while (off + 8 <= b.length) {
            int len = readIntBE(b, off);
            if (len < 0 || len > b.length - off - 12) return null;
            int type = readIntBE(b, off + 4);
            off += 12 + len;
            if (type == TAG_IEND) {
                if (off < b.length && (b[off] & 0xFF) == 0x47
                        && off + 188 < b.length && (b[off + 188] & 0xFF) == 0x47) {
                    return slice(b, off, b.length - off);
                }
                return null;
            }
        }
        return null;
    }

    /** TIKTIKRAW + TS crudo (layout legacy). */
    private static byte[] tsAfterMarker(byte[] b, byte[] marker) {
        int idx = indexOf(b, marker);
        if (idx == -1) return null;
        int start = idx + marker.length;
        if (start >= b.length || (b[start] & 0xFF) != 0x47) return null;
        return slice(b, start, b.length - start);
    }

    /** TIKTIKTSGZ + gzip(TS) (layout legacy). */
    private static byte[] gunzipAfterMarker(byte[] b, byte[] marker) {
        int idx = indexOf(b, marker);
        if (idx == -1) return null;
        int start = idx + marker.length;
        if (start >= b.length) return null;
        byte[] ts = gunzip(b, start, b.length - start);
        if (ts == null || ts.length < 188 || (ts[0] & 0xFF) != 0x47) return null;
        return ts;
    }

    /**
     * Ultimo recurso: TS crudo embedded en el buffer. Solo se acepta si desde
     * el candidato el resto es un multiplo exacto de 188 bytes, para no
     * confundirse con un 0x47 casual dentro de datos comprimidos (PNG/IDAT).
     */
    private static byte[] syncScanTs(byte[] b) {
        int max = Math.min(b.length - 188, 65536);
        for (int i = 0; i < max; i++) {
            if (b[i] != 0x47 || b[i + 188] != 0x47) continue;
            int len = b.length - i;
            if (len % 188 != 0) continue;
            return slice(b, i, len);
        }
        return null;
    }

    // ------------------------------------------------------------- primitivas

    private static boolean isPng(byte[] b) {
        return b.length >= 8 && (b[0] & 0xFF) == 0x89 && (b[1] & 0xFF) == 0x50
            && (b[2] & 0xFF) == 0x4E && (b[3] & 0xFF) == 0x47;
    }

    /** Inflata todos los IDAT sin concatenarlos (ahorra una copia de ~2.7 MB). */
    private static byte[] inflateIdat(byte[] png, int[] ranges, int count) {
        Inflater inf = new Inflater();
        ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 16);
        byte[] buf = new byte[1 << 14];
        try {
            int idx = 0;
            inf.setInput(png, ranges[0], ranges[1]);
            while (true) {
                int n;
                try {
                    n = inf.inflate(buf);
                } catch (DataFormatException e) {
                    return null;
                }
                if (n > 0) {
                    bos.write(buf, 0, n);
                    if (bos.size() > MAX_INTERMEDIATE) return null;
                }
                if (inf.finished()) break;
                if (inf.needsInput() || inf.needsDictionary()) {
                    idx++;
                    if (idx >= count) break;
                    inf.setInput(png, ranges[idx * 2], ranges[idx * 2 + 1]);
                } else if (n == 0) {
                    break;
                }
            }
        } finally {
            inf.end();
        }
        return bos.toByteArray();
    }

    /** Revierte los filtros PNG fila a fila y devuelve w*h*bpp bytes. */
    private static byte[] unfilter(byte[] src, int w, int h, int stride, int bpp) {
        byte[] out = new byte[stride * h];
        int sp = 0;
        for (int y = 0; y < h; y++) {
            int f = src[sp++] & 0xFF;
            if (f > 4) return null;
            int ro = y * stride;
            int po = ro - stride;
            for (int i = 0; i < stride; i++) {
                int v = src[sp + i] & 0xFF;
                if (f != 0) {
                    int a = i >= bpp ? (out[ro + i - bpp] & 0xFF) : 0;
                    int b = y > 0 ? (out[po + i] & 0xFF) : 0;
                    int c = (y > 0 && i >= bpp) ? (out[po + i - bpp] & 0xFF) : 0;
                    switch (f) {
                        case 1: v += a; break;
                        case 2: v += b; break;
                        case 3: v += (a + b) >> 1; break;
                        default: {
                            int p = a + b - c;
                            int pa = Math.abs(p - a);
                            int pb = Math.abs(p - b);
                            int pc = Math.abs(p - c);
                            v += (pa <= pb && pa <= pc) ? a : (pb <= pc ? b : c);
                            break;
                        }
                    }
                }
                out[ro + i] = (byte) v;
            }
            sp += stride;
        }
        return out;
    }

    /** gunzip sobre un rango del buffer, sin copiar la entrada. */
    private static byte[] gunzip(byte[] src, int off, int len) {
        if (len < 2 || off < 0 || off + len > src.length) return null;
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(len * 2, 1 << 13));
        GZIPInputStream gis = null;
        try {
            gis = new GZIPInputStream(new ByteArrayInputStream(src, off, len));
            byte[] buf = new byte[1 << 14];
            int n;
            while ((n = gis.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > MAX_INTERMEDIATE) return null;
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (gis != null) {
                try {
                    gis.close();
                } catch (Exception ignored) {}
            }
        }
        return bos.toByteArray();
    }

    private static byte[] slice(byte[] b, int off, int len) {
        if (len <= 0) return new byte[0];
        byte[] out = new byte[len];
        System.arraycopy(b, off, out, 0, len);
        return out;
    }

    private static boolean startsWith(byte[] body, String prefix) {
        return asciiEquals(body, 0, prefix);
    }

    private static boolean asciiEquals(byte[] b, int off, String s) {
        if (b == null || off < 0 || off + s.length() > b.length) return false;
        for (int i = 0; i < s.length(); i++) {
            if ((b[off + i] & 0xFF) != s.charAt(i)) return false;
        }
        return true;
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static int asciiInt(String s) {
        return ((s.charAt(0) & 0xFF) << 24) | ((s.charAt(1) & 0xFF) << 16)
            | ((s.charAt(2) & 0xFF) << 8) | (s.charAt(3) & 0xFF);
    }

    private static int readIntBE(byte[] b, int off) {
        if (off < 0 || off + 4 > b.length) return -1;
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
            | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static long readUInt32BE(byte[] b, int off) {
        if (off < 0 || off + 4 > b.length) return -1L;
        return ((long) (b[off] & 0xFF) << 24) | ((long) (b[off + 1] & 0xFF) << 16)
            | ((long) (b[off + 2] & 0xFF) << 8) | (long) (b[off + 3] & 0xFF);
    }

    private static long readUInt32LE(byte[] b, int off) {
        if (off < 0 || off + 4 > b.length) return -1L;
        return (long) (b[off] & 0xFF) | ((long) (b[off + 1] & 0xFF) << 8)
            | ((long) (b[off + 2] & 0xFF) << 16) | ((long) (b[off + 3] & 0xFF) << 24);
    }

    private static int indexOf(byte[] array, byte[] target) {
        if (target.length == 0) return 0;
        int limit = array.length - target.length;
        outer:
        for (int i = 0; i <= limit; i++) {
            for (int j = 0; j < target.length; j++) {
                if (array[i + j] != target[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}