#!/usr/bin/env bash
# Compila el plugin .dex del contrato moai v1 para DaddyLive y regenera
# manifest.json (con sha256 del dex + canales del catálogo 24/7).
#
# Requisitos: JDK 8+, Android SDK (d8), python3 (stdlib).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
BUILD_TOOLS="${ANDROID_BUILD_TOOLS:-$SDK/build-tools/36.0.0}"
VERSION="0.3.1"

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

echo ">> Generando catálogo desde /api/channels"
python3 "$ROOT/scripts/build_catalog.py" --out "$ROOT/build/canales.json"
CANALES_COUNT="$(python3 -c 'import json,sys;print(len(json.load(open(sys.argv[1]))))' "$ROOT/build/canales.json")"
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
  "canales":
EOF
cat "$ROOT/build/canales.json" >> "$ROOT/manifest.json"
cat >> "$ROOT/manifest.json" <<EOF
}
EOF

echo ">> manifest.json: $(stat -c%s "$ROOT/manifest.json") bytes ($CANALES_COUNT canales)"
echo "OK"