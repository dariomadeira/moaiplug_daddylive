# Plan — `moaiplug_daddylive` (plugin .dex, contrato moai v1)

Portal completo del flujo DaddyLive de moaiServer a un plugin Java/DEX para el
motor moai3, siguiendo el patrón de `moaiplug_telefe`. Documento de PLANEO: no
hay código aún, solo diseño y fases.

---

## 1. Objetivo

Aportar al catálogo de moai3 **todos los canales 24/7 de DaddyLive (~1300)** y
resolver su señal para reproducción directa en el motor, **sin** el problema del
server actual (re-scrape y token-swap cada 30–60 s). El plugin solo resuelve
(`IPlugin.resolve`); el motor reproduce "tal cual" — mismo contrato y división
que `moaiplug_telefe`.

## 2. Contexto / lecciones aplicadas

| Aprendizaje (research + moaiServer) | Decisión de diseño del plugin |
|---|---|
| Catálogo DaddyLive tiene JSON oficial estable: `/api/channels` (~1300 canales), redis por ellos ~1 min. | Catálogo se baja de ese JSON (no scrape de `24-7-channels.php`). |
| Canales 24/7 resuelven a CDN estable (xameleon/`phantemlis.top`, `…/tracks-v1a1/mono.m3u8`) con `Referer` fijo (`lewblivehdplay.ru` o el origin del embed) + UA. Duran horas sin re-resolución. | `ttlMs` largo (horas) en `ResolveResult` + cache en la instancia. **Nada de timers de 30/60 s.** |
| El intercambio de tokens `s/e` a mitad de sesión rompe el playback (bug principal de moaiServer). | El plugin entrega una URL estable y no la reconstruye: si se muere, la app reintenta con `fallbackIndex++`. |
| Los dominios de DaddyLive rotan seguido. | Lista de dominios/mirrors configurable en el plugin + fallback secuencial. |
| El server pierde canales si el scrape devuelve `[]`. | Si falla el catálogo, el plugin sirve la última lista en cache (persistida en memoria/archivo), nunca vacía. |

## 3. Estructura esperada de la carpeta

```
moaiplug_daddylive/
├── PLAN.md                  # este documento
├── README.md                # instalación/uso/construcción (como telefe)
├── manifest.json            # generado por build.sh (id/clase/sha256 + canales)
├── plugin.dex               # generado por build.sh
├── build.sh                 # clon adaptado de moaiplug_telefe/build.sh
└── src/
    ├── contract/java/com/infomak/moai/contract/*.java   # espejo v1 (SIN modificar)
    └── plugin/java/com/infomak/moai/daddylive/
        ├── DaddylivePlugin.java        # implementa IPlugin
        ├── ChannelCatalog.java         # carga y cache del catálogo
        ├── ChannelParser.java          # heurística país/categoría/logos
        ├── DaddyliveResolver.java      # port del resolver (watch→iframe→sub→decrypt)
        ├── Config.java                 # dominios, timeouts, TTL, headers
        └── Http.java                   # helper HttpURLConnection + JSON (sin libs)
```

## 4. Contrato v1 — lo que cubre (verificación en 4b)

- `PluginManifest(id, nombre, version, minContrato, maxContrato, canales[], clase)`:
  soporta N canales en runtime. **OK para ~1300.**
- `ResolveRequest(channelId, fallbackIndex)`: el host llama SIEMPRE con
  `fallbackIndex = 0` (ver 4b Hallazgo 2); el selector de player Daddylive
  (1–6) pasa a ser lógica interna del plugin.
- `ResolveResult(url, headers Map, drm, format, ttlMs)`: entrega `.m3u8` +
  `Referer`/`Origin`/`User-Agent` + `format="hls"` + **`ttlMs` largo**. Cubre
  todo lo que DaddyLive necesita. `drm = null` (HLS sin DRM).

## 4b. FASE 0 — Verificación del host moai3 (HECHA, solo lectura)

Archivos auditados: `moai3/android/.../plugin/PluginLoader.kt`,
`moai3/lib/services/plugin_host_service.dart`,
`moai3/lib/features/player/playback/channel_playback_helpers.dart`,
`moai3/android/.../MainActivity.kt`, `moai3/lib/services/plugin_update_service.dart`.
No se modificó nada de moai3.

