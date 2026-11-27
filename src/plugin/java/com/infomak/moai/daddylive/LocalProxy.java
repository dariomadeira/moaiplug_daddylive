package com.infomak.moai.daddylive;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.DataFormatException;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;

/**
 * Servidor HTTP local en 127.0.0.1 que sirve los canales de DaddyLive al
 * reproductor (v0.6.0, fuentes vistas en vivo el 2026-11-21).
 *
 * Playlist (`/playlist.m3u8?id=N`):
 *   1. API de tvnow247: resuelve el mismo id y entrega un playlist propio con
 *      los mismos segmentos. Va primero porque sus dominios resolvieron incluso
 *      donde el DNS del televisor no ve `thedaddy.*` ni el CDN cast.
 *   2. Si falla: player "cast" de DaddyLive ({@link CastResolver}), vía directa
 *      con HLS firmado (~6 h).
 *   3. Si falla: CDN de dembed con token ({@link EdgeTokens}), hoy detrás de
 *      Cloudflare Turnstile.
 *   Si un refresco falla se sirve el último playlist bueno unos segundos, para
 *   que el reproductor no corte por un error momentáneo.
 *
 * Segmentos (`/segment?url=&ref=`): se bajan con reintentos, con el Referer
 * del embed que corresponda (el CDN cast lo exige), se desempaquetan de a
 * uno (como el player oficial; acota memoria y CPU) y quedan en un LRU chico.
 * Al servir el playlist se precargan los más nuevos, así el reproductor los
 * encuentra listos en vez de esperar descarga + desempaquetado.
 *
 * Los segmentos llegan como archivos ".image" y el layout vigente es:
 *
 *   PNG (IHDR 8-bit RGB/RGBA, no entrelazado)
 *     -> IDAT concatenado -> inflate zlib -> scanlines con filtro PNG
 *     -> se deshace el filtro de cada fila -> pixeles RGB
 *     -> pixeles[0..7] == "TIKTIKPX"
 *     -> pixeles[8..11] = uint32 BE = longitud del bloque gzip
 *     -> pixeles[12 .. 12+n) = gzip( MPEG-TS )
 *
 * Se aceptan tambien los layouts legacy (TRAW / TSGZ / TS tras IEND de PNG /
 * TS en el chunk EXIF de un WebP) replicando el unwrap() del player oficial,
 * y el de tvnow247 (PNG decorativo cuyo unico IDAT lleva TS crudo).
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
                Thread worker = new Thread(new RequestWorker(client), "Moai-DaddyLive-Req");
                worker.setDaemon(true);
                worker.start();
            } catch (Exception e) {
                if (!running) break;
            }
        }
    }

    // =====================================================================
    // Playlist del canal
    // =====================================================================

    /** Playlist de origen ya validado (#EXTM3U) y la URL contra la que se resuelven rutas relativas. */
    static final class Upstream {
        final String body;
        final String baseUrl;
        /** Referer que exigen el playlist y los segmentos de esta fuente. */
        final String referer;

        Upstream(String body, String baseUrl, String referer) {
            this.body = body;
            this.baseUrl = baseUrl;
            this.referer = referer;
        }
    }

    /** Resultado de buscar el playlist: [upstream] o el último código de error. */
    static final class PlaylistResult {
        final Upstream upstream;
        final int errorCode;
        final String error;

        PlaylistResult(Upstream upstream, int errorCode, String error) {
            this.upstream = upstream;
            this.errorCode = errorCode;
            this.error = error;
        }
    }

    private static final Pattern TVNOW_URL_RE =
        Pattern.compile("\"(?:proxyPlaylistUrl|m3u8)\"\\s*:\\s*\"([^\"]+)\"");

    /** Último playlist bueno por canal (respaldo ante un refresco fallido). */
    private static final ConcurrentHashMap<String, Object[]> LAST_GOOD =
        new ConcurrentHashMap<String, Object[]>();
    /** URL de tvnow247 ya resuelta por canal. */
    private static final ConcurrentHashMap<String, String> TVNOW_URLS =
        new ConcurrentHashMap<String, String>();

    /**
     * Busca el playlist del canal. tvnow247 va PRIMERO: sus dominios resuelven
     * incluso donde `thedaddy.*` y el CDN cast caen en el DNS del televisor (y
     * una resolución lenta de esos dominios puede consumir el timeout de 20 s
     * del host antes de llegar al respaldo). El player cast queda como segunda
     * fuente y el CDN con token como último recurso. No espera más allá de
     * [deadline].
     */
    static PlaylistResult fetchPlaylist(String channelId, long deadline) {
        long tvDeadline = Math.min(deadline,
            System.currentTimeMillis() + Config.TVNOW_BUDGET_MS);
        Upstream tv = fetchTvnow(channelId, tvDeadline);
        if (tv != null) {
            return ok(channelId, mediaPlaylist(tv, deadline));
        }

        long castDeadline = Math.min(deadline,
            System.currentTimeMillis() + Config.CAST_BUDGET_MS);
        Upstream cast = fetchCast(channelId, castDeadline);
        if (cast != null) {
            return ok(channelId, mediaPlaylist(cast, deadline));
        }

        PlaylistResult edge = fetchEdge(channelId, deadline);
        if (edge.upstream != null) {
            return ok(channelId, mediaPlaylist(edge.upstream, deadline));
        }
        return edge;
    }

    /**
     * Si la fuente devolvió un master playlist, baja la primera variante para
     * que el proxy sólo tenga que reescribir segmentos.
     */
    private static Upstream mediaPlaylist(Upstream u, long deadline) {
        if (u.body.indexOf("#EXT-X-STREAM-INF") < 0) {
            return u;
        }
        String variant = null;
        for (String line : u.body.split("\n")) {
            String t = line.trim();
            if (t.length() > 0 && !t.startsWith("#")) {
                variant = t;
                break;
            }
        }
        if (variant == null) {
            return u;
        }
        try {
            String abs = URI.create(u.baseUrl).resolve(variant).toString();
            Http.Response r = Http.getResponse(abs, refererHeaders(u.referer), true,
                Config.PLAYLIST_TIMEOUT_MS);
            if (r.code == 200 && startsWith(r.body, "#EXTM3U")) {
                return new Upstream(new String(r.body, StandardCharsets.UTF_8), abs, u.referer);
            }
        } catch (Exception ignored) {
            // se sirve el master tal cual; el reproductor no podrá seguirlo
        }
        return u;
    }

    private static Map<String, String> refererHeaders(String referer) {
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("User-Agent", Config.USER_AGENT);
        h.put("Referer", referer != null ? referer : Config.PLAYER_ORIGIN + "/");
        String origin = originOf(referer);
        if (origin != null) {
            h.put("Origin", origin);
        }
        return h;
    }

    /**
     * Player cast: reutiliza la URL firmada del canal (~6 h) y sólo repite la
     * cadena de páginas cuando vence o el CDN la rechaza.
     */
    private static Upstream fetchCast(String channelId, long deadline) {
        CastResolver.Source source = CastResolver.cached(channelId);
        boolean fresh = false;
        if (source == null) {
            source = CastResolver.resolve(channelId, deadline);
            fresh = true;
            if (source == null) {
                return null;
            }
        }

        for (int i = 0; i < 2 && System.currentTimeMillis() < deadline; i++) {
            try {
                Http.Response r = Http.getResponse(source.playlistUrl,
                    CastResolver.headers(source), true, Config.PLAYLIST_TIMEOUT_MS);
                if (r.code == 200 && startsWith(r.body, "#EXTM3U")) {
                    String text = new String(r.body, StandardCharsets.UTF_8);
                    // Canal frío: el player oficial reintenta si aún hay pocos segmentos.
                    if (fresh && i == 0 && countSegments(text) < 6) {
                        sleepUntil(700L, deadline);
                        continue;
                    }
                    return new Upstream(text, source.playlistUrl, source.referer);
                }
                if (r.code == 401 || r.code == 403 || r.code == 404) {
                    // URL vencida o canal caído: la próxima vez se resuelve de nuevo.
                    CastResolver.invalidate(channelId);
                    return null;
                }
            } catch (Exception e) {
                // Fallo transitorio: se conserva la fuente para el próximo refresco.
                return null;
            }
        }
        return null;
    }

    private static int countSegments(String playlist) {
        int n = 0;
        for (String line : playlist.split("\n")) {
            String t = line.trim();
            if (t.length() > 0 && !t.startsWith("#")) {
                n++;
            }
        }
        return n;
    }

    /** CDN de dembed con token por espectador (hoy detrás de Turnstile). */
    private static PlaylistResult fetchEdge(String channelId, long deadline) {
        // Un canal que ya se estaba sirviendo no está frío: menos espera.
        Object[] last = LAST_GOOD.get(channelId);
        boolean warm = last != null
            && System.currentTimeMillis() - (Long) last[1] < 60000L;
        int tries = warm ? 2 : Config.COLD_START_TRIES;

        int code = 0;
        String error = null;
        boolean newToken = false;
        // Con el token negado, los reintentos sólo comían el tiempo que
        // necesita el respaldo (antes se llegaba a tvnow247 sin presupuesto).
        if (EdgeTokens.tokenDenied()) tries = 0;
        for (int i = 0; i < tries && System.currentTimeMillis() < deadline; i++) {
            if (i > 0 && EdgeTokens.tokenDenied()) break;
            try {
                String token = EdgeTokens.get(channelId, newToken);
                newToken = false;
                String url = EdgeTokens.playlistUrl(channelId, token);
                Http.Response r = Http.getResponse(url, EdgeTokens.playerHeaders(), true,
                    Config.PLAYLIST_TIMEOUT_MS);
                code = r.code;
                if (r.code == 200 && startsWith(r.body, "#EXTM3U")) {
                    return ok(channelId, new Upstream(new String(r.body, StandardCharsets.UTF_8),
                        url, Config.PLAYER_ORIGIN + "/"));
                }
                if (r.code == 401 || r.code == 403) {
                    // Token rechazado o EDGE rotado: se pide uno nuevo.
                    EdgeTokens.invalidateEdge();
                    newToken = true;
                    continue;
                }
            } catch (Exception e) {
                error = e.getMessage();
                // DNS/connect/timeout: el EDGE pudo cambiar de dominio.
                EdgeTokens.invalidateEdge();
            }
            sleepUntil(Config.COLD_START_DELAY_MS, deadline);
        }
        return new PlaylistResult(null, code == 0 ? 502 : code, error);
    }

    private static PlaylistResult ok(String channelId, Upstream u) {
        LAST_GOOD.put(channelId, new Object[] {u, System.currentTimeMillis()});
        return new PlaylistResult(u, 200, null);
    }

    private static Upstream fetchTvnow(String channelId, long deadline) {
        for (int i = 0; i < 2 && System.currentTimeMillis() < deadline; i++) {
            try {
                String url = TVNOW_URLS.get(channelId);
                if (url == null || i > 0) {
                    Map<String, String> h = new LinkedHashMap<String, String>();
                    h.put("Referer", Config.TVNOW_ORIGIN + "/");
                    h.put("Origin", Config.TVNOW_ORIGIN);
                    String json = Http.get(Config.TVNOW_RESOLVE_API + channelId, h);
                    Matcher m = TVNOW_URL_RE.matcher(json);
                    if (!m.find()) return null;
                    url = m.group(1).replace("\\/", "/");
                    TVNOW_URLS.put(channelId, url);
                }
                Map<String, String> h = new LinkedHashMap<String, String>();
                h.put("Referer", Config.TVNOW_ORIGIN + "/");
                Http.Response r = Http.getResponse(url, h, true, Config.PLAYLIST_TIMEOUT_MS);
                if (r.code == 200 && startsWith(r.body, "#EXTM3U")) {
                    return new Upstream(new String(r.body, StandardCharsets.UTF_8), url,
                        Config.TVNOW_ORIGIN + "/");
                }
            } catch (Exception ignored) {
                // se reintenta resolviendo la URL de nuevo
            }
        }
        return null;
    }

    private static void sleepUntil(long ms, long deadline) {
        long wait = Math.min(ms, deadline - System.currentTimeMillis());
        if (wait <= 0) return;
        try {
            Thread.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // =====================================================================
    // Segmentos: descarga con reintentos, desempaquetado serializado y LRU
    // =====================================================================

    private static final ExecutorService DOWNLOADS =
        Executors.newFixedThreadPool(Config.DOWNLOAD_THREADS, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "Moai-DaddyLive-Seg");
                t.setDaemon(true);
                return t;
            }
        });
    private static final Object UNWRAP_LOCK = new Object();
    private static final Map<String, Future<byte[]>> SEGMENTS =
        new LinkedHashMap<String, Future<byte[]>>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Future<byte[]>> eldest) {
                return size() > Config.SEGMENT_CACHE_SIZE;
            }
        };

    /** Segmento desempaquetado (en curso o listo). Lo baja si no estaba. */
    static Future<byte[]> segment(final String url, final String referer) {
        synchronized (SEGMENTS) {
            Future<byte[]> f = SEGMENTS.get(url);
            if (f == null) {
                f = DOWNLOADS.submit(new Callable<byte[]>() {
                    @Override
                    public byte[] call() {
                        return downloadSegment(url, referer);
                    }
                });
                SEGMENTS.put(url, f);
            }
            return f;
        }
    }

    private static void forget(String url) {
        synchronized (SEGMENTS) {
            SEGMENTS.remove(url);
        }
    }

    private static byte[] downloadSegment(String url, String referer) {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("User-Agent", Config.USER_AGENT);
        headers.put("Referer", referer != null && referer.length() > 0
            ? referer : Config.PLAYER_ORIGIN + "/");
        String origin = originOf(referer);
        if (origin != null) {
            headers.put("Origin", origin);
        }
        String last = null;
        for (int i = 0; i <= Config.SEGMENT_RETRIES; i++) {
            try {
                Http.Response r = Http.getResponse(url, headers, true, Config.SEGMENT_TIMEOUT_MS);
                if (r.code == 200 && r.body != null) {
                    byte[] ts;
                    synchronized (UNWRAP_LOCK) {
                        ts = unwrapBytes(r.body);
                    }
                    if (ts != null && ts.length >= 188 && (ts[0] & 0xFF) == 0x47) {
                        return ts;
                    }
                    last = "segmento no MPEG-TS (" + r.body.length + " bytes)";
                } else {
                    last = "HTTP " + r.code;
                }
            } catch (Exception e) {
                last = e.getMessage();
            }
            try {
                Thread.sleep(400L * (i + 1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new IllegalStateException(last != null ? last : "segmento fallido");
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

            PlaylistResult pr = fetchPlaylist(channelId,
                System.currentTimeMillis() + Config.RESOLVE_BUDGET_MS);
            Upstream up = pr.upstream;
            if (up == null) {
                Object[] last = LAST_GOOD.get(channelId);
                if (last != null && System.currentTimeMillis() - (Long) last[1] < Config.STALE_PLAYLIST_MS) {
                    up = (Upstream) last[0];
                }
            }
            if (up == null) {
                int errCode = (pr.errorCode >= 400 && pr.errorCode < 600) ? pr.errorCode : 502;
                sendResponse(os, errCode, reason(errCode), "text/plain",
                    ("Canal fuera de emision (HTTP " + errCode + ")").getBytes(StandardCharsets.UTF_8));
                return;
            }

            URI base = URI.create(up.baseUrl);
            String referer = up.referer != null ? up.referer : Config.PLAYER_ORIGIN + "/";
            String[] lines = up.body.split("\n");
            StringBuilder sb = new StringBuilder();
            List<String> segments = new ArrayList<String>();

            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.length() > 0 && !trimmed.startsWith("#")) {
                    String abs = base.resolve(trimmed).toString();
                    segments.add(abs);
                    sb.append("http://127.0.0.1:").append(port)
                      .append("/segment?url=").append(URLEncoder.encode(abs, "UTF-8"))
                      .append("&ref=").append(URLEncoder.encode(referer, "UTF-8")).append("\n");
                } else {
                    sb.append(line).append("\n");
                }
            }

            // Precarga de los más nuevos: es lo que el reproductor pide a continuación.
            for (int i = Math.max(0, segments.size() - Config.PREFETCH_SEGMENTS); i < segments.size(); i++) {
                segment(segments.get(i), referer);
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

            byte[] ts;
            String referer = getParam(path, "ref");
            try {
                long wait = (long) Config.SEGMENT_TIMEOUT_MS * (Config.SEGMENT_RETRIES + 1) + 5000L;
                ts = segment(targetUrl, referer).get(wait, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                forget(targetUrl);
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                sendResponse(os, 502, "Bad Gateway", "text/plain",
                    ("Error segment: " + cause.getMessage()).getBytes(StandardCharsets.UTF_8));
                return;
            }
            sendResponse(os, 200, "OK", "video/mp2t", ts);
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

    /** `https://host[:port]` del Referer, o null si no se puede parsear. */
    private static String originOf(String referer) {
        if (referer == null || referer.length() == 0) {
            return null;
        }
        try {
            URI u = URI.create(referer);
            String scheme = u.getScheme();
            String host = u.getHost();
            if (scheme == null || host == null) {
                return null;
            }
            int port = u.getPort();
            return scheme + "://" + host + (port > 0 ? ":" + port : "");
        } catch (Exception e) {
            return null;
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

        ts = pngIdatRawTs(raw);
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

    /**
     * Layout de tvnow247: PNG decorativo (IHDR alto 1) cuyo unico chunk IDAT
     * lleva el MPEG-TS crudo, sin comprimir. Se valida el sync 0x47 cada 188
     * bytes para no aceptar un PNG normal (cuyo IDAT empieza con zlib 0x78).
     */
    private static byte[] pngIdatRawTs(byte[] b) {
        if (b.length < 16 || !isPng(b)) return null;
        int off = 8;
        ByteArrayOutputStream bos = null;
        while (off + 8 <= b.length) {
            int len = readIntBE(b, off);
            if (len < 0 || len > b.length - off - 12) return null;
            int type = readIntBE(b, off + 4);
            if (type == TAG_IDAT && len > 0) {
                int start = off + 8;
                if (bos == null) {
                    if ((b[start] & 0xFF) != 0x47) return null;
                    bos = new ByteArrayOutputStream(len);
                }
                bos.write(b, start, len);
                if (bos.size() > MAX_INTERMEDIATE) return null;
            } else if (type == TAG_IEND) {
                break;
            }
            off += 12 + len;
        }
        if (bos == null) return null;
        byte[] ts = bos.toByteArray();
        if (ts.length < 188 || ts.length % 188 != 0) return null;
        for (int i = 0; i < ts.length; i += 188) {
            if ((ts[i] & 0xFF) != 0x47) return null;
        }
        return ts;
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