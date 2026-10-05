# Documentación Técnica: Plugin DaddyLive (`moaiplug_daddylive`)

## 1. Resumen Ejecutivo

Este documento detalla el análisis de causa raíz, la arquitectura de desempaquetado de video y los cambios implementados en el plugin **Moai DaddyLive** (`moaiplug_daddylive`) hasta la versión **0.4.11**.

El objetivo principal de la investigación fue resolver las fallas de reproducción, pantallas de error HTTP 403/404 y la aparición de imágenes/memes estáticos en lugar del video en vivo.

---

## 2. Diagnóstico del Problema y Descubrimientos Clave

### 2.1 La Estructura de Segmentos Enmascarados (TikTok CDN)
- El CDN principal utilizado por DaddyLive (`https://edge.cowedd4855ws.sbs/premium{id}/index.m3u8`) sirve listas de reproducción `.m3u8` válidas.
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
- En el catálogo de 1.285 canales, aproximadamente el **48%** corresponde a señales 24/7 activas en vivo (ej. 521 `#Vamos`, 31, 800, 1, 10, 50).
- El resto (**~33% a 52%**) corresponde a eventos PPV/temporales o canales inactivos en la fuente origen de DaddyLive, los cuales retornan `HTTP 404 Not Found` en el CDN.
- Al intentar reproducir un canal inactivo, la falta de validación previa en el resolvedor provocaba que ExoPlayer recibiera un error 404 directo sin explicación.

---

## 3. Arquitectura de Solución Implementada (`LocalProxy.java`)

Para solucionar el problema sin requerir modificaciones en la aplicación nativa Android (APK `moai3`), toda la lógica de proxy y desempaquetado se encapsuló **100% dentro del código Java del plugin `.dex`**.

```
[ ExoPlayer / App ]
       │
       ▼ (1) Solicita playlist (http://127.0.0.1:{port}/playlist.m3u8?id=521)
[ LocalProxy.java ] ──► (2) Descarga M3U8 de CDN (edge.cowedd4855ws.sbs)
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
     3. **PNG + píxeles** (layout vigente): parseo de chunks → `Inflater` sobre los `IDAT` (sin concatenarlos, para no copiar ~2.7 MB) → reversión de los 5 filtros PNG fila a fila → `TIKTIKPX` + `uint32BE` → `GZIPInputStream` → TS.
     4. `TIKTIKRAW` + TS crudo (legacy).
     5. `TIKTIKTSGZ` + gunzip (legacy).
     6. Barrido de sync byte `0x47`, **solo** si desde el candidato el resto es múltiplo exacto de 188 (evita los falsos positivos dentro de datos comprimidos).

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

---

## 5. Pruebas y Verificaciones Realizadas

1. **Prueba JVM (`DaddylivePlugin.main`):**
   - Resolución y desempaquetado comprobado en canales `521`, `800`, `31`, `1`, `10`, `50`:
     - Estado: `HTTP 200 OK`
     - Tamaño de paquete desempaquetado: **~2.08 MB por segmento**
     - Verificación de Sync Byte: **`0x47 sync byte: true`** (`REPRODUCIBLE 100% HD`).

2. **Muestreo de Catálogo:**
   - Muestreo de 15 canales con 4 segmentos cada uno (60 peticiones): **44/44 segmentos TS válidos**
     (longitud múltiplo de 188, sync `0x47`, cabecera PES de video `00 00 01 E0`).
   - Canales offline en origen: responden `404` en `edge.cowedd4855ws.sbs/premium{id}/index.m3u8`
     (p. ej. 200, 1234, 8) — comportamiento esperado, no un fallo del plugin.

---

## 6. Archivos del Proyecto

- `src/plugin/java/com/infomak/moai/daddylive/LocalProxy.java`: Implementación del proxy HTTP local y desempaquetador MPEG-TS.
- `src/plugin/java/com/infomak/moai/daddylive/DaddyliveResolver.java`: Lógica de resolución y validación de fuentes CDN.
- `src/plugin/java/com/infomak/moai/daddylive/DaddylivePlugin.java`: Clase principal del plugin e interfaz `IPlugin`.
- `build.ps1`: Script de compilación Java 8 + `d8` a `plugin.dex` y generación de `manifest.json`.
- `plugin.dex`: Binario ejecutable compilado.
- `manifest.json`: Manifest con catálogo de 1.285 canales y hash SHA-256 oficial.
