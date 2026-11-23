# Documentación Técnica: Plugin DaddyLive (`moaiplug_daddylive`)

## 1. Resumen Ejecutivo

Este documento detalla el análisis de causa raíz, la arquitectura de desempaquetado de video y los cambios implementados en el plugin **Moai DaddyLive** (`moaiplug_daddylive`) hasta la versión **0.6.0**.

El objetivo principal de la investigación fue resolver las fallas de reproducción, pantallas de error HTTP 403/404 y la aparición de imágenes/memes estáticos en lugar del video en vivo.

---

## 2. Diagnóstico del Problema y Descubrimientos Clave

### 2.1 La Estructura de Segmentos Enmascarados (TikTok CDN)
- El CDN principal utilizado por DaddyLive (`https://edge.wedd48c.top/premium{id}/index.m3u8`, antes `edge.cowedd4855ws.sbs`) sirve listas de reproducción `.m3u8` válidas. El dominio `edge.*` rota, por lo que `EdgeTokens` lo descubre de `const EDGE = "..."` en la página del player y lo cachea 15 minutos.
- Sin embargo, los segmentos de video apuntan a URLs de imágenes en el CDN de TikTok:
  `https://p16-common-sign.tiktokcdn-us.com/tiktok-obj-tx/...image`
- **Layout vigente (verificado en vivo el 2026-10-05):** el `.image` es un **PNG válido**
  (`IHDR` 512x1808, bit depth 8, color type 2/3 canales, sin entrelazado) cuyo payload real va escondido
  en los píxeles:

  ```
  PNG (IHDR + N x IDAT + IEND)
      -> IDAT concatenado -> inflate zlib      (Inflater de java.util.zip)
      -> scanlines con filtro PNG -> se revierte el filtro fila a fila (None/Sub/Up/Average/Paeth)
      -> pixeles RGB = TIKTIKPX (8 bytes)
      -> pixeles[8..11] = uint32 BE = longitud del bloque gzip
      -> pixeles[12 .. 12+n) = gzip( MPEG-TS )
  ```

  Es decir: los píxeles del PNG **no son una imagen**, son el MPEG-TS comprimido con gzip.
- Layouts legacy que el player oficial aún contempla y que el plugin también acepta:
  `TIKTIKRAW` + TS crudo, `TIKTIKTSGZ` + gzip(TS), TS concatenado tras el `IEND` de un PNG,
  y TS dentro del chunk `EXIF` de un WebP.
- El algoritmo de referencia es el `unwrap()` del player oficial
  (`https://daddyliveplayer.st/premiumtv/*.php`, que carga `pako` para el inflate).

### 2.2 Por qué fallaba ExoPlayer / Motor Nativo
- El `unwrapBytes()` de la v0.4.10 solo buscaba los marcadores `TIKTIKRAW` / `TIKTIKTSGZ` y, como
  último recurso, hacía un barrido ingenuo de sync byte `0x47` cada 188 bytes.
- Con el layout nuevo ninguno de esos caminos aplica: los bytes comprimidos del PNG contienen
  `0x47` casuales (falsos positivos), así que el proxy entregaba ~2.7 MB de PNG comprimido etiquetado
  como `video/mp2t` y ExoPlayer no tenía nada decodificable. De ahí lapantalla de error / los memes.
- Bug adicional: `Http.readFully()` cortaba la descarga en **2 MB**, mientras los segmentos pesan entre
  1.2 y 2.7 MB (e incluso 5.6 MB en algunos canales). Aunque el desempaquetado fuera correcto, el
  segmento llegaba truncado.
- Bug adicional: el proxy solo leía la *request line* y respondía/cerraba dejando el resto de las
  cabeceras sin leer en el socket. Eso provoca un TCP RST y el cliente (ExoPlayer) pierde la respuesta
  de forma intermitente.

### 2.3 Canales Activos vs. Canales Inactivos (404 / 403)
- El catálogo vigente (899 canales 24/7 scrapeados de `24-7-channels.php`) mezcla señales activas con eventos PPV/temporales y canales inactivos en la fuente origen de DaddyLive, los cuales retornan `HTTP 404 Not Found` en el CDN.
- Al intentar reproducir un canal inactivo, la falta de validación previa en el resolvedor provocaba que ExoPlayer recibiera un error 404 directo sin explicación.

### 2.4 Cloudflare Turnstile en dembed y la vía `cast` (v0.6.0)
- Desde 2026-11 el player oficial `dembed.st/premiumtv/daddy.php` pide `/api/playlist-token` con un **Turnstile** que sólo se resuelve en el navegador. Sin ese token el CDN responde `403` y la vía `edge.*` deja de ser usable desde un cliente puro-HTTP.
- El player **`cast`** (`/cast/stream-{id}.php`) sigue entregando la señal sin captcha:
  ```
  GET /cast/stream-{id}.php          -> <iframe src="https://waterera.net/e/{hash}">
  GET {iframe}                       -> window._econfig = 'BASE64'
  decrypt(_econfig)                  -> { stream_url, stream_url_nop2p, ... }
  GET {stream_url}                   -> #EXTM3U con 7 segmentos MPEG-TS
  ```
