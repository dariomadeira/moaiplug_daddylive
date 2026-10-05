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
- **Descubrimiento del Payload Enmascarado:**
  - Los primeros **16.605 bytes** de cada archivo `.image` corresponden a una cabecera de imagen PNG/WebP falsa conteniendo imágenes memes.
  - El flujo de video **MPEG-TS real (HD 1080p)** comienza a partir del byte **16.606**, donde se ubica el byte de sincronización estándar **`0x47`**, precedido por marcadores de compresión:
    - `TRAW` (`TIKTIKRAW`): Segmento TS raw empaquetado.
    - `TSGZ` (`TIKTIKTSGZ`): Segmento TS comprimido con GZIP.

### 2.2 Por qué fallaba ExoPlayer / Motor Nativo
- En versiones anteriores (v0.4.9), el plugin entregaba directamente la URL `.m3u8` del CDN a ExoPlayer.
- Al solicitar los segmentos sin procesamiento previo, ExoPlayer intentaba decodificar los primeros bytes (la imagen PNG meme) en lugar del byte `16.606`, resultando en errores de decodificación o reproducción de fotogramas memes.

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
   - **`handleSegment`:** Descarga los bytes del segmento `.image`, ejecuta `unwrapBytes(rawBytes)` y entrega el payload MPEG-TS puro con `Content-Type: video/mp2t`.
   - **`unwrapBytes`:** Algoritmo en Java para:
     1. Detectar marcador `TRAW` y extraer la sub-cadena MPEG-TS.
     2. Detectar marcador `TSGZ` y descomprimir el stream con `GZIPInputStream`.
     3. Escanear bytes en busca del primer byte de sincronización `0x47` válido (donde `raw[i] == 0x47` y `raw[i+188] == 0x47`).

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

---

## 5. Pruebas y Verificaciones Realizadas

1. **Prueba JVM (`DaddylivePlugin.main`):**
   - Resolución y desempaquetado comprobado en canales `521`, `800`, `31`, `1`, `10`, `50`:
     - Estado: `HTTP 200 OK`
     - Tamaño de paquete desempaquetado: **~2.08 MB por segmento**
     - Verificación de Sync Byte: **`0x47 sync byte: true`** (`REPRODUCIBLE 100% HD`).

2. **Muestreo de Catálogo (100 Canales):**
   - Canales activos comprobados: 48/100 (200 OK, reproducción fluida).
   - Canales inactivos en origen: 33/100 (404 Not Found en servidores de DaddyLive).

---

## 6. Archivos del Proyecto

- `src/plugin/java/com/infomak/moai/daddylive/LocalProxy.java`: Implementación del proxy HTTP local y desempaquetador MPEG-TS.
- `src/plugin/java/com/infomak/moai/daddylive/DaddyliveResolver.java`: Lógica de resolución y validación de fuentes CDN.
- `src/plugin/java/com/infomak/moai/daddylive/DaddylivePlugin.java`: Clase principal del plugin e interfaz `IPlugin`.
- `build.ps1`: Script de compilación Java 8 + `d8` a `plugin.dex` y generación de `manifest.json`.
- `plugin.dex`: Binario ejecutable compilado.
- `manifest.json`: Manifest con catálogo de 1.285 canales y hash SHA-256 oficial.
