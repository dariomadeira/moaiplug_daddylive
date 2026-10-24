package com.infomak.moai.daddylive;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolver del catálogo 24/7 de DaddyLive (v0.5.0, fuentes vistas en vivo el
 * 2026-10-24).
 *
 * El id numérico del catálogo sigue siendo el canal del CDN (premium{id}).
 * El CDN ahora exige un token por espectador ({@link EdgeTokens}); si no
 * responde, tvnow247 resuelve el mismo id. Ambas fuentes entregan segmentos
 * enmascarados, así que el resultado siempre es el playlist del
 * {@link LocalProxy}, que elige la fuente en cada refresco.
 *
 * La cadena anterior (daddylive.*\/player/embed.php -> PLAYERS) quedó vacía
 * en la web nueva y ya no se usa.
 */
final class DaddyliveResolver {

    static final class Result {
        final String url;
        final Map<String, String> headers;
        final long ttlMs;

        Result(String url, Map<String, String> headers, long ttlMs) {
            this.url = url;
            this.headers = headers;
            this.ttlMs = ttlMs;
        }
    }

    DaddyliveResolver() {
    }

    Result resolve(String channelId) {
        if (channelId == null || channelId.length() == 0 || !isNumeric(channelId)) {
            throw new IllegalStateException("Daddylive: id de canal inválido: "
                + channelId + " (solo id numérico en este layout)");
        }
        long deadline = System.currentTimeMillis() + Config.RESOLVE_BUDGET_MS;
        LocalProxy.PlaylistResult pr = LocalProxy.fetchPlaylist(channelId, deadline);
        if (pr.upstream == null) {
            throw new IllegalStateException("Daddylive: canal " + channelId
                + " fuera de emisión (HTTP " + pr.errorCode + ")"
                + (pr.error != null ? " (" + pr.error + ")" : ""));
        }
        String proxyUrl = LocalProxy.getInstance().getPlaylistUrl(channelId);
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("User-Agent", Config.USER_AGENT);
        System.err.println("[DaddyLive] canal " + channelId + " listo: " + proxyUrl);
        return new Result(proxyUrl, headers, Config.TTL_CAP_MS);
    }

    private static boolean isNumeric(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return s.length() > 0;
    }
}