- `_econfig` es Base64 de un Base64 partido en 4 trozos iguales: a cada trozo se le quita el 4.º carácter, se reordenan `2-0-3-1` y se decodifica. El resultado es el JSON con la URL firmada (`?s=...&e=...`, vigencia ~6 h).
- El playlist y los segmentos del CDN `7odxv0l067ka.net` exigen `Referer` del embed y `User-Agent` de navegador. El proxy ahora propaga el `Referer` correcto por segmento (`/segment?url=...&ref=...`), algo que antes estaba fijado a `dembed.st` y rompía la vía cast.
- Orden de fuentes en `LocalProxy.fetchPlaylist()`: **cast → tvnow247 → dembed/token**. La fuente cast se cachea por canal hasta que vence el `e=` de la URL; sólo se repite la cadena de páginas al vencer o ante `401/403/404`.

---

## 3. Arquitectura de Solución Implementada (`LocalProxy.java`)

Para solucionar el problema sin requerir modificaciones en la aplicación nativa Android (APK `moai3`), toda la lógica de proxy y desempaquetado se encapsuló **100% dentro del código Java del plugin `.dex`**.

```
[ ExoPlayer / App ]
       │
       ▼ (1) Solicita playlist (http://127.0.0.1:{port}/playlist.m3u8?id=521)
[ LocalProxy.java ] ──► (2) Descarga M3U8 de CDN (edge.* vigente, token por espectador)
       │              ◄── Lista M3U8 con segmentos TikTok (.image)
       │
       ▼ (3) Reescritura de URLs de segmentos -> http://127.0.0.1:{port}/segment?url=...
       │
       ▼ (4) ExoPlayer solicita segmento proxied
[ LocalProxy.java ] ──► (5) Descarga bytes .image de TikTok CDN
       │              ◄── Bytes raw (.image conteniendo PNG + TS)
       │
       ▼ (6) Ejecuta unwrapBytes() (Elimina PNG, busca sync byte 0x47 / descomprime TSGZ)
       │
       ▼ (7) Retorna bytes MPEG-TS puros (video/mp2t, empieza con 0x47)
[ ExoPlayer / App ] ──► Reproducción fluida HD
```

### Componentes Principales:

1. **`LocalProxy.java`:**
   - Servidor HTTP multiproceso ligero ejecutándose en `127.0.0.1` sobre un puerto dinámico disponible.
   - **`handlePlaylist`:** Descarga el playlist M3U8 de DaddyLive, reescribe los enlaces de segmentos para redirigirlos al proxy local y establece el `Content-Type: application/vnd.apple.mpegurl`.
   - **`handleSegment`:** Descarga los bytes del segmento `.image`, ejecuta `unwrapBytes(rawBytes)` y entrega el payload MPEG-TS puro con `Content-Type: video/mp2t`. Si el resultado no arranca en `0x47` devuelve `502` en vez de bytes basura.
   - **`unwrapBytes`:** Intentos en este orden, replicando el player oficial:
     1. **WebP** con chunk `EXIF` que contiene el TS.
     2. **PNG** con el TS concatenado tras el `IEND`.
     3. **PNG + IDAT crudo** (layout de tvnow247): IDAT único que ya contiene MPEG-TS (sin zlib).
     4. **PNG + píxeles** (layout TikTok): parseo de chunks → `Inflater` sobre los `IDAT` (sin concatenarlos, para no copiar ~2.7 MB) → reversión de los 5 filtros PNG fila a fila → `TIKTIKPX` + `uint32BE` → `GZIPInputStream` → TS.
     5. `TIKTIKRAW` + TS crudo (legacy).
     6. `TIKTIKTSGZ` + gunzip (legacy).
     7. Barrido de sync byte `0x47`, **solo** si desde el candidato el resto es múltiplo exacto de 188 (evita los falsos positivos dentro de datos comprimidos).

2. **`DaddyliveResolver.java`:**
   - Valida mediante una petición HTTP ligera que la fuente responda `200 OK` y contenga `#EXTM3U` antes de entregar la URL del proxy local.
   - En caso de canal inactivo (404/403), intenta la resolución mediante iframe en dominios espejo (*worldsportz4u*, *freetvspor*, *gomstream*). Si todos fallan, emite una excepción descriptiva en lugar de colgar el reproductor.

---

## 4. Historial de Cambios y Versiones

| Versión | SHA-256 | Cambios Principales |
| :--- | :--- | :--- |
| **v0.4.9** | `39506cc...` | Fast-path directo al CDN `edge.cowedd4855ws.sbs` sin desempaquetado. Provocó reproducción de imágenes memes y errores de decodificación. |
| **v0.4.10** | `dc4e7b7...` | Se añadió `LocalProxy.java` para interceptar segmentos y aplicar `unwrapBytes()`. Logró reproducciones comprobadas de 2.08MB de video puro con `0x47`. |
| **v0.4.11** | `83e7fb7...` | Se agregó validación HTTP 200 y comprobación de `#EXTM3U` en `DaddyliveResolver` antes de confirmar el fast-path, evitando errores 404 en canales inactivos. |
| **v0.4.12** | `7aa246e...` | DaddyLive cambió el enmascarado: el `.image` pasó a ser un **PNG cuyas filas de píxeles contienen `TIKTIKPX` + uint32 + gzip(TS)**. Se reescribió `unwrapBytes()` como port del `unwrap()` del player oficial (PNG → inflate IDAT → desfiltro Paeth/etc. → gunzip), se subió el tope de descarga de 2 MB a 24 MB con timeout propio para segmentos, y el proxy ahora drena las cabeceras de la petición para evitar el RST que hacía perder respuestas. |
| **v0.5.0** | `0f7f408...` | El CDN pasó a exigir token por espectador (`/api/playlist-token?slug=premium{id}`) y Referer/Origin del player; se añadió `EdgeTokens`, precarga de segmentos y respaldo tvnow247. |
| **v0.5.1** | `5d544d4...` | El dominio `edge.*` rotó de `edge.cowedd4855ws.sbs` a `edge.wedd48c.top`: `EdgeTokens` ahora descubre `const EDGE` de la página del player, lo cachea 15 min y lo invalida ante 401/403/DNS. La API `/api/channels` desapareció, así que el catálogo se scrapea de la grilla de `24-7-channels.php`. Se agregó el layout de tvnow247 (IDAT con TS crudo) a `unwrapBytes()`. |
| **v0.6.0** | `1823028...` | El CDN de dembed quedó detrás de Cloudflare Turnstile (`/api/playlist-token` → 403). Se añadió `CastResolver`: player `cast` → iframe `waterera.net` → `_econfig` (Base64 partido en 4, orden 2-0-3-1) → HLS firmado ~6 h. `LocalProxy` ahora propaga el `Referer`/`Origin` de cada fuente a los segmentos (`&ref=`), cachea la fuente cast por canal y prueba cast → tvnow247 → dembed. Los dominios `thedaddy.pk`/`thedaddy.li` se prueban en orden con backoff de 30 s. |

---

## 5. Pruebas y Verificaciones Realizadas

1. **Prueba JVM (`DaddylivePlugin.main`):**
   - Resolución y desempaquetado comprobado en canales `521`, `800` y `44`:
     - Estado: `HTTP 200 OK`
     - Tamaño de paquete desempaquetado: **~2.08 MB por segmento**
     - Verificación de Sync Byte: **`0x47 sync byte: true`** (`REPRODUCIBLE 100% HD`).

2. **Muestreo de Catálogo:**
   - Muestreo de 15 canales con 4 segmentos cada uno (60 peticiones): **44/44 segmentos TS válidos**
     (longitud múltiplo de 188, sync `0x47`, cabecera PES de video `00 00 01 E0`).
   - Canales offline en origen: responden `404` en `edge.*/premium{id}/index.m3u8`
     (p. ej. 200, 1234, 8) — comportamiento esperado, no un fallo del plugin.

3. **Prueba JVM v0.6.0 (`DaddylivePlugin.main`, 2026-11-22):**
   - Canales `44`, `31`, `521`, `800` y `140` resueltos por cast en **3.7–5.6 s**,
     con segmento desempaquetado `HTTP 200`, ~1–3 MB y `0x47 sync byte: true`.
   - `BatchVerify --sample 12 --workers 3`: **10/12 online (83 %)**; los 2 fallos
     fueron timeouts del sitio (`cast page HTTP 0`), no del pipeline.
   - `230` es un canal sin señal en cast (playlist `404`) y tvnow247 no respondió:
     se reporta como fuera de emisión, comportamiento esperado.

---

## 6. Archivos del Proyecto

- `src/plugin/java/com/infomak/moai/daddylive/LocalProxy.java`: Implementación del proxy HTTP local y desempaquetador MPEG-TS.
- `src/plugin/java/com/infomak/moai/daddylive/CastResolver.java`: Player `cast` → `_econfig` → HLS firmado (fuente principal v0.6.0).
- `src/plugin/java/com/infomak/moai/daddylive/EdgeTokens.java`: Token y descubrimiento del CDN de dembed (último recurso, detrás de Turnstile).
- `src/plugin/java/com/infomak/moai/daddylive/DaddyliveResolver.java`: Lógica de resolución y validación de fuentes CDN.
- `src/plugin/java/com/infomak/moai/daddylive/DaddylivePlugin.java`: Clase principal del plugin e interfaz `IPlugin`.
- `build.ps1`: Script de compilación Java 8 + `d8` a `plugin.dex` y generación de `manifest.json`.
- `plugin.dex`: Binario ejecutable compilado.
- `manifest.json`: Manifest con catálogo de 899 canales y hash SHA-256 oficial.
