package com.infomak.moai.daddylive;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolver puro-HTTP del catálogo 24/7 de Daddylive (Fase 1, flow validado
 * en vivo el 2026-09-20 — ver PLAN.md sección 4d).
 *
 * Cadena: /player/embed.php?id=N -> array PLAYERS -> slot con iframe ->
 * player v9 (tiestep) -> _econfig -> Scheme B (decrypt moaiServer) -> m3u8.
 *
 * Los segmentos del CDN exigen `Referer: https://{origin_player}/` (p. ej.
 * https://tiestep.top/); el motor moai3 aplica los headers del ResolveResult
 * a toda la cadena HLS, así que el resultado carga y reproduce.
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

    private static final Pattern PLAYERS_RE =
        Pattern.compile("PLAYERS\\s*=\\s*(\\[[\\s\\S]*?\\])");
    private static final Pattern SLOT_RE =
        Pattern.compile("\\{\\s*\"src\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*,\\s*\"hls\"\\s*:\\s*(true|false)\\s*\\}");
    private static final Pattern IFRAME_SRC_RE =
        Pattern.compile("<iframe[^>]+src\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern ECONFIG_RE =
        Pattern.compile("window(?:\\._econfig|\\[['\"][^'\"]+['\"]\\])\\s*=\\s*['\"]([^'\"]{50,})['\"]");
    private static final Pattern ATOB_SOURCE_RE =
        Pattern.compile("source\\s*:\\s*window\\.atob\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)");
    private static final Pattern DIRECT_M3U8_RE =
        Pattern.compile("[\"']((?:https?:)?//[^\"']+\\.m3u8[^\"']*)[\"']");
    private static final Pattern URL_NOP2P_RE =
        Pattern.compile("\"stream_url_nop2p\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern URL_RE =
        Pattern.compile("\"stream_url\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern EXPIRY_RE =
        Pattern.compile("[?&]e=(\\d{10})");
    private static final Pattern HOST_IN_URL_RE =
        Pattern.compile("^(?:https?://)?([^/:#?]+)");
    // Scheme D: JSON embebido con source/url
    private static final Pattern JSON_SOURCE_RE =
        Pattern.compile("\"source\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern JSON_URL_RE =
        Pattern.compile("\"url\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    // Scheme E: m3u8 en atributo data-src o src
    private static final Pattern DATA_SRC_RE =
        Pattern.compile("data-src\\s*=\\s*[\"']([^\"']+\\.m3u8[^\"']*)[\"']");
    private static final Pattern SRC_M3U8_RE =
        Pattern.compile("src\\s*=\\s*[\"']([^\"']+\\.m3u8[^\"']*)[\"']");

    private static final Map<String, String> NO_HEADERS =
        new LinkedHashMap<String, String>();

    DaddyliveResolver() {
    }

    Result resolve(String channelId) {
        if (channelId == null || channelId.length() == 0 || !isNumeric(channelId)) {
            throw new IllegalStateException("Daddylive: id de canal inválido: "
                + channelId + " (solo id numérico en este layout)");
        }
        long deadline = System.currentTimeMillis() + Config.RESOLVE_BUDGET_MS;
        String lastError = null;

        for (String domain : Config.EMBED_DOMAINS) {
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("Daddylive: presupuesto "
                    + Config.RESOLVE_BUDGET_MS + " ms superado resolviendo "
                    + channelId);
            }
            try {
                return resolveWithDomain(channelId, domain, deadline);
            } catch (Exception e) {
                lastError = e.getMessage();
            }
        }
        throw new IllegalStateException("Daddylive: todos los dominios fallaron para "
            + channelId + (lastError != null ? " (" + lastError + ")" : ""));
    }

    private Result resolveWithDomain(String channelId, String domain, long deadline) {
        checkBudget(deadline);
        String embedUrl = "https://" + domain + "/player/embed.php?id=" + channelId;
        Map<String, String> embedHeaders = new LinkedHashMap<String, String>();
        embedHeaders.put("Referer", "https://" + domain + "/");

        System.err.println("[DEBUG] Fetching embed: " + embedUrl);
        String embedHtml = Http.get(embedUrl, embedHeaders);
        Matcher pm = PLAYERS_RE.matcher(embedHtml);
        if (!pm.find()) {
            throw new IllegalStateException("Sin array PLAYERS en " + embedUrl);
        }
        List<Slot> slots = parseSlots(pm.group(1), embedUrl);
        System.err.println("[DEBUG] Total slots parsed: " + slots.size());
        if (slots.isEmpty()) {
            throw new IllegalStateException("PLAYERS vacío en " + embedUrl);
        }

        List<Slot> preferred = new ArrayList<Slot>();
        List<Slot> backup = new ArrayList<Slot>();
        for (Slot slot : slots) {
            if (isDeadSlot(slot.url)) {
                backup.add(slot);
            } else {
                preferred.add(slot);
            }
        }
        preferred.addAll(backup);

        for (Slot slot : preferred) {
            checkBudget(deadline);
            try {
                System.err.println("[DEBUG] Trying slot: " + slot.url);
                Result r = trySlot(slot, embedUrl, deadline);
                if (r != null) {
                    System.err.println("[DEBUG] Slot SUCCESS: " + r.url);
                    return r;
                }
            } catch (Exception e) {
                System.err.println("[DEBUG] Slot FAILED (" + slot.url + "): " + e.getMessage());
                e.printStackTrace(System.err);
            }
        }
        throw new IllegalStateException("Ningún slot rindió m3u8 en " + domain
            + " (canal " + channelId + ")");
    }

    private static final class Slot {
        final String url;
        final boolean hlsDirect;

        Slot(String url, boolean hlsDirect) {
            this.url = url;
            this.hlsDirect = hlsDirect;
        }
    }

    private static List<Slot> parseSlots(String arrayJson, String embedUrl) {
        List<Slot> out = new ArrayList<Slot>();
        Matcher m = SLOT_RE.matcher(arrayJson);
        while (m.find()) {
            String src = m.group(1).replace("\\/", "/").replace("\\\\", "\\");
            boolean direct = "true".equals(m.group(2));
            if (src.startsWith("//")) {
                src = "https:" + src;
            } else if (src.startsWith("http://")) {
                src = "https://" + src.substring(7);
            } else if (src.startsWith("/")) {
                src = originOf(embedUrl) + src;
            }
            out.add(new Slot(src, direct));
        }
        return out;
    }

    private static boolean isDeadSlot(String url) {
        String h = url.toLowerCase();
        return h.contains("nontongo") || h.contains("worldsportz4u");
    }

    private Result trySlot(Slot slot, String embedUrl, long deadline) {
        if (slot.hlsDirect && slot.url.contains(".m3u8")) {
            checkBudget(deadline);
            return validatedResult(slot.url, originOf(embedUrl) + "/", deadline);
        }

        checkBudget(deadline);
        String page;
        try {
            page = Http.get(slot.url, headersOf("Referer", originOf(slot.url) + "/"));
        } catch (Exception e) {
            try {
                page = Http.get(slot.url, headersOf("Referer", embedUrl));
            } catch (Exception e2) {
                return null;
            }
        }

        // Seguir un iframe de primer nivel (p. ej. freetvspor -> tiestep o pandastreams)
        Matcher im = IFRAME_SRC_RE.matcher(page);
        String targetUrl = slot.url;
        String targetHtml = page;
        String refererRoot = originOf(slot.url) + "/";
        if (im.find()) {
            String sub = im.group(1);
            if (sub.startsWith("//")) {
                sub = "https:" + sub;
            } else if (sub.startsWith("http://")) {
                sub = "https://" + sub.substring(7);
            } else if (sub.startsWith("/")) {
                sub = originOf(slot.url) + sub;
            }
            targetUrl = sub;
            checkBudget(deadline);
            try {
                targetHtml = Http.get(sub, headersOf("Referer", slot.url));
                refererRoot = originOf(sub) + "/";
            } catch (Exception e) {
                return null;
            }
        }

        String url = extractSource(targetHtml);
        if (url == null) {
            return null;
        }
        // En Scheme B o iframe el origin del targetUrl es el que desbloquea los segmentos.
        refererRoot = originOf(targetUrl) + "/";
        checkBudget(deadline);
        return validatedResult(url, refererRoot, deadline);
    }

    private static Map<String, String> headersOf(String k, String v) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put(k, v);
        return m;
    }

    private static boolean isV9Like(String url) {
        return url != null && url.length() > 0;
    }

    /** Scheme B > A > C > D > E sobre el HTML (orden de moaiServer pero B primero: es el vivo). */
    private static String extractSource(String html) {
        // Scheme B: _econfig (el más común y confiable)
        Matcher ec = ECONFIG_RE.matcher(html);
        if (ec.find()) {
            try {
                String json = decryptEconfig(ec.group(1));
                Matcher u = URL_NOP2P_RE.matcher(json);
                String url = u.find() ? unescape(u.group(1)) : null;
                if (url == null || url.length() == 0) {
                    Matcher u2 = URL_RE.matcher(json);
                    url = u2.find() ? unescape(u2.group(1)) : null;
                }
                if (url != null && url.length() > 0) {
                    return url;
                }
            } catch (Exception e) {
                // fallar grave pero seguir a Scheme A/C/D/E
            }
        }
        // Scheme A: source: window.atob('...')
        Matcher am = ATOB_SOURCE_RE.matcher(html);
        if (am.find()) {
            try {
                byte[] dec = Base64Codec.decode(am.group(1));
                String s = new String(dec, java.nio.charset.StandardCharsets.UTF_8);
                if (s.contains(".m3u8")) {
                    return s.trim();
                }
            } catch (Exception e) {
                // seguir
            }
        }
        // Scheme C: m3u8 directo en el HTML
        Matcher dm = DIRECT_M3U8_RE.matcher(html);
        if (dm.find()) {
            String u = dm.group(1);
            if (u.startsWith("//")) {
                u = "https:" + u;
            }
            return u;
        }
        // Scheme D: JSON embebido con source/url
        Matcher js = JSON_SOURCE_RE.matcher(html);
        if (js.find()) {
            String u = unescape(js.group(1));
            if (u.contains(".m3u8")) {
                if (u.startsWith("//")) {
                    u = "https:" + u;
                }
                return u;
            }
        }
        Matcher ju = JSON_URL_RE.matcher(html);
        if (ju.find()) {
            String u = unescape(ju.group(1));
            if (u.contains(".m3u8")) {
                if (u.startsWith("//")) {
                    u = "https:" + u;
                }
                return u;
            }
        }
        // Scheme E: m3u8 en data-src o src
        Matcher ds = DATA_SRC_RE.matcher(html);
        if (ds.find()) {
            String u = ds.group(1);
            if (u.startsWith("//")) {
                u = "https:" + u;
            }
            return u;
        }
        Matcher sm = SRC_M3U8_RE.matcher(html);
        if (sm.find()) {
            String u = sm.group(1);
            if (u.startsWith("//")) {
                u = "https:" + u;
            }
            return u;
        }
        return null;
    }

    private static String unescape(String s) {
        return s.replace("\\/", "/").replace("\\u0026", "&")
            .replace("\\\"", "\"").replace("\\\\", "\\");
    }

    /**
     * Port de moaiServer `DaddyliveResolver.decryptConfig` (scrapers.ts:574).
     * base64 -> 4 partes -> reordenar [2,0,3,1] -> quitar el 4º byte de cada
     * parte -> re-base64 -> concatenar -> base64 -> JSON.
     */
    static String decryptEconfig(String encoded) {
        byte[] dec1 = Base64Codec.decode(encoded);
        int total = dec1.length;
        int partLength = (total + 3) / 4;
        int[] order = {2, 0, 3, 1};
        String[] parts = new String[4];
        for (int i = 0; i < 4; i++) {
            int from = i * partLength;
            int to = Math.min(from + partLength, total);
            if (from >= total) {
                parts[i] = "";
                continue;
            }
            parts[i] = new String(dec1, from, to - from,
                java.nio.charset.StandardCharsets.ISO_8859_1);
        }
        byte[][] rearranged = new byte[4][];
        for (int i = 0; i < order.length; i++) {
            String s = parts[i];
            if (s.length() >= 4) {
                s = s.substring(0, 3) + s.substring(4);
            }
            try {
                rearranged[order[i]] = Base64Codec.decode(s);
            } catch (Exception e) {
                rearranged[order[i]] = new byte[0];
            }
        }
        byte[] combined = new byte[0];
        for (int i = 0; i < 4; i++) {
            byte[] a = rearranged[i];
            byte[] n = new byte[combined.length + a.length];
            System.arraycopy(combined, 0, n, 0, combined.length);
            System.arraycopy(a, 0, n, combined.length, a.length);
            combined = n;
        }
        return new String(Base64Codec.decode(new String(combined,
            java.nio.charset.StandardCharsets.ISO_8859_1)),
            java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Valida que el m3u8 responda 200 con cabecera #EXTM3U y calcula el TTL. */
    private Result validatedResult(String url, String refererRoot, long deadline) {
        checkBudget(deadline);
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("User-Agent", Config.USER_AGENT);
        headers.put("Referer", refererRoot);

        Http.Response r;
        try {
            r = Http.getResponse(url, headers, true);
        } catch (Exception e) {
            r = null;
        }

        if (r == null || r.code < 200 || r.code >= 300 || !startsWith(r.body, "#EXTM3U")) {
            // Reintentar sin Referer por si el CDN lo rechaza
            Map<String, String> h2 = new LinkedHashMap<String, String>();
            h2.put("User-Agent", Config.USER_AGENT);
            try {
                Http.Response r2 = Http.getResponse(url, h2, true);
                if (r2.code >= 200 && r2.code < 300 && startsWith(r2.body, "#EXTM3U")) {
                    r = r2;
                    headers = h2;
                }
            } catch (Exception ignored) {
            }
        }

        if (r == null || r.code < 200 || r.code >= 300 || !startsWith(r.body, "#EXTM3U")) {
            int code = r != null ? r.code : 0;
            throw new IllegalStateException("m3u8 no reproducible (HTTP " + code
                + ") en " + url);
        }

        long ttl = ttlFor(url);
        return new Result(url, headers, ttl);
    }

    private static long ttlFor(String url) {
        Matcher m = EXPIRY_RE.matcher(url);
        if (m.find()) {
            try {
                long eMs = Long.parseLong(m.group(1)) * 1000L;
                long ttl = eMs - System.currentTimeMillis() - Config.EXPIRY_MARGIN_MS;
                if (ttl > Config.MIN_TTL_MS) {
                    return Math.min(ttl, Config.TTL_CAP_MS);
                }
            } catch (NumberFormatException e) {
                // tratar como sin expiry
            }
        }
        return Config.MIN_TTL_MS;
    }

    private static boolean startsWith(byte[] body, String prefix) {
        if (body == null || body.length < prefix.length()) {
            return false;
        }
        for (int i = 0; i < prefix.length(); i++) {
            if ((char) (body[i] & 0xFF) != prefix.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static String originOf(String url) {
        Matcher m = HOST_IN_URL_RE.matcher(url);
        if (!m.find()) {
            return url;
        }
        return "https://" + m.group(1);
    }

    private static boolean isNumeric(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return s.length() > 0;
    }

    private static void checkBudget(long deadline) {
        if (System.currentTimeMillis() > deadline) {
            throw new IllegalStateException("presupuesto de tiempo superado");
        }
    }
}