### Hallazgo 1 — La lista de canales vive SOLO en el `manifest.json` estático
`parseManifest` (PluginLoader.kt:232-282) lee `canales` del JSON descargado;
si queda vacío lanza **"manifest.json sin canales"** (línea 269-271). `pluginMap`
(línea 407-423) y `scanInstalledFromDisk` (381-403) usan ese manifest guardado.
**`IPlugin.manifest()` NUNCA se invoca en runtime.**
▶ **Consecuencia:** el plugin debe venir con `manifest.json` que contenga TODOS
los canales (~1300) y cada cambio de catálogo es una **actualización de fuente**
(bump de `version` + nuevo `sha256`, ver `plugin_update_service.dart` que compara
versión semántica y sha256). No hay catálogo dinámico en runtime.

### Hallazgo 2 — `resolve()` se llama SIN `fallbackIndex`
`resolve()` (PluginLoader.kt:302-322) hace `ResolveRequest(channelId = channelId)`.
La app (channel_playback_helpers.dart) trata al canal-plugin como 1 sola URL:
`playableUrlCount == 1`, y cada intento re-resuelve en el plugin.
▶ **Consecuencia:** el plugin DEBE seleccionar/rotar el player Daddylive
internamente (players 1–N) y cachear el que funcionó; la app no reintenta por player.

### Hallazgo 3 — Timeout estricto de 20 s por `resolve()`
`RESOLVE_TIMEOUT_SECONDS = 20L` (PluginLoader.kt:41). Superado → cancela y error.
▶ **Consecuencia:** el flujo completo (scrape watch → iframe → sub-iframe →
decrypt) debe caber en <20 s. Timeouts cortos por request (4–6 s) y máx. 2–3
intentos de dominio/player dentro del resolve; usar cache de instancia para que un
reshape no vuelva a scrapear.

### Hallazgo 4 — Los headers del `ResolveResult` llegan a TODA la cadena HLS
`MainActivity.kt:324-331` monta `DefaultHttpDataSource.Factory()
.setDefaultRequestProperties(headers).setAllowCrossProtocolRedirects(true)`
+ `userAgent`. Con format `hls` → media3 HLS. Referer/Origin/UA del resultado
valen para master, variantes, segmentos y keys.
▶ **Consecuencia:** NO hace falta proxy HLS (a diferencia de moaiServer): el
plugin entrega la URL directa + headers y el motor reproduce.

### Hallazgo 5 — La instancia del plugin vive entre `resolve()`; `ttlMs` es solo hint
`instances` (PluginLoader.kt:61,351) reutiliza la misma instancia del plugin.
`ttlMs` sí llega a Dart pero la app no lo usa para cachear.
▶ **Consecuencia:** el cache del plugin va en campos de instancia (patrón telefe:
`cachedUrl`/`cachedExpiresAt`). Con TTL de horas, reabrir un canal sale al instante
y sin re-scrape — el fin del "cada 1 minuto".

### Hallazgo 6 — `manifest.json` de ~1300 canales es viable
Cada entry ≈120–180 B → ~200 KB en `manifest.json` (GitHub raw OK, disco OK,
MethodChannel OK, json.decode OK). Los canales 18+ se filtran en el build script.

### Hallazgo 7 — DRM trivail
`drm` se mapea a WIDEVINE/CLEARKEY; DaddyLive es HLS simple → `drm = null`.

### Implicaciones de diseño final
- `manifest.json` = catálogo completo, generado por un script de build desde el
  JSON `/api/channels` de DaddyLive (con heurística país/categoría y logos).
- Resolver interno con rotación de players y cache de instancia de larga vida.
- Excluir del manifest los feeds WebP-wrapped (family "plus"/hub) que ExoPlayer
  no parsea; quedarse con los que dan HLS limpio (24/7 + tv1).

---

## 4d. FASE 1 — Verificación EN VIVO del catálogo y del resolver (HECHA, 2026-09-20)

Pruebas con curl/python3 contra las URLs reales, sin tocar nada fuera de esta
carpeta. Herramienta disponible en el entorno local (JDK 21, Android SDK
`$HOME/Android/Sdk` con build-tools 36.0.0 + platforms 34–37, python3).

### Catálogo (listo para el build script)
- `GET https://{daddylive.li|daddylive.app|daddylive.mov}/api/channels` → 200,
  JSON plano `[{channel_name, url: ".../player/embed.php?id=NNN"}, ...]`.
- **1341 canales** hoy: **1274 con id numérico**, 67 con id `stream-N`
  (p. ej. `stream-144`). Se midió tamaño por canal ≈120–180 B.
