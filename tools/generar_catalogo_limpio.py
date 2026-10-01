#!/usr/bin/env python3
"""Genera el catalogo limpio (solo canales ONLINE verificados) y el manifest.json.

REGLA DE ORO: los resultados SIEMPRE van a data/. Nunca a build/, porque
build.sh ejecuta "rm -rf build" en cada compilacion y borra todo lo que haya
ahi. Este script nunca toca build/.

Flujo:
  1. Verifica los canales (una sola vez) -> data/verified_channels.json
     - Si ya existe y esta completo, NO vuelve a verificar (usa --force para rehacerlo)
  2. Extrae los online             -> data/canales_online.json
  3. Genera catalogo con logos      -> data/canales.json
  4. Escribe manifest.json con el sha256 real de plugin.dex

Uso:
    python tools/generar_catalogo_limpio.py
    python tools/generar_catalogo_limpio.py --force          # rehace la verificacion
    python tools/generar_catalogo_limpio.py --solo-manifest   # no verifica, solo arma el manifest
"""
import argparse
import hashlib
import json
import os
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA = os.path.join(ROOT, "data")
SCRIPTS = os.path.join(ROOT, "scripts")
VERIFIED = os.path.join(DATA, "verified_java.json")
ONLINE = os.path.join(DATA, "verified_java_online.json")
CATALOG = os.path.join(DATA, "canales.json")
MANIFEST = os.path.join(ROOT, "manifest.json")
DEX = os.path.join(ROOT, "plugin.dex")

VERSION = "0.4.1"
PLUGIN_ID = "moai_daddylive"

PLUGIN_TAG = "Daddy"
PLUGIN_NAME = "Moai Daddylive"
PLUGIN_CLASS = "com.infomak.moai.daddylive.DaddylivePlugin"


def log(msg):
    print(f"[catalogo] {msg}", file=sys.stderr, flush=True)


def run(cmd):
    """Ejecuta un comando y falla duro si hay error."""
    log("ejecutando: " + " ".join(os.path.basename(c) for c in cmd))
    proc = subprocess.run(cmd, cwd=ROOT)
    if proc.returncode != 0:
        sys.exit(f"ERROR: fallo {' '.join(cmd)} (codigo {proc.returncode})")


def cargar_json(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def guardar_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)


def verificacion_usable():
    """True si data/verified_java_online.json o data/verified_java.json existen y tienen canales online."""
    if os.path.exists(ONLINE):
        try:
            online = cargar_json(ONLINE)
            if isinstance(online, list) and len(online) > 0:
                return True
        except Exception:
            pass
    if not os.path.exists(VERIFIED):
        return False
    try:
        data = cargar_json(VERIFIED)
    except Exception as e:
        log(f"verificacion guardada ilegible ({e}), se va a rehacer")
        return False
    canales = data.get("channels", []) if isinstance(data, dict) else data
    if not canales or not any(c.get("status") == "online" for c in canales if isinstance(c, dict)):
        return False
    return True


def verificar(workers, timeout):
    """Verifica los canales UNA vez con el plugin Java. Guarda siempre en data/.

    Usa verify_with_java.py (que corre BatchVerify en un único proceso JVM multihilo).
    """
    os.makedirs(DATA, exist_ok=True)
    log(f"verificando con el plugin Java (workers={workers}, corte por canal={max(timeout*2,40)}s)")
    log("guarda en data/ y NO se pierde al compilar")
    t0 = time.time()
    run([
        sys.executable,
        os.path.join(SCRIPTS, "verify_with_java.py"),
        "--workers", str(workers),
        "--timeout", str(timeout),
        "--out", VERIFIED,
    ])
    log(f"verificacion terminada en {time.time() - t0:.0f}s")


def extraer_online():
    """Arma la lista de online desde lo que produjo verify_with_java.py."""
    online = []
    if os.path.exists(ONLINE):
        try:
            online = cargar_json(ONLINE)
        except Exception as e:
            log(f"error leyendo {ONLINE}: {e}")
            online = []
    if not online and os.path.exists(VERIFIED):
        try:
            data = cargar_json(VERIFIED)
            canales = data.get("channels", []) if isinstance(data, dict) else data
            online = [c for c in canales if isinstance(c, dict) and c.get("status") == "online"]
        except Exception as e:
            log(f"error leyendo {VERIFIED}: {e}")
            online = []

    if not online:
        sys.exit("ERROR: cero canales online detectados. No se sobrescribe la lista online ni se genera el manifiesto.")

    guardar_json(ONLINE, online)
    log(f"online: {len(online)} canales")
    return online


def generar_catalogo():
    """Arma el catalogo con pais/categoria/logo via scripts/build_catalog.py."""
    run([
        sys.executable,
        os.path.join(SCRIPTS, "build_catalog.py"),
        "--out", CATALOG,
        "--online-only", ONLINE,
    ])
    cat = cargar_json(CATALOG)
    con_logo = sum(1 for c in cat if c.get("logo"))
    log(f"catalogo: {len(cat)} canales, {con_logo} con logo")
    if len(cat) != len(cargar_json(ONLINE)):
        log("AVISO: el catalogo tiene menos canales que la lista online")
    return cat


def escribir_manifest(cat):
    """Escribe manifest.json con el sha256 REAL de plugin.dex."""
    if not os.path.exists(DEX):
        sys.exit("ERROR: no existe plugin.dex, compila antes con build.sh")
    with open(DEX, "rb") as f:
        sha = hashlib.sha256(f.read()).hexdigest()

    manifest = {
        "id": PLUGIN_ID,
        "tag": PLUGIN_TAG,
        "nombre": PLUGIN_NAME,
        "version": VERSION,
        "minContrato": 1,
        "maxContrato": 1,
        "clase": PLUGIN_CLASS,
        "sha256": sha,
        "canalInicial": "1",
        "canales": cat,
    }
    guardar_json(MANIFEST, manifest)
    log(f"manifest.json escrito: {len(cat)} canales, v{VERSION}, sha256 {sha[:16]}")


def main():
    ap = argparse.ArgumentParser(description="Catalogo limpio de canales online verificados")
    ap.add_argument("--workers", type=int, default=5)
    ap.add_argument("--timeout", type=int, default=20)
    ap.add_argument("--force", action="store_true", help="Volver a verificar aunque ya haya datos")
    ap.add_argument("--solo-manifest", action="store_true", help="No verificar, solo armar el manifest")
    args = ap.parse_args()

    if args.solo_manifest:
        log("modo --solo-manifest: reutilizando data/canales.json")
        if not os.path.exists(CATALOG):
            sys.exit("ERROR: no existe data/canales.json, corre sin --solo-manifest primero")
        escribir_manifest(cargar_json(CATALOG))
        return

    if args.force or not verificacion_usable():
        if args.force:
            log("--force: se descarta la verificacion anterior")
        verificar(args.workers, args.timeout)
    else:
        log("verificacion existente en data/, se reutiliza (usa --force para rehacerla)")

    extraer_online()
    escribir_manifest(generar_catalogo())
    log("LISTO. resultados en data/ (permanente), manifest.json actualizado")


if __name__ == "__main__":
    main()
