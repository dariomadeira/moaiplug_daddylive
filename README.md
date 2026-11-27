# Moai DaddyLive — plugin .dex (Contrato moai v1)

Plugin del motor moai3 que aporta canales 24/7 de DaddyLive resueltos directamente en el cliente mediante el contrato de plugins v1. Como el CDN enmascara cada segmento `.image` (PNG con el MPEG-TS escondido dentro de los píxeles), el plugin levanta un proxy HTTP local en `127.0.0.1` que desempaqueta los segmentos y se los entrega en MPEG-TS puro al motor ExoPlayer de moai3, sin servidor intermediario.

- **Tag:** `Daddy`
- **ID:** `moai_daddylive`
- **Versión:** `0.6.1`
- **Canales:** Catálogo vigente scrapeado de `https://thedaddy.pk/24-7-channels.php` (899 canales 24/7 el 2026-11-02).
- **Fuente principal:** API de tvnow247: resuelve el mismo id y sus dominios funcionan aunque el DNS del televisor no vea `thedaddy.*` ni el CDN cast (v0.6.1).
- **Respaldo:** player `cast` (`/cast/stream-{id}.php` → iframe `waterera.net` → `_econfig` → HLS firmado ~6 h) y, por último, el CDN de dembed con token (`edge.*`), hoy detrás de Cloudflare Turnstile.

## Instalación en moai3

Desde **Fuentes / Plugins** en la app, ingresar cualquiera de estas URLs (HTTPS):

- `https://raw.githubusercontent.com/dariomadeira/moaiplug_daddylive/main/manifest.json`
- `https://raw.githubusercontent.com/dariomadeira/moaiplug_daddylive/main/plugin.dex`

El host descarga el manifest, verifica el `sha256` contra el `.dex` y carga los canales en el catálogo.

## Construcción y Verificación

Requiere JDK 8+ y Android SDK (con `d8` en `build-tools`).

### En Windows (PowerShell)

```powershell
# 1. Compilación básica usando canales verificados existentes (permanentes en data/)
.\build.ps1

# 2. Compilación con verificación completa de canales
.\build.ps1 -Verify

# 3. Verificación rápida de muestra (ej: 20 canales)
py -3 scripts/verify_with_java.py --sample 20
```

### En Linux / Git Bash

```bash
# 1. Compilación básica (reutiliza data/verified_java_online.json existente)
./build.sh

# 2. Compilación con verificación completa
./build.sh --verify
```

### Comandos de Verificación Independiente

Los resultados de verificación **siempre se guardan en `data/`** para que nunca se borren al compilar:

```bash
# Verificar todos los canales con BatchVerify multihilo en Java (rápido, 1 solo JVM, 3 hilos seguros)
py -3 scripts/verify_with_java.py

# Verificar una muestra rápida (ej: 30 canales)
py -3 scripts/verify_with_java.py --sample 30

# O ejecutar directamente en Java
java -cp "build/plugin;build/contract" com.infomak.moai.daddylive.BatchVerify --sample 20
```

Archivos generados en `data/`:
- `data/verified_java.json` — Reporte completo con estado, tiempos de respuesta y errores de cada canal.
- `data/verified_java_online.json` — Lista limpia de canales online con URL `.m3u8` resuelta.
- `data/canales.json` — Catálogo generado para el plugin con nombres, categorías, países y logos.
- `manifest.json` — Manifiesto del plugin firmado con el sha256 real de `plugin.dex`.

## Autotest en JVM

```bash
# Probar un canal específico
java -cp "build/plugin;build/contract" com.infomak.moai.daddylive.VerifyChannel 521
```
