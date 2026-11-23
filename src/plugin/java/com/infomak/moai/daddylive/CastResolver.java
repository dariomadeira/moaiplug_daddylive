package com.infomak.moai.daddylive;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resuelve el player "cast" de DaddyLive (PLAYER 2), la vía que quedó
 * operativa cuando el CDN de dembed empezó a exigir Cloudflare Turnstile.
 *
 * Cadena (vista en vivo el 2026-11-21):
 *
 *   /cast/stream-{id}.php
 *     -> iframe https://waterera.net/e/{hash}   (host rota)
 *     -> window._econfig = 'BASE64'
 *     -> decrypt (4 trozos, se descarta el 4º char, orden 2-0-3-1)
 *     -> JSON { stream_url, stream_url_nop2p, ... }
 *     -> HLS firmado (p. ej. https://*.7odxv0l067ka.net:8443/hls/{id}.m3u8?s=..&e=..)
 *
 * El playlist y los segmentos exigen `Referer` del embed y User-Agent de
 * navegador; el proxy local los propaga a cada segmento. El token de la URL
 * dura ~6 h, así que la fuente se cachea por canal y sólo se repite la cadena
 * de páginas cuando vence (o cuando el CDN responde 403).
 */
final class CastResolver {

    /** Playlist resuelto de un canal, con el Referer que exige su CDN. */
    static final class Source {
        final String playlistUrl;
        final String referer;
        final long expiresAtMs;

        Source(String playlistUrl, String referer, long expiresAtMs) {
            this.playlistUrl = playlistUrl;
            this.referer = referer;
            this.expiresAtMs = expiresAtMs;
        }

        /** Origen del embed (para `Origin` en playlist y segmentos). */
        String origin() {
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
    }

    private static final Pattern IFRAME_RE = Pattern.compile(
        "<iframe[^>]+src\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern ECONFIG_RE = Pattern.compile(
        "window\\._econfig\\s*=\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern URL_NOP2P_RE = Pattern.compile(
        "\"stream_url_nop2p\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern URL_RE = Pattern.compile(
        "\"stream_url\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern P2P_RE = Pattern.compile(
        "\"p2p\"\\s*:\\s*(true|false)");
    private static final Pattern URL_NOP2P_FLAG_RE = Pattern.compile(
        "\"url_nop2p\"\\s*:\\s*(true|false)");
    private static final Pattern EXPIRES_RE = Pattern.compile("[?&]e=(\\d{9,})");

    /** Fuente vigente por canal (el playlist se refresca, la URL no). */
    private static final ConcurrentHashMap<String, Source> SOURCES =
        new ConcurrentHashMap<String, Source>();
    /** Dominios que acaban de fallar: no reintentarlos en cada canal. */
    private static final ConcurrentHashMap<String, Long> BASE_BACKOFF =
        new ConcurrentHashMap<String, Long>();

    private CastResolver() {
    }

    /** Fuente cacheada y aún vigente, o null. */
    static Source cached(String channelId) {
        Source s = SOURCES.get(channelId);
        if (s == null) {
            return null;
        }
        if (System.currentTimeMillis() >= s.expiresAtMs - Config.EXPIRY_MARGIN_MS) {
            SOURCES.remove(channelId, s);
            return null;
        }
        return s;
    }

    /** Descarta la fuente del canal (403 del CDN o segmentos caídos). */
    static void invalidate(String channelId) {
        SOURCES.remove(channelId);
    }

    /**
     * Recorre los dominios de DaddyLive hasta encontrar el embed cast del
     * canal. Devuelve null si ningún dominio lo tiene o si se agotó el
     * presupuesto; no lanza.
     */
    static Source resolve(String channelId, long deadline) {
        String lastError = null;
        for (String base : Config.DLHD_BASES) {
            long now = System.currentTimeMillis();
            if (now >= deadline - 1500L) {
                lastError = "presupuesto agotado";
                break;
            }
            Long until = BASE_BACKOFF.get(base);
            if (until != null && now < until) {
                continue;
            }
            try {
                Source s = fromBase(base, channelId, deadline);
                if (s != null) {
                    BASE_BACKOFF.remove(base);
                    SOURCES.put(channelId, s);
                    System.err.println("[Cast] canal " + channelId + " -> " + host(s.playlistUrl));
                    return s;
                }
            } catch (Exception e) {
                lastError = e.getMessage();
                BASE_BACKOFF.put(base, System.currentTimeMillis() + Config.BASE_BACKOFF_MS);
                System.err.println("[Cast] " + base + " fallo: " + e.getMessage());
            }
        }
        if (lastError != null) {
            System.err.println("[Cast] canal " + channelId + " sin fuente cast: " + lastError);
        }
        return null;
    }

    private static Source fromBase(String base, String channelId, long deadline) throws Exception {
        String pageUrl = base + Config.CAST_PATH + channelId + ".php";
        Http.Response page = Http.getResponse(pageUrl, htmlHeaders(base + "/"), true,
            timeoutFor(deadline));
        if (page.code == 404 || page.code == 410) {
            System.err.println("[Cast] sin player cast (HTTP " + page.code + ") en " + base);
            return null;
        }
        if (page.code != 200 || page.body == null || page.body.length == 0) {
            throw new IllegalStateException("cast HTTP " + page.code + " en " + base);
        }
        String html = new String(page.body, StandardCharsets.UTF_8);
        Matcher im = IFRAME_RE.matcher(html);
        if (!im.find()) {
            // El canal no tiene este player; no es un fallo del dominio.
            System.err.println("[Cast] sin iframe en " + pageUrl);
            return null;
        }
        String embed = absolute(pageUrl, im.group(1));
        if (embed == null) {
            return null;
        }
        if (System.currentTimeMillis() >= deadline - 1200L) {
            return null;
        }

        Http.Response er = Http.getResponse(embed, htmlHeaders(pageUrl), true,
            timeoutFor(deadline));
        if (er.code == 404 || er.code == 410) {
            System.err.println("[Cast] embed caído (HTTP " + er.code + ") en " + host(embed));
            return null;
        }
        if (er.code != 200 || er.body == null || er.body.length == 0) {
            throw new IllegalStateException("embed HTTP " + er.code + " en " + host(embed));
        }
        String ehtml = new String(er.body, StandardCharsets.UTF_8);
        Matcher em = ECONFIG_RE.matcher(ehtml);
        if (!em.find()) {
            System.err.println("[Cast] sin _econfig en " + host(embed));
            return null;
        }
        String json = decryptEconfig(em.group(1));
        if (json == null) {
            System.err.println("[Cast] _econfig ilegible en " + host(embed));
            return null;
        }
        String url = playableUrl(json);
        if (url == null || url.length() == 0) {
            System.err.println("[Cast] _econfig sin stream_url en " + host(embed));
            return null;
        }
        url = url.replace("\\/", "/");
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return null;
        }
        return new Source(url, embed, expiresAt(url));
    }

    /** Headers que exige el CDN del embed (playlist y segmentos). */
    static Map<String, String> headers(Source s) {
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("User-Agent", Config.USER_AGENT);
        h.put("Accept", "*/*");
        h.put("Referer", s.referer);
        String origin = s.origin();
        if (origin != null) {
            h.put("Origin", origin);
        }
        return h;
    }

    private static Map<String, String> htmlHeaders(String referer) {
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("User-Agent", Config.USER_AGENT);
        h.put("Accept", "text/html,application/xhtml+xml");
        h.put("Referer", referer);
        return h;
    }

    /** Read timeout acotado al presupuesto restante de la fase cast. */
    private static int timeoutFor(long deadline) {
        long left = deadline - System.currentTimeMillis();
        if (left < 2500L) {
            return 2500;
        }
        return (int) Math.min((long) Config.PAGE_TIMEOUT_MS, left);
    }

    // ------------------------------------------------------------- econfig

    /**
     * Descifra `window._econfig`. El texto es Base64 de un Base64 partido en
     * 4 trozos iguales: a cada trozo se le quita el 4º carácter y los trozos
     * se reordenan 2-0-3-1 antes de decodificar.
     */
    static String decryptEconfig(String b64) {
        if (b64 == null || b64.length() < 32) {
            return null;
        }
        byte[] raw = Base64Codec.decode(b64);
        if (raw.length < 32) {
            return null;
        }
        int chunk = (raw.length + 3) / 4;
        int[] order = {2, 0, 3, 1};
        byte[][] parts = new byte[4][];
        for (int i = 0; i < 4; i++) {
            int start = i * chunk;
            int end = Math.min(start + chunk, raw.length);
            int len = end - start;
            if (len <= 4) {
                return null;
            }
            byte[] piece = new byte[len - 1];
            System.arraycopy(raw, start, piece, 0, 3);
            System.arraycopy(raw, start + 4, piece, 3, len - 4);
            byte[] dec = Base64Codec.decode(new String(piece, StandardCharsets.ISO_8859_1));
            if (dec.length == 0) {
                return null;
            }
            parts[order[i]] = dec;
        }
        int total = 0;
        for (byte[] p : parts) {
            total += p.length;
        }
        byte[] combined = new byte[total];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, combined, off, p.length);
            off += p.length;
        }
        byte[] json = Base64Codec.decode(new String(combined, StandardCharsets.ISO_8859_1));
        return new String(json, StandardCharsets.UTF_8);
    }

    /** Misma preferencia que el player oficial. */
    private static String playableUrl(String json) {
        Matcher nop = URL_NOP2P_RE.matcher(json);
        String nopUrl = nop.find() ? nop.group(1) : null;
        Matcher std = URL_RE.matcher(json);
        String stdUrl = std.find() ? std.group(1) : null;
        boolean p2pOff = "false".equals(flag(P2P_RE, json));
        boolean nopFlag = "true".equals(flag(URL_NOP2P_FLAG_RE, json));
        if (p2pOff || nopFlag) {
            return nopUrl != null ? nopUrl : stdUrl;
        }
        return stdUrl != null ? stdUrl : nopUrl;
    }

    private static String flag(Pattern p, String json) {
        Matcher m = p.matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static long expiresAt(String url) {
        long now = System.currentTimeMillis();
        Matcher m = EXPIRES_RE.matcher(url);
        if (m.find()) {
            try {
                long at = Long.parseLong(m.group(1)) * 1000L - Config.EXPIRY_MARGIN_MS;
                if (at > now + Config.MIN_TTL_MS) {
                    return Math.min(at, now + Config.TTL_CAP_MS);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return now + 15L * 60L * 1000L;
    }

    // ------------------------------------------------------------- util

    private static String absolute(String base, String ref) {
        try {
            return URI.create(base).resolve(ref).toString();
        } catch (Exception e) {
            if (ref.startsWith("//")) {
                return "https:" + ref;
            }
            if (ref.startsWith("/")) {
                String o = host(base);
                return o != null ? "https://" + o + ref : null;
            }
            return null;
        }
    }

    private static String host(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return url;
        }
    }
}
