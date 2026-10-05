#!/usr/bin/env bash
# Compila el plugin .dex del contrato moai v1 para DaddyLive y regenera
# manifest.json (con sha256 del dex + canales del catálogo 24/7).
#
# Requisitos: JDK 8+, Android SDK (d8), python3 (stdlib).
#
# Opciones:
#   --verify          Verificar canales antes de generar el catálogo
#   --workers N       Número de workers paralelos para verificación (default: 5)
#   --timeout N       Timeout por petición en segundos (default: 20)
#
# OJO: el verificador SIEMPRE es scripts/verify_with_java.py, que usa la clase
# VerifyChannel (recibe el channel_id y devuelve la URL resuelta). El antiguo
# verify_channels.py de Python quedó eliminado: validaba el .m3u8 contra el
# servidor de streaming, que bloquea, y por eso reportaba 0 canales online.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
BUILD_TOOLS="${ANDROID_BUILD_TOOLS:-$SDK/build-tools/36.0.0}"
VERSION="0.4.12"

# IMPORTANTE: los resultados de verificación NUNCA van en build/, porque
# este script borra build/ por completo (rm -rf). Van en data/, que es permanente.
DATA_DIR="$ROOT/data"
mkdir -p "$DATA_DIR"

# Parse arguments
VERIFY=false
WORKERS=5
TIMEOUT=20
while [[ $# -gt 0 ]]; do
    case $1 in
        --verify)
            VERIFY=true
            shift
            ;;
        --workers)
            WORKERS="$2"
            shift 2
            ;;
        --timeout)
            TIMEOUT="$2"
            shift 2
            ;;
        *)
            echo "Unknown option: $1" >&2
            exit 1
            ;;
    esac
done

pick_platform() {
    for dir in "$SDK"/platforms/*; do
        [ -e "$dir/android.jar" ] && echo "$dir"
    done | sort | tail -n 1
}
PLATFORM="$(pick_platform)"
if [ -z "$PLATFORM" ] || [ ! -x "$BUILD_TOOLS/d8" ]; then
    echo "Falta el Android SDK (android.jar y build-tools/d8)." >&2
    exit 1
fi
ANDROID_JAR="$PLATFORM/android.jar"
D8="$BUILD_TOOLS/d8"

echo ">> Limpiando build/"
rm -rf "$ROOT/build"
mkdir -p "$ROOT/build/contract" "$ROOT/build/plugin" "$ROOT/build/out"

echo ">> Compilando stubs del contrato"
javac -source 8 -target 8 \
    -d "$ROOT/build/contract" \
    "$ROOT"/src/contract/java/com/infomak/moai/contract/*.java

echo ">> Compilando plugin (contra contrato + android.jar)"
javac -source 8 -target 8 \
    -cp "$ROOT/build/contract:$ANDROID_JAR" \
    -d "$ROOT/build/plugin" \
    "$ROOT"/src/plugin/java/com/infomak/moai/daddylive/*.java

( cd "$ROOT/build" && jar cf plugin.jar -C plugin . )
echo ">> Empaquetado: $(jar tf "$ROOT/build/plugin.jar" | wc -l) archivos"

echo ">> d8 -> dex"
"$D8" --min-api 21 \
    --lib "$ANDROID_JAR" \
    --classpath "$ROOT/build/contract" \
    --output "$ROOT/build/out" \
    "$ROOT/build/plugin.jar"

cp "$ROOT/build/out/classes.dex" "$ROOT/plugin.dex"
echo ">> plugin.dex: $(stat -c%s "$ROOT/plugin.dex") bytes"

SHA256="$(sha256sum "$ROOT/plugin.dex" | cut -d' ' -f1)"
echo ">> sha256: $SHA256"

# Verificar canales si se solicita
if [ "$VERIFY" = true ]; then
    echo ">> Verificando canales con $WORKERS workers (timeout: ${TIMEOUT}s)..."
    python3 "$ROOT/scripts/verify_with_java.py" \
        --workers "$WORKERS" \
        --timeout "$TIMEOUT" \
        --out "$DATA_DIR/verified_java.json"
    
    # Usar solo canales online para el catálogo
    ONLINE_JSON="$DATA_DIR/verified_java_online.json"
    if [ ! -f "$ONLINE_JSON" ]; then
        echo "ERROR: no se genero $ONLINE_JSON" >&2
        exit 1
    fi
    
    CANALES_COUNT="$(python3 -c 'import json,sys;print(len(json.load(open(sys.argv[1]))))' "$ONLINE_JSON")"
    echo ">> Canales verificados: $CANALES_COUNT online"
    
    # Generar catálogo con logos mejorados
    echo ">> Generando catálogo con logos mejorados..."
    python3 "$ROOT/scripts/build_catalog.py" --out "$DATA_DIR/canales.json" --online-only "$ONLINE_JSON"
else
    ONLINE_JSON="$DATA_DIR/verified_java_online.json"
    if [ -f "$ONLINE_JSON" ] && [ -s "$ONLINE_JSON" ]; then
        echo ">> Usando canales online verificados existentes ($ONLINE_JSON)..."
        python3 "$ROOT/scripts/build_catalog.py" --out "$DATA_DIR/canales.json" --online-only "$ONLINE_JSON"
    else
        echo ">> Generando catálogo desde /api/channels (sin verificación)"
        python3 "$ROOT/scripts/build_catalog.py" --out "$DATA_DIR/canales.json"
    CANALES_COUNT="$(python3 -c 'import json,sys;print(len(json.load(open(sys.argv[1]))))' "$DATA_DIR/canales.json")"
fi

echo ">> Enriqueciendo canales con logos..."
python3 "$ROOT/scripts/generate_logos.py" --input "$DATA_DIR/canales.json" --output "$DATA_DIR/canales.json"
CANALES_COUNT="$(python3 -c 'import json,sys;print(len(json.load(open(sys.argv[1]))))' "$DATA_DIR/canales.json")"
echo ">> canales: $CANALES_COUNT"


cat > "$ROOT/manifest.json" <<EOF
{
  "id": "moai_daddylive",
  "tag": "Daddy",
  "nombre": "Moai Daddylive",
  "version": "$VERSION",
  "minContrato": 1,
  "maxContrato": 1,
  "clase": "com.infomak.moai.daddylive.DaddylivePlugin",
  "sha256": "$SHA256",
  "canalInicial": "1",
  "canales":
EOF
cat "$DATA_DIR/canales.json" >> "$ROOT/manifest.json"
cat >> "$ROOT/manifest.json" <<EOF
}
EOF

echo ">> manifest.json: $(stat -c%s "$ROOT/manifest.json") bytes ($CANALES_COUNT canales)"
echo "OK"