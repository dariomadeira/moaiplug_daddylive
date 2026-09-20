package com.infomak.moai.daddylive;

import com.infomak.moai.contract.DrmInfo;
import com.infomak.moai.contract.IPlugin;
import com.infomak.moai.contract.PluginChannel;
import com.infomak.moai.contract.PluginManifest;
import com.infomak.moai.contract.ResolveRequest;
import com.infomak.moai.contract.ResolveResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Plugin .dex v1 — Catálogo 24/7 de DaddyLive (~1274 canales numéricos).
 *
 * El host moai3 aporta la lista de canales desde el `manifest.json` estático
 * (parseManifest; ver PLAN.md 4b Hallazgo 1), así que aquí `manifest()` es
 * solo un stub (nunca se invoca en runtime) y el catálogo real viaja en el
 * propio manifest.json generado por build.sh + scripts/build_catalog.py.
 *
 * La instancia es única por plugin en el host (PluginLoader mantiene el mapa
 * `instances`), así que cachés de resolución viven en campos de instancia:
 *  - Por canal -> URL firmada + headers + expira (basado en `e` de la URL).
 *  - Negative-cache de errores de 30 s para no martillar dominios caídos.
 *
 * Reproducción: main moai3 aplica los headers del ResolveResult a TODA la
 * cadena HLS (MainActivity DefaultHttpDataSource.setDefaultRequestProperties)
 * -> el `Referer: https://tiestep.top/` desbloquea los segmentos del CDN.
 */
public final class DaddylivePlugin implements IPlugin {

    private static final class Cached {
        final DaddyliveResolver.Result result;
        final long expiresAtMs;

        Cached(DaddyliveResolver.Result result, long expiresAtMs) {
            this.result = result;
            this.expiresAtMs = expiresAtMs;
        }
    }

    private final DaddyliveResolver resolver;
    private final ConcurrentHashMap<String, Cached> cache =
        new ConcurrentHashMap<String, Cached>();
    private final ConcurrentHashMap<String, Long> errorCache =
        new ConcurrentHashMap<String, Long>();

    public DaddylivePlugin() {
        this.resolver = new DaddyliveResolver();
    }

    @Override
    public PluginManifest manifest() {
        List<PluginChannel> stub = new ArrayList<PluginChannel>();
        stub.add(new PluginChannel("521", "#Vamos Spain", "",
            "Deportes", "España"));
        return new PluginManifest(
            Config.PLUGIN_ID,
            Config.PLUGIN_NOMBRE,
            Config.PLUGIN_VERSION,
            1,
            1,
            stub,
            Config.PLUGIN_CLASE,
            Config.PLUGIN_TAG);
    }

    @Override
    public ResolveResult resolve(ResolveRequest request) {
        String channelId = request.getChannelId();
        long now = System.currentTimeMillis();

        Long failAt = errorCache.get(channelId);
        if (failAt != null && now < failAt) {
            throw new IllegalStateException("Daddylive: fallo reciente en "
                + channelId + ", reintenta en breve");
        }

        Cached cached = cache.get(channelId);
        if (cached != null && now < cached.expiresAtMs) {
            return toResult(cached.result);
        }

        DaddyliveResolver.Result result = resolver.resolve(channelId);
        long ttl = result.ttlMs;
        cache.put(channelId, new Cached(result, now + ttl));
        errorCache.remove(channelId);
        return toResult(result);
    }

    private static ResolveResult toResult(DaddyliveResolver.Result r) {
        Map<String, String> headers = Collections.unmodifiableMap(r.headers);
        return new ResolveResult(r.url, headers, (DrmInfo) null,
            Config.FORMAT_HLS, r.ttlMs);
    }

    // --------------------------------------------------------- autotest (JVM)
    public static void main(String[] args) throws Exception {
        DaddylivePlugin p = new DaddylivePlugin();
        System.out.println("manifest: " + p.manifest());

        String[] samples = {"521", "800", "44"};
        for (String id : samples) {
            long t0 = System.currentTimeMillis();
            try {
                ResolveResult r = p.resolve(new ResolveRequest(id, 0));
                long ms = System.currentTimeMillis() - t0;
                System.out.println("resolve " + id + " en " + ms + " ms:");
                System.out.println("  url: " + shorten(r.getUrl(), 130));
                System.out.println("  format: " + r.getFormat()
                    + "  ttlMs: " + r.getTtlMs()
                    + "  drm: " + r.getDrm());
                System.out.println("  headers: " + r.getHeaders());
                verifySegment(id, r);
            } catch (Exception e) {
                System.out.println("resolve " + id + " FALLO: " + e.getMessage());
                e.printStackTrace(System.out);
            }
            System.out.println();
        }
        System.out.println("AUTOTEST OK");
    }

    private static String shorten(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }

    /** Verifica jugabilidad: primer segmento del playlist responde 200 con referer. */
    private static void verifySegment(String id, ResolveResult r) {
        try {
            String pl = Http.get(r.getUrl(),
                unmodifiable(r.getHeaders()));
            if (!pl.startsWith("#EXTM3U")) {
                System.out.println("  OJO: playlist sin #EXTM3U");
                return;
            }
            String newest = null;
            for (String line : pl.split("\n")) {
                String t = line.trim();
                if (t.length() > 0 && !t.startsWith("#")) {
                    newest = t;
                }
            }
            if (newest == null) {
                System.out.println("  segmento: no en playlist");
                return;
            }
            String segUrl = r.getUrl().substring(0, r.getUrl().lastIndexOf('/') + 1)
                + newest;
            Http.Response seg = Http.getResponse(segUrl, unmodifiable(r.getHeaders()),
                false);
            System.out.println("  segmento " + seg.code + " -> " + (seg.code == 200
                ? "REPRODUCIBLE" : "NO (revisar referer)"));
        } catch (Exception e) {
            System.out.println("  segmento: error " + e.getMessage());
        }
    }

    private static Map<String, String> unmodifiable(Map<String, String> m) {
        return Collections.unmodifiableMap(m);
    }
}