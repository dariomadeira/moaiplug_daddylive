# Moai DaddyLive — plugin .dex (Contrato moai v1)

Plugin del motor moai3 que aporta canales 24/7 de DaddyLive resueltos directamente en el cliente mediante el contrato de plugins v1. El plugin resuelve la señal entregando la URL directa con los headers necesarios (`Referer`, `User-Agent`); el motor ExoPlayer de moai3 reproduce la transmisión sin requerir servidor proxy intermediario.

- **Tag:** `Daddy`
- **ID:** `moai_daddylive`
- **Versión:** `0.4.1`
- **Canales:** Catálogo categorizado y filtrado por canales online verificados.

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
