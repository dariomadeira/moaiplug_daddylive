package com.infomak.moai.daddylive;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tokens del CDN de DaddyLive (port de fetchPlaylistToken del player oficial
 * dembed.st/premiumtv/daddy.php, visto en vivo el 2026-11-02).
 *
 * El CDN solo entrega el playlist con `?t=TOKEN`, un token corto (ttl ~120 s)
 * atado a la IP del espectador, pedido a EDGE/api/playlist-token?slug=premium{id}
 * con Referer/Origin del player. Se renueva en segundo plano desde la mitad
 * del ttl para que un refresco del playlist nunca espere un token nuevo.
 *
 * El dominio del CDN rota (edge.cowedd4855ws.sbs -> edge.wedd48c.top), así que
 * no se usa una constante fija: se lee `const EDGE = "..."` de la página del
 * player y se cachea EDGE_TTL_MS. Si una petición falla se descarta el dominio
 * y se vuelve a leer, de modo que un cambio de EDGE no vuelve a romper el
 * plugin.
 */
final class EdgeTokens {

    private static final Pattern TOKEN_RE =
        Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern TTL_RE =
        Pattern.compile("\"ttl\"\\s*:\\s*(\\d+)");
    private static final Pattern EDGE_RE =
        Pattern.compile("(?:const\\s+)?EDGE\\s*=\\s*[\"'](https?://[^\"']+)[\"']");
    private static final Pattern SRC_RE =
        Pattern.compile("(?:const\\s+)?SRC\\s*=\\s*[\"'](https?://[^/\"']+)");

    private static final class Token {
        final String value;
        final long renewAt;
        final long expiresAt;

        Token(String value, long ttlSeconds) {
            long now = System.currentTimeMillis();
            this.value = value;
            this.renewAt = now + ttlSeconds * 500L;
            this.expiresAt = now + ttlSeconds * 1000L - 2000L;
        }
    }

    private static final ConcurrentHashMap<String, Token> TOKENS =
        new ConcurrentHashMap<String, Token>();
    private static final ConcurrentHashMap<String, Boolean> RENEWING =
        new ConcurrentHashMap<String, Boolean>();

    /** EDGE vigente y cuándo se leyó por última vez. */
    private static volatile String edge = Config.EDGE;
    private static volatile long edgeAt = 0L;
    /** No reintentar el descubrimiento antes de este instante. */
    private static volatile long edgeRetryAt = 0L;

    private EdgeTokens() {
    }

    /** Headers que el CDN exige en token y playlist. */
    static Map<String, String> playerHeaders() {
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("User-Agent", Config.USER_AGENT);
        h.put("Referer", Config.PLAYER_ORIGIN + "/");
        h.put("Origin", Config.PLAYER_ORIGIN);
        return h;
    }

