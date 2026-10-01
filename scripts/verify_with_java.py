#!/usr/bin/env python3
"""Verifica canales de DaddyLive usando el plugin Java (más preciso).

Usa el plugin Java para resolver cada canal y verificar si está online.
Es el UNICO verificador. El antiguo verify_channels.py (Python) se elimino: al
validar el .m3u8 contra el servidor de streaming lo bloquean y reportaba 0 online.

Usa la clase VerifyChannel, que SI recibe el channel_id y devuelve la URL
resuelta. Ojo: DaddylivePlugin.main() NO sirve, es un autotest de campo fijo
que ignora el argumento y siempre resuelve los canales 521, 800 y 44.

Uso:
    python3 scripts/verify_with_java.py --workers 5 --timeout 20
    python3 scripts/verify_with_java.py --sample 20      # prueba rapida
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, asdict, is_dataclass
from typing import Optional

DOMAINS = ["daddylive.li", "daddylive.app", "daddylive.mov"]
UA = ("Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")


@dataclass
class ChannelStatus:
    id: str
    nombre: str
    status: str  # "online", "offline", "error"
    response_time_ms: int
    url: Optional[str] = None
    error_message: Optional[str] = None


def verify_channel_java(channel_id: str, nombre: str, java_classpath: str, timeout: int = 30) -> ChannelStatus:
    """Verificar UN canal usando VerifyChannel (NO DaddylivePlugin, que ignora el id)."""
    start = time.time()
    
    # El resolver de Java se auto-limita con Config.RESOLVE_BUDGET_MS = 20s,
    # mas el arranque de la JVM (~1s). Si el mata-procesos de Python es
    # igual a 20s matamos la JVM mientras todavia esta resolviendo y todos
    # los canales salen "Timeout". Por eso el corte va holgado.
    hard_timeout = max(timeout * 2, 40)
    
    try:
        cmd = [
            "java",
            "-cp", java_classpath,
            "com.infomak.moai.daddylive.VerifyChannel",
            channel_id,
        ]
        
        result = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            timeout=hard_timeout,
            encoding="utf-8",
            errors="replace"
        )
        
        elapsed = int((time.time() - start) * 1000)
        
        # VerifyChannel imprime un JSON con el resultado
        if result.returncode != 0:
            return ChannelStatus(
                id=channel_id, nombre=nombre, status="error",
                response_time_ms=elapsed,
                error_message=(result.stderr or "salida distinta de 0").strip()[:200]
            )
        
        data = parse_json_de_salida(result.stdout)
        if data is None:
            return ChannelStatus(
                id=channel_id, nombre=nombre, status="error",
                response_time_ms=elapsed,
                error_message="Java no devolvio JSON: " + (result.stdout or "").strip()[:200]
            )
        
        status = data.get("status", "offline")
        return ChannelStatus(
            id=channel_id,
            nombre=nombre,
            status="online" if status == "online" else "offline",
            response_time_ms=int(data.get("responseTimeMs") or elapsed),
            url=data.get("url"),
            error_message=data.get("error"),
        )
    
    except subprocess.TimeoutExpired:
        elapsed = int((time.time() - start) * 1000)
        return ChannelStatus(
            id=channel_id, nombre=nombre, status="error",
            response_time_ms=elapsed,
            error_message="Timeout"
        )
    except Exception as e:
        elapsed = int((time.time() - start) * 1000)
        return ChannelStatus(
            id=channel_id, nombre=nombre, status="error",
            response_time_ms=elapsed, error_message=str(e)[:200]
        )


def parse_json_de_salida(stdout: str):
    """Saca el JSON que imprime VerifyChannel (la ultima linea con { )."""
    for linea in reversed((stdout or "").splitlines()):
        linea = linea.strip()
        if linea.startswith("{") and linea.endswith("}"):
            try:
                return json.loads(linea)
            except json.JSONDecodeError:
                continue
    return None


def como_dict(r):
    """Un resultado puede ser dataclass (recien verificado) o dict (cargado del parcial)."""
    return asdict(r) if is_dataclass(r) else r


def estado(r):
    return r.get("status") if isinstance(r, dict) else r.status


def cid_de(r):
    return str(r.get("id") if isinstance(r, dict) else r.id)


def main():
    ap = argparse.ArgumentParser(description="Verificar canales usando el plugin Java")
    ap.add_argument("--workers", type=int, default=5,
                    help="Workers en paralelo (default: 5). Cada uno lanza su propia JVM.")
    ap.add_argument("--timeout", type=int, default=20,
                    help="Timeout base por canal (default: 20). El corte real es max(2x este, 40)s.")
    ap.add_argument("--sample", type=int, default=0, help="Verificar solo una muestra (0 = todos)")
    ap.add_argument("--domain", choices=DOMAINS + ["auto"], default="auto")
    ap.add_argument("--out", default="data/verified_java.json",
                    help="Archivo de salida (NUNCA en build/: build.sh borra esa carpeta)")
    ap.add_argument("--classpath", default=f"build{os.sep}plugin{os.pathsep}build{os.sep}contract", help="Classpath del plugin Java")
    ap.add_argument("--offset", type=int, default=0, help="Offset para continuar desde un canal específico (default: 0)")
    ap.add_argument("--legacy", action="store_true", help="Usar modo subprocess 1 JVM por canal (más lento)")
    args = ap.parse_args()
    
    # Crear la carpeta de salida ANTES de verificar: si no existe, el guardado
    # parcial falla en cada checkpoint y se pierde todo al cortarse.
    out_dir = os.path.dirname(args.out) or "."
    os.makedirs(out_dir, exist_ok=True)

    # Normalizar classpath para la plataforma actual
    cp_parts = [p.strip() for p in re.split(r"[;:]", args.classpath) if p.strip()]
    normalized_cp = os.pathsep.join(cp_parts)
    
    # Verificar que el plugin Java existe
    plugin_dir = cp_parts[0] if cp_parts else "build/plugin"
    if not os.path.exists(plugin_dir):
        print(f"ERROR: No se encontró el plugin Java en {plugin_dir}", file=sys.stderr)
        print("Ejecuta primero: ./build.sh", file=sys.stderr)
        sys.exit(1)

    # Delegar a BatchVerify (un solo JVM multihilo rápido y ligero)
    batch_class = os.path.join(plugin_dir, "com", "infomak", "moai", "daddylive", "BatchVerify.class")
    if os.path.exists(batch_class) and not args.legacy:
        print(f"[verify-java] Usando BatchVerify multihilo en Java ({args.workers} hilos)...", file=sys.stderr)
        cmd = [
            "java",
            "-cp", normalized_cp,
            "com.infomak.moai.daddylive.BatchVerify",
            "--workers", str(args.workers),
            "--out", args.out,
        ]
        if args.sample > 0:
            cmd.extend(["--sample", str(args.sample)])
        if args.offset > 0:
            cmd.extend(["--offset", str(args.offset)])
        proc = subprocess.run(cmd)
        if proc.returncode != 0:
            sys.exit(proc.returncode)
        return
    
    # Bajar catálogo
    domains = [args.domain] if args.domain != "auto" else DOMAINS
    channels = {}
    
    # Reintenta cada dominio: un 5xx puntual no debe abortar la corrida entera
    for domain in domains:
        for intento in range(3):
            try:
                url = f"https://{domain}/api/channels"
                req = urllib.request.Request(url, headers={"User-Agent": UA})
                with urllib.request.urlopen(req, timeout=20) as r:
                    if r.status != 200:
                        raise RuntimeError(f"HTTP {r.status}")
                    raw = json.loads(r.read().decode("utf-8", "replace"))
                    for item in raw:
                        name = (item.get("channel_name") or "").strip()
                        url_embed = item.get("url") or ""
                        m = re.search(r"id=([0-9]+)", url_embed)
                        if m:
                            cid = m.group(1)
                            if re.fullmatch(r"[0-9]+", cid):
                                channels[cid] = name or cid
                print(f"[verify-java] {domain}: {len(channels)} canales numéricos", file=sys.stderr)
                break
            except Exception as e:
                print(f"[verify-java] {domain} (intento {intento+1}/3): {e}", file=sys.stderr)
                channels = {}
                time.sleep(2 * (intento + 1))
        if channels:
            break
    
    if not channels:
        sys.exit("ERROR: no se pudo bajar el catálogo de ningun dominio")
    
    # Muestra si se especificó
    if args.sample > 0:
        import random
        sample_ids = random.sample(list(channels.keys()), min(args.sample, len(channels)))
        channels = {k: channels[k] for k in sample_ids}
        # Con muestra el nombre puede no estar: el id es el dato que importa
        channels = {k: (v or k) for k, v in channels.items()}
    
    # Verificar en paralelo
    items = sorted(channels.items(), key=lambda x: int(x[0]))
    
    # Aplicar offset si se especificó
    if args.offset > 0:
        items = items[args.offset:]
        print(f"[verify-java] Offset aplicado: {args.offset} canales saltados", file=sys.stderr)

    # El parcial va a un archivo aparte. Con --sample se le suma el tamaño
    # para que una prueba no contamine el parcial de la corrida real.
    base_out, ext_out = os.path.splitext(args.out)
    ext_out = ext_out or ".json"
    partial_file = f"{base_out}_partial{ext_out}"
    if args.sample > 0:
        partial_file = f"{base_out}_sample{args.sample}_partial{ext_out}"
    
    # Reanudar solo tiene sentido en la corrida real. Con --sample el muestreo
    # es aleatorio, asi que cada corrida toma canales distintos: mezclar el
    # parcial de una prueba con otra daria un catalogo de dos muestras distintas.
    results = []
    if args.sample > 0:
        if os.path.exists(partial_file):
            os.remove(partial_file)
        print("[verify-java] --sample: muestra nueva, se descarta cualquier parcial previo", file=sys.stderr)
    elif os.path.exists(partial_file) and args.offset == 0:
        try:
            with open(partial_file, encoding='utf-8') as f:
                results = json.load(f)
            if not isinstance(results, list):
                results = []
            print(f"[verify-java] Cargados {len(results)} resultados parciales", file=sys.stderr)
        except Exception as e:
            print(f"[verify-java] parcial ilegible ({e}), se empieza de cero", file=sys.stderr)
            results = []

    # Saltar los canales ya verificados en una corrida anterior
    if results:
        ya_hechos = {cid_de(r) for r in results}
        pendientes = [(c, n) for c, n in items if c not in ya_hechos]
        print(f"[verify-java] Reanudando: {len(ya_hechos)} ya verificados, "
              f"{len(pendientes)} pendientes", file=sys.stderr)
        items = pendientes

    total = len(items)
    if total == 0:
        print("[verify-java] No hay canales pendientes.", file=sys.stderr)
    else:
        print(f"[verify-java] Verificando {total} canales con {args.workers} workers...", file=sys.stderr)
        print(f"[verify-java] Corte por canal: {max(args.timeout*2, 40)}s "
              f"(el resolver de Java se auto-limita a 20s)", file=sys.stderr)

    start_time = time.time()
    nombre_de = dict(items)
    
    def guardar_parcial():
        """Volcar DICCIONARIOS (como_dict), nunca dataclasses: json.dump
        revienta con dataclass y antes el except: pass lo tapaba en silencio,
        por eso se perdia todo.

        Se escribe a un .tmp y se renombra: si el proceso muere justo durante
        el volcado, el parcial anterior queda intacto en vez de corrupto."""
        tmp = partial_file + ".tmp"
        with open(tmp, "w", encoding="utf-8") as fh:
            json.dump([como_dict(r) for r in results], fh, ensure_ascii=False, indent=2)
        os.replace(tmp, partial_file)

    with ThreadPoolExecutor(max_workers=args.workers) as executor:
        # Se mandan de a chunks para no encolar los 1284 de una: si el proceso
        # muere, solo se pierde el chunk en curso, no todo lo encolado.
        CHUNK = max(args.workers * 4, 20)
        enviados = 0
        i = 0
        while enviados < len(items):
            lote = items[enviados:enviados + CHUNK]
            enviados += len(lote)
            futures = {
                executor.submit(verify_channel_java, cid, name, args.classpath, args.timeout): cid
                for cid, name in lote
            }
            for future in as_completed(futures):
                i += 1
                try:
                    results.append(future.result())
                except Exception as e:
                    # Una future que explota NO puede abortar la corrida entera.
                    cid = futures[future]
                    print(f"[verify-java] worker fallo en {cid}: {e}", file=sys.stderr)
                    results.append(ChannelStatus(
                        id=cid, nombre=nombre_de.get(cid, cid), status="error",
                        response_time_ms=0, error_message=str(e)[:200]
                    ))
                
                if i % 10 == 0 or i == total:
                    # Guardado parcial cada 10 canales
                    try:
                        guardar_parcial()
                    except Exception as e:
                        print(f"[verify-java] NO se pudo guardar el parcial: {e}", file=sys.stderr)
                    
                    elapsed = time.time() - start_time
                    rate = i / elapsed if elapsed > 0 else 0
                    eta = (total - i) / rate if rate > 0 else 0
                    n_on = sum(1 for r in results if estado(r) == "online")
                    n_err = sum(1 for r in results if estado(r) == "error")
                    print(
                        f"[verify-java] Progreso: {i}/{total} ({100*i//total}%) - "
                        f"{elapsed:.0f}s - ETA {eta:.0f}s - "
                        f"Online: {n_on} | Errores: {n_err}",
                        file=sys.stderr
                    )
    
    # Guardado final garantizado
    try:
        guardar_parcial()
    except Exception as e:
        print(f"[verify-java] NO se pudo guardar el parcial final: {e}", file=sys.stderr)
    
    # Estadísticas
    online = [r for r in results if estado(r) == "online"]
    offline = [r for r in results if estado(r) == "offline"]
    errors = [r for r in results if estado(r) == "error"]
    
    total_time = time.time() - start_time
    n = len(results) or 1
    
    print(f"\n[verify-java] {'='*60}", file=sys.stderr)
    print(f"[verify-java] RESULTADO:", file=sys.stderr)
    print(f"[verify-java]   ✓ Online: {len(online)} ({100*len(online)//n}%)", file=sys.stderr)
    print(f"[verify-java]   ✗ Offline: {len(offline)} ({100*len(offline)//n}%)", file=sys.stderr)
    if errors:
        print(f"[verify-java]   ⚠ Errores: {len(errors)}", file=sys.stderr)
    print(f"[verify-java]   Tiempo total: {total_time:.1f}s", file=sys.stderr)
    print(f"[verify-java]   Promedio: {total_time/max(1,total):.1f}s por canal", file=sys.stderr)
    
    # Guardar resultados
    os.makedirs(out_dir, exist_ok=True)
    output = {
        "summary": {
            "total": len(results),
            "online": len(online),
            "offline": len(offline),
            "errors": len(errors),
            "total_time_seconds": round(total_time, 1),
            "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        },
        "channels": sorted((como_dict(r) for r in results), key=lambda x: int(x["id"])),
    }
    
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(output, f, ensure_ascii=False, indent=2)
    
    print(f"[verify-java] Resultados guardados en {args.out}", file=sys.stderr)
    
    # Generar lista de canales online para el catálogo.
    # Se arma desde results (NO desde items) porque al reanudar items
    # solo contiene los pendientes y se perderían los ya verificados.
    catalog = sorted(
        ({"id": cid_de(r), "nombre": (r.get("nombre") if isinstance(r, dict) else r.nombre)}
         for r in online),
        key=lambda x: int(x["id"])
    )
    
    # Ruta DISTINTA al de resultados: si coincide, el catalogo online
    # pisa los resultados completos y se pierde todo el trabajo.
    base, ext = os.path.splitext(args.out)
    if catalog:
        with open(catalog_out, "w", encoding="utf-8") as f:
            json.dump(catalog, f, ensure_ascii=False, indent=2)
        print(f"[verify-java] Catálogo online guardado en {catalog_out} ({len(catalog)} canales)", file=sys.stderr)
    else:
        print("[verify-java] AVISO: 0 canales online detectados. No se sobrescribe el catálogo online previo.", file=sys.stderr)
    
    # Chequeo de integridad: si no se verificaron todos los canales del catálogo
    # queda claro, para que un corte no se tome por un resultado final.
    if not args.sample and len(results) < len(channels):
        print(f"[verify-java] AVISO: se verificaron {len(results)} de {len(channels)}. "
              f"Faltan {len(channels) - len(results)}: relanza el mismo comando para reanudar.",
              file=sys.stderr)
    if errors:
        print(f"[verify-java] AVISO: {len(errors)} canales con ERROR (no es 'offline' real). "
              f"Relanza el mismo comando y la reanudacion los reintenta.", file=sys.stderr)


if __name__ == "__main__":
    main()
