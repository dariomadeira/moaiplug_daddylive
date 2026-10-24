package com.infomak.moai.daddylive;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tokens del CDN de DaddyLive (port de fetchPlaylistToken del player oficial
 * dembed.st/premiumtv/daddy.php, visto en vivo el 2026-10-24).
 *
 * El CDN solo entrega el playlist con `?t=TOKEN`, un token corto (ttl ~120 s)
 * atado a la IP del espectador, pedido a EDGE/api/playlist-token?slug=premium{id}
 * con Referer/Origin del player. Se renueva en segundo plano desde la mitad
 * del ttl para que un refresco del playlist nunca espere un token nuevo.
 */
final class EdgeTokens {

    private static final Pattern TOKEN_RE =
        Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern TTL_RE =
        Pattern.compile("\"ttl\"\\s*:\\s*(\\d+)");

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
        String url = Config.EDGE + "/api/playlist-token?slug=premium" + channelId;
        Http.Response r = Http.getResponse(url, playerHeaders(), true, Config.TOKEN_TIMEOUT_MS);
        if (r.code != 200 || r.body == null) {
            throw new IllegalStateException("token HTTP " + r.code);
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
            return Config.EDGE + "/premium" + channelId + "/index.m3u8?t="
                + java.net.URLEncoder.encode(token, "UTF-8")
                + "&_=" + System.currentTimeMillis();
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
