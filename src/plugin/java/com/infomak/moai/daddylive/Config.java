package com.infomak.moai.daddylive;

import java.util.LinkedHashMap;

/** Configuración central del plugin (dominios, UA, timeouts y límites). */
final class Config {

    /** Dominios del catálogo/embed validados en vivo (Fase 1). Orden = rotación. */
    static final String[] EMBED_DOMAINS = {
        "daddylive.li",
        "daddylive.app",
        "daddylive.mov",
    };

    static final String USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    static final int CONNECT_TIMEOUT_MS = 3500;
    static final int READ_TIMEOUT_MS = 3500;

    /** Presupuesto máximo de un resolve() en el plugin (el host corta a 20 s). */
    static final long RESOLVE_BUDGET_MS = 18L * 1000L;

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
    static final String PLUGIN_VERSION = "0.1.0";
    static final String PLUGIN_CLASE = "com.infomak.moai.daddylive.DaddylivePlugin";

    private Config() {
    }
}