package com.infomak.moai.daddylive;

/** Configuración central del plugin (fuentes, UA, timeouts y límites). */
final class Config {

    /**
     * CDN de DaddyLive: sirve premium{id}/index.m3u8 con token por espectador.
     * El dominio rota (visto 2026-11-02: edge.wedd48c.top; antes
     * edge.cowedd4855ws.sbs). Solo es el valor de arranque: EdgeTokens lo
     * redescubre de PLAYER_PAGE y lo refresca cada EDGE_TTL_MS.
     */
    static final String EDGE = "https://edge.wedd48c.top";

    /**
     * Dominios de la web de DaddyLive (rotan). El player `cast` se busca en
     * todos en orden; el primero que responde queda como fuente del canal.
     * Vistos en vivo el 2026-11-21: thedaddy.pk y thedaddy.li.
     */
    static final String[] DLHD_BASES = {
        "https://thedaddy.pk",
        "https://thedaddy.li",
    };

    /**
     * Player "cast" (PLAYER 2): la vía directa que sigue funcionando sin
     * Turnstile. Devuelve un iframe con `_econfig` y un HLS firmado.
     */
    static final String CAST_PATH = "/cast/stream-";

    /**
     * Página del player oficial. Contiene `const EDGE = "https://..."`, que es
     * la fuente autoritativa del CDN mientras el dominio siga rotando.
     */
    static final String PLAYER_PAGE = "https://dembed.st/premiumtv/daddy.php?id=";

    /** Vigencia del EDGE descubierto antes de volver a leer la página. */
    static final long EDGE_TTL_MS = 15L * 60L * 1000L;

    /** Si el descubrimiento falla, no reintentarlo en cada canal. */
    static final long EDGE_RETRY_MS = 60L * 1000L;

    /**
     * Si el CDN niega el token (403; desde 2026-11 el player oficial lo pide
     * con Cloudflare Turnstile), durante este tiempo se va directo a tvnow247
     * en vez de gastar el presupuesto del resolve en reintentos.
     */
    static final long TOKEN_WALL_MS = 10L * 60L * 1000L;

    /**
     * Origen del player oficial (dembed.st/premiumtv/daddy.php?id=N). El CDN
     * solo entrega token y playlist con este Referer/Origin.
     */
    static final String PLAYER_ORIGIN = "https://dembed.st";

    /** Web que embebe el player: Referer de la página del player. */
    static final String SITE_ORIGIN = "https://thedaddy.pk";

    /**
     * Catálogo 24/7. La web dejó de exponer /api/channels: ahora el listado
     * viaja en la grilla de 24-7-channels.php (enlaces a watch.php?id=N).
     */
    static final String CATALOG_PAGE = "https://thedaddy.pk/24-7-channels.php";

    /**
     * Respaldo: API de tvnow247 que resuelve el mismo id numérico y devuelve
     * un playlist (proxy propio) con los mismos segmentos enmascarados.
     */
    static final String TVNOW_RESOLVE_API = "https://chat.cfbu247.sbs/api/resolve-dlstream/";
    static final String TVNOW_ORIGIN = "https://tvnow247.top";

    /** Dominios legacy de DaddyLive para bajar el catálogo (/api/channels). */
    static final String[] EMBED_DOMAINS = {
        "daddylive.li",
        "daddylive.app",
        "daddylive.mov",
    };

    static final String USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    static final int CONNECT_TIMEOUT_MS = 4000;
    static final int READ_TIMEOUT_MS = 6000;
    /** Páginas HTML del player cast (la del canal pesa ~660 KB). */
    static final int PAGE_TIMEOUT_MS = 9000;
    /** Los segmentos enmascarados pesan 1.2-2.7 MB: necesitan mas margen. */
    static final int SEGMENT_TIMEOUT_MS = 15000;
    static final int PLAYLIST_TIMEOUT_MS = 8000;
    static final int TOKEN_TIMEOUT_MS = 4000;
    /** Presupuesto de la fase tvnow247 (primera fuente desde v0.6.1). */
    static final long TVNOW_BUDGET_MS = 7L * 1000L;
    /** Presupuesto de la fase cast antes de pasar al CDN de dembed. */
    static final long CAST_BUDGET_MS = 10L * 1000L;
    /** Un dominio de DaddyLive caído no se reintenta en cada canal. */
    static final long BASE_BACKOFF_MS = 30L * 1000L;
    /** Techo de descarga por peticion. Los segmentos van a ~2.7 MB. */
    static final int MAX_BODY_BYTES = 24 * 1024 * 1024;

    /**
     * Un canal que nadie mira tarda en publicar los primeros segmentos: el
     * player oficial reintenta 4 veces cada 1,5 s antes de darlo por caído.
     */
    static final int COLD_START_TRIES = 4;
    static final long COLD_START_DELAY_MS = 1500L;

    /** Reintentos de descarga de un segmento antes de responder 502. */
    static final int SEGMENT_RETRIES = 2;
    /** Segmentos que se bajan y desempaquetan por adelantado al servir el playlist. */
    static final int PREFETCH_SEGMENTS = 3;
    /** Segmentos desempaquetados en memoria (LRU). */
    static final int SEGMENT_CACHE_SIZE = 8;
    /** Descargas de segmentos en paralelo. */
    static final int DOWNLOAD_THREADS = 3;
    /** Un playlist viejo se sirve si el refresco falla, hasta esta edad. */
    static final long STALE_PLAYLIST_MS = 20L * 1000L;

    /** Presupuesto máximo de un resolve() en el plugin (límite de 20 s de Android). */
    static final long RESOLVE_BUDGET_MS = 16L * 1000L;

    /** Margen de seguridad restado a `e` (expiry) antes de invalidar el cache. */
    static final long EXPIRY_MARGIN_MS = 30L * 1000L;
    /** Piso base de TTL cuando todo OK pero sin `e` disponible. */
    static final long MIN_TTL_MS = 60L * 1000L;
    /** Techo del TTL del cache de instancia. */
    static final long TTL_CAP_MS = 6L * 3600L * 1000L;
    /** Negative-cache de errores para no martillar dominios caídos. */
    static final long NEGATIVE_CACHE_MS = 30L * 1000L;
    /** Formato de salida. */
    static final String FORMAT_HLS = "hls";

    static final String PLUGIN_ID = "moai_daddylive";
    static final String PLUGIN_TAG = "Daddy";
    static final String PLUGIN_NOMBRE = "Moai Daddylive";
    static final String PLUGIN_VERSION = "0.6.1";
    static final String PLUGIN_CLASE = "com.infomak.moai.daddylive.DaddylivePlugin";

    private Config() {
    }
}
