package com.infomak.moai.daddylive;

/** Configuración central del plugin (fuentes, UA, timeouts y límites). */
final class Config {

    /** CDN de DaddyLive: sirve premium{id}/index.m3u8 con token por espectador. */
    static final String EDGE = "https://edge.cowedd4855ws.sbs";

    /**
     * Origen del player oficial (dembed.st/premiumtv/daddy.php?id=N). El CDN
     * solo entrega token y playlist con este Referer/Origin.
     */
    static final String PLAYER_ORIGIN = "https://dembed.st";

    /**
     * Respaldo: API de tvnow247 que resuelve el mismo id numérico y devuelve
     * un playlist (proxy propio) con los mismos segmentos enmascarados.
     */
    static final String TVNOW_RESOLVE_API = "https://chat.cfbu247.sbs/api/resolve-dlstream/";
    static final String TVNOW_ORIGIN = "https://tvnow247.top";

    /** Dominios de DaddyLive para bajar el catálogo (/api/channels) en BatchVerify. */
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
    /** Los segmentos enmascarados pesan 1.2-2.7 MB: necesitan mas margen. */
    static final int SEGMENT_TIMEOUT_MS = 15000;
    static final int PLAYLIST_TIMEOUT_MS = 8000;
    static final int TOKEN_TIMEOUT_MS = 4000;
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
    static final String PLUGIN_VERSION = "0.5.0";
    static final String PLUGIN_CLASE = "com.infomak.moai.daddylive.DaddylivePlugin";

    private Config() {
    }
}
