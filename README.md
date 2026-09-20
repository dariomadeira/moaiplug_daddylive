# Moai DaddyLive — plugin .dex (Contrato moai v1)

Plugin del motor moai3 que aporta canales 24/7 de DaddyLive resueltos directamente en el cliente mediante el contrato de plugins v1. El plugin resuelve la señal entregando la URL directa con los headers necesarios (`Referer`, `User-Agent`); el motor ExoPlayer de moai3 reproduce la transmisión sin requerir servidor proxy intermediario.

- **Tag:** `Daddy`
- **ID:** `moai_daddylive`
- **Versión:** `0.1.0`
- **Canales:** ~1274 canales numéricos categorizados (Deportes, Cine, Noticias, etc.).

## Instalación en moai3

Desde **Fuentes / Plugins** en la app, ingresar cualquiera de estas URLs (HTTPS):

- `https://raw.githubusercontent.com/dariomadeira/moaiplug_daddylive/main/manifest.json`
- `https://raw.githubusercontent.com/dariomadeira/moaiplug_daddylive/main/plugin.dex`

El host descarga el manifest, verifica el `sha256` contra el `.dex` y carga los canales en el catálogo.

## Construcción

Requiere JDK 8+ y Android SDK (con `d8` en `build-tools`).

```bash
./build.sh
```

Genera `plugin.dex` y `manifest.json` (con `sha256` y catálogo de canales actualizado).

### Autotest en JVM

```bash
java -cp build/plugin:build/contract com.infomak.moai.daddylive.DaddylivePlugin
```