    /**
     * Token vigente del canal. Con [forceNew] descarta el actual (el CDN lo
     * rechazó). Lanza si no se pudo obtener ninguno.
     */
    static String get(final String channelId, boolean forceNew) {
        long now = System.currentTimeMillis();
        Token t = forceNew ? null : TOKENS.get(channelId);
        if (t != null && now < t.expiresAt) {
            if (now >= t.renewAt && RENEWING.putIfAbsent(channelId, Boolean.TRUE) == null) {
                Thread r = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            fetch(channelId);
                        } catch (Exception ignored) {
                            // se sigue usando el actual hasta que venza
                        } finally {
                            RENEWING.remove(channelId);
                        }
                    }
                }, "Moai-DaddyLive-Token");
                r.setDaemon(true);
                r.start();
            }
            return t.value;
        }
        return fetch(channelId).value;
    }

    private static Token fetch(String channelId) {
        return fetch(channelId, true);
    }

    /**
     * Token del canal. Si el EDGE actual no responde (dominio rotado o caído),
     * se descubre uno nuevo desde la página del player y se reintenta una vez.
     */
    private static Token fetch(String channelId, boolean allowRediscover) {
        String base = edge();
        String url = base + "/api/playlist-token?slug=premium" + channelId;
        Http.Response r;
        try {
            r = Http.getResponse(url, playerHeaders(), true, Config.TOKEN_TIMEOUT_MS);
        } catch (RuntimeException e) {
            if (allowRediscover) {
                invalidateEdge();
                return fetch(channelId, false);
            }
            throw e;
        }
        if (r.code != 200 || r.body == null) {
            if (allowRediscover) {
                invalidateEdge();
                return fetch(channelId, false);
            }
            throw new IllegalStateException("token HTTP " + r.code + " en " + base);
        }
        String json = new String(r.body, java.nio.charset.StandardCharsets.UTF_8);
        Matcher tm = TOKEN_RE.matcher(json);
        if (!tm.find()) {
            throw new IllegalStateException("token ausente");
        }
        long ttl = 120;
        Matcher ttlm = TTL_RE.matcher(json);
        if (ttlm.find()) {
            try {
                ttl = Math.max(20, Long.parseLong(ttlm.group(1)));
            } catch (NumberFormatException ignored) {
            }
        }
        Token t = new Token(tm.group(1), ttl);
        TOKENS.put(channelId, t);
        return t;
    }

    /** URL del playlist del canal con el token y un anti-cache, como el player oficial. */
    static String playlistUrl(String channelId, String token) {
        try {
            return edge() + "/premium" + channelId + "/index.m3u8?t="
                + java.net.URLEncoder.encode(token, "UTF-8")
                + "&_=" + System.currentTimeMillis();
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ EDGE

    /** Descarta el EDGE actual: la próxima petición lo redescubre. */
    static void invalidateEdge() {
        edgeAt = 0L;
        edgeRetryAt = 0L;
        TOKENS.clear();
    }

    /** EDGE vigente, redescubriéndolo de la página del player si venció. */
    static String edge() {
        return edgeFor(null);
    }

    private static String edgeFor(String channelId) {
        long now = System.currentTimeMillis();
        String current = edge;
        if (current != null && current.length() > 0 && now - edgeAt < Config.EDGE_TTL_MS) {
            return current;
        }
        if (now < edgeRetryAt) {
            return current != null && current.length() > 0 ? current : Config.EDGE;
        }
        return discoverEdge(channelId);
    }

    /**
     * Lee `const EDGE = "..."` (o el host de `const SRC = "..."`) de la página
     * del player. Si no se puede, conserva el último EDGE conocido.
     */
    private static synchronized String discoverEdge(String channelId) {
        long now = System.currentTimeMillis();
        if (edge != null && edge.length() > 0 && now - edgeAt < Config.EDGE_TTL_MS) {
            return edge;
        }
        if (now < edgeRetryAt) {
            return edge != null && edge.length() > 0 ? edge : Config.EDGE;
        }

        String found = null;
        String id = channelId != null && channelId.length() > 0 ? channelId : "44";
        try {
            Map<String, String> h = playerHeaders();
            h.put("Referer", Config.SITE_ORIGIN + "/");
            Http.Response r = Http.getResponse(Config.PLAYER_PAGE + id, h, true,
                Config.PLAYLIST_TIMEOUT_MS);
            if (r.code == 200 && r.body != null) {
                String html = new String(r.body, java.nio.charset.StandardCharsets.UTF_8);
                Matcher m = EDGE_RE.matcher(html);
                if (m.find()) {
                    found = m.group(1);
                } else {
                    Matcher s = SRC_RE.matcher(html);
                    if (s.find()) {
                        found = s.group(1);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[EdgeTokens] no se pudo leer el EDGE: " + e.getMessage());
        }

        if (found == null || found.length() == 0) {
            // Sin dato nuevo: se sigue usando el anterior y se espera un minuto
            // para no pedir la página del player en cada canal.
            edgeRetryAt = System.currentTimeMillis() + Config.EDGE_RETRY_MS;
            return edge != null && edge.length() > 0 ? edge : Config.EDGE;
        }

        if (found.endsWith("/")) {
            found = found.substring(0, found.length() - 1);
        }
        if (!found.equals(edge)) {
            System.err.println("[EdgeTokens] EDGE vigente: " + found);
        }
        edge = found;
        edgeAt = System.currentTimeMillis();
        edgeRetryAt = 0L;
        return found;
    }
}