- `dlhd.st` (301→`dlive.sx`) y `daddylive.club` (301→DMCA de premierleague.com)
  ya NO sirven el API; `daddylive.sx`/`daddylive.mp` están caídos (000).

### Resolver (esquema que SÍ funciona puro-HTTP HOY)
Cadena por canal numérico, validada 5/5 canales (#Vamos 521, A Spor 1011,
6'eren 800, 3 Schweiz 44, ...):
1. `GET {domain}/player/embed.php?id={id}` → constante `PLAYERS = [{src, hls}]`
   con 4 slots. Los `source=tv1..tv13` NO cambian el array en este layout.
2. Slot 3 `http://freetvspor.cfd/live/stream-{id}.php` (con `Referer` = embed)
   → iframe a `https://tiestep.top/e/{code}` (player v9/hls.js + SwarmCloud).
3. `GET https://tiestep.top/e/{code}` (con `Referer` = freetvspor) →
   `<script id="config">window._econfig='{\~148 KB de b64\}'</script>`.
4. Decrypt `_econfig` con el algoritmo de moaiServer (order `[2,0,3,1]`, 4
   partes, slice(0,3)+slice(4) por parte, re-base64) → JSON con
   `stream_url` / `stream_url_nop2p`.

Resultado para #Vamos Spain:
`https://e8975o.7odxv0l067ka.net:8443/hls/ul201cjsd2h7i.m3u8?s=...&e=...`

Nota importante del autotest Java: los canales **fuera de señal** devuelven
**404** en el CDN tras el decrypt (verificado con python y curl: A Spor 1011 a
las 15:20 UTC daba 404 mientras 44/2/16/857 daban 200). El plugin lo reporta
honestamente como fallo de resolución (validación de `#EXTM3U` en resolve-time)
— es el comportamiento correcto, no un bug.

### Reproducibilidad (validado, decisivo)
- El **playlist** responde 200 sin headers especiales.
- Los **segmentos `.ts` dan 403** con cualquier Referer EXCEPTO el origin del
  player v9: **`Referer: https://tiestep.top/`** → **200 (1,4 MB TS)**.
  (El CDN firma segmentos por-referer; sin ese header nada reproduce.)
- ▶ **Decisión:** el `ResolveResult` entrega el `.m3u8` con headers
  `{User-Agent: móvil, Referer: https://tiestep.top/}`. El motor moai3 aplica
  headers a TODA la cadena (4b Hallazgo 4) → master, variantes y segmentos con
  el referer correcto → clic play sin proxy.

### Vida del token y consecuencias de diseño
- La URL firmada lleva `e` (expiry): **~3-6 h** por resolución (medido en vivo:
  e=1789934338 → ~6 h tras su emisión). Sin token, playlist 403; el campeón
  `s` es HMAC (no re-sellable). TTL_CAP del plugin = 6 h.
- ▶ **Decisión:** el cache de instancia se clavea por canal y expira en
  `min(e - now, TTL_CAP)`. Reabrir el canal dentro de la ventana = cache hit
  (sin red); abrir tras expirar = 1 resolución rápida (~3 GETs, <2 s).
- **Límite conocido (se documenta):** una sesión abierta aguanta ~hasta `e`;
  el host no re-resuelve mid-stream. O sea: el fin del "cada 1 minuto" del
  server se cumple (cero proceso servidor); un stream largo (films/lives 24/7
  de horas) se conserva sin re-resolver durante **horas**, y solo se reabre si
  agota su ventana. Mitigación a futuro en Fase 2/3:
  preferir una familia de CDN estable si aparece (hub/xameleon ya no se sirve
  por HTTP puro) o cachear el código `tiestep` y solo re-decryptar.

### Familias descartadas HOY (por qué no están en el resolver)
- `nontongo.win/livetv/{id}` (slot 1): player "hub" WebP + anti-devtool + CF,
  sin m3u8 por HTTP puro.
- `worldsportz4u.cfd/online/stream-{id}.php` (slot 2): "Unauthorized Embed
  Blocked" (hotlink-check server-side).
- `gomstream.xyz/live/stream-{id}.php` (slot 4): popads JS, sin fuente.
- `dlive.sx` (ex-dlhd): `watch.php?id=` → `stream-{id}.php` de 640 KB con JS
  ofuscado; ninguno de los esquemas A/B/C coincide. Solo cubre los 67 ids
  `stream-N` → **se excluyen del manifest MVP** (Fase 2/3 si se descifran).

---

## 4c. Contrato v1 — decisiones cerradas por Fase 0

## 5. Componentes a implementar

### 5.1 `ChannelCatalog` (build-time, no runtime)
- En Fase 1 el catálogo NO se baja en runtime: lo genera el build desde
  `GET https://{domain}/api/channels` (dominios respaldados: `daddylive.li`,
  `daddylive.app`, `daddylive.mov` — validados 200 en vivo) y queda VIAJANDO en
  el `manifest.json`. Así respetamos el host (4b Hallazgo 1).
- Script `scripts/build_catalog.py` (python3 stdlib): descarga el JSON, filtra
  ids `stream-N` (no reproducibles hoy, 4d), quita 18+, aplica heurística
  país/categoría/logo, y escribe el array `canales` para el `manifest.json`.
- Por canal arma `PluginChannel(id="{id_numerico}", nombre, logo, categoria, pais)`.

### 5.2 `ChannelParser`
- Port de `parseDaddyliveChannelName` (decode html, detección 18+, reglas país,
  keywords sports/news/kids/movie → categoría).
- Logos: usar `logo_url` del JSON si viene; si no, fallback a patrón
  `tv-logo/tv-logos` por nombre o vacío (para que el host muestre placeholder).

### 5.3 `DaddyliveResolver` (port del flow validado en 4d)
Flujo `resolve(channelId)` en <20 s con timeouts cortos:
1. `GET {domain}/player/embed.php?id={id}` (rota `daddylive.li` →
   `daddylive.app` → `daddylive.mov`) → parse del array `PLAYERS`.
2. Por cada slot (con `Referer` = URL del embed):
   - `hls:true` → la URL es el source directo (Scheme D).
   - página con `<iframe src="...">` → seguirlo (1 nivel) con `Referer` = slot,
     y sobre esa página aplicar los esquemas.
   - Schemes en la página (o sub-página) en orden:
     - **B:** `window._econfig='<b64>'` → decrypt order `[2,0,3,1]` → `stream_url_nop2p||stream_url`,
     - **A:** `source\s*:\s*window\.atob\('(<b64>)'\)` → b64decode,
     - **C:** regex de `https?://...m3u8` suelto.
3. **Validación de jugabilidad:** `GET` del m3u8 resultante (5 s); exigir 200 y
   cabecera `#EXTM3U`. Si falla, seguir con el siguiente slot/dominio.
4. Headers del resultado: `{User-Agent: móvil, Referer: https://{origin_del_player_v9}/}`
   (necesario para los segmentos; 4d). Sin pasarlo no reproduce.
5. Cache de instancia: `channelId -> {url, headers, expiresAtMs}` con
   `ttl = min(e_param - now, TTL_CAP)` y negative-cache ~30 s en fallo.
   Re-resolver solo cuando el cache expiró. **Nunca** re-resolver por reloj
   dentro de la sesión (el host no lo pide).

### 5.4 `Config` / `Http`
- `EMBED_DOMAINS` (default: `daddylive.li`, `daddylive.app`, `daddylive.mov`),
  `UA` móvil Android, `CONNECT_TIMEOUT_MS`/`READ_TIMEOUT_MS` (~4–5 s),
  `TTL_CAP_MS` y margen de seguridad sobre `e`.
- `Http` con `HttpURLConnection` (como telefe), JSON sin libs (regex/parse
  simple), Base64 propio en Java 8 puro (sin `java.util.Base64` ni
  `android.util.Base64`, para no depender del API level en el dex).

## 6. Estrategia de no-resolución-perpetua (el corazón del plan)

- `ttlMs` del `ResolveResult` = vida restante del cache (`min(e - now, TTL_CAP)`
  con piso mínimo); el motor lo usa solo como hint.
- Dentro del plugin: cache por `channelId` con ese TTL; re-resolver solo si el
  cache expiró. **1 resolución ≈ 3 GETs + decrypt, < 2 s**, sin proceso
  servidor y sin scrape por reloj.
- Criterio de éxito frente al bug original del server: **una apertura de canal
  ≠ un scrape por minuto de TODOS los canales**. Con este plugin, la carga cae de
  "resoluciones constantes en moaiServer (TokenRefreshManager 30/60 s)" a
  "resolución solo al abrir cada canal (y, tras expirar `e`, al reabrir)".
- Riesgo residual documentado: sesión larga → caduca con `e` (~3-6 h). La app
  no re-resuelve mid-stream; el usuario reabre/reintenta tras horas, y el
  cache de instancia re-entrega el m3u8 sin red durante toda la ventana.
  Fase 2/3 buscan una familia de CDN estable para eliminarlo.

## 7. Dificultades / riesgos conocidos

1. **`manifest.json` estático vs catálogo dinámico** (sección 4.1) — la decisión
   depende del comportamiento real del host moai3. Es el único punto que puede
   cambiar el alcance.
2. **Decrypt frágil:** `atob` + `_econfig` y familias futuras (xor/AES/hub/WebM).
   El port cubre los esquemas actuales de moaiServer; si DaddyLive cambia,
   toca actualizar `DaddyliveResolver`.
3. **Players con segmentos WebP→TS:** algunos feeds (family "plus"/hub) no son
   reproducibles "tal cual" por un player simple. Estrategia: excluirlos del
   catálogo y quedarse con los que dan HLS limpio (24/7 + tv1).
4. **Dominios rotativos y antirrejilla/Cloudflare:** mitigado con mirrors y UA.
5. **Tamaño:** 1300 `PluginChannel` en memoria es trivial; el riesgo es el
   transporte si el host serializa todo el manifest de golpe (orden de KB).
6. **Cache persistente:** el plugin no puede escribir en disco por defecto
   (sin contexto); validar si el host le da storage, si no queda cache en
   memoria y el catálogo se re-baja al abrir la app (aceptable, ~1 llamada).

## 8. Construcción y test

- `build.sh`: copiar el de telefe y adaptar paquete (`com.infomak.moai.daddylive`),
  id `moai_daddylive`, y generación de `manifest.json`.
- `main()` autotest en JVM (como telefe) que:
  - imprime `manifest().getCanales().size()`,
  - resuelve 2–3 canales de muestra (uno 24/7 y uno `.m3u8` firmado) e imprime
    `ResolveResult`, verificando headers y TTL.
- Prueba manual en moai3: instalar desde GitHub raw, abrir un canal 24/7 y uno
  deportivo, medir si el playback sobrevive >30–60 s SIN re-resolución (el
  criterio de éxito directo del bug del server).

## 9. Fases

- **Fase 0 — Verificación de host: ✅ HECHA** (sección 4b). Cierra el alcance:
  catálogo en `manifest.json` estático, resolver interno con rotación de player,
  headers aplicados a toda la cadena por el motor, cache de instancia.
- **Fase 1 — MVP: ✅ EN EJECUCIÓN** (hallazgos en vivo en 4d, 2026-09-20):
  - Script de build que baja `/api/channels` (1274 numéricos), filtra `stream-N`
    y 18+, aplica heurística país/categoría + logos y genera `manifest.json`.
  - `build.sh` (clon de telefe, paquete `com.infomak.moai.daddylive`).
  - `DaddylivePlugin` con resolver Scheme A/B/C/D (PLAYERS → v9/`_econfig`),
    validación de jugabilidad del m3u8 (200 + `#EXTM3U`), headers
    `Referer: https://tiestep.top/` + UA, cache por instancia hasta `e`,
    negative-cache, timeouts cortos (<20 s total).
  - Autotest JVM: manifest + resolve en vivo de 3 canales muestra con
    verificación de segmento (200).
- **Fase 2 — Robustez:** mirrors/dominios rotativos, cachear código `tiestep`
  para re-decrypt ultra rápido, exclusión dura de familias no reproducibles,
  ordenar slots por éxito histórico, telemetría mínima.
- **Fase 3 — Pulido:** categorías/grupos auto, 18+ opcional vía segundo manifest
  (o `stream-N` si se descifra dlive.sx), README final e instalación en moai3.

## 10. Preguntas abiertas

- ✅ **Resueltas por Fase 0:** autoridad de canales = `manifest.json` estático;
  motor aplica headers a toda la cadena; sin fallbackIndex; timeout 20 s;
  instancia única entre resolves; ttlMs solo hint.
- ✅ **Resueltas por Fase 1 (en vivo):** catálogo y dominios válidos; esquema de
  resolución reproducible puro-HTTP; header requerido para segmentos (tiestep);
  vida del token (`e` ~ 3-6 h); familias descartables.
- Pendientes (no bloquean MVP):
  - **Sesión larga vs `e` (~3-6 h):** ¿la app reintenta `resolve()` ante error
    de reproducción? Ese sería el único reseteo de sesión posible hoy. Con
    ventanas de horas el caso es raro; Fase 2/3 buscará CDN estable o
    re-decrypt intra-sesión si el contrato lo permite.
  - ¿El usuario prefiere 18+ en un manifest separado o verificarlo con la app?