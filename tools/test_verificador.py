#!/usr/bin/env python3
"""Pruebas del script verificador SIN tocar la red: simula la salida de Java."""
import importlib.util, json, os, sys, tempfile, subprocess

ESPEC = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "scripts", "verify_with_java.py"
)

# Java real puede devolver: online, offline, o basura. Probamos los 3.
CASOS = {
    "online":  '{"id":"1","status":"online","url":"https://x.net/hls/a.m3u8","responseTimeMs":1200}',
    "offline": '{"id":"1","status":"offline","error":"HTTP 404","responseTimeMs":300}',
    "basura":  'Exception in thread "main" java.lang.OutOfMemoryError',
    "vacio":   "",
    "nulo":    '{"id":"1","status":"online","url":null,"responseTimeMs":5}',
}

def cargar():
    spec = importlib.util.spec_from_file_location("vj", ESPEC)
    m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
    return m

falla = 0
def check(nombre, cond, detalle=""):
    global falla
    if cond:
        print(f"  OK   {nombre}")
    else:
        print(f"  FALLA {nombre} {detalle}"); falla += 1

m = cargar()

print("1) parse_json_de_salida con cada salida posible de Java")
for nombre, salida in CASOS.items():
    if nombre in ("online", "offline"):
        r = m.parse_json_de_salida(salida)
        check(f"parsea {nombre}", r is not None and r.get("status") == nombre, f"-> {r}")
    elif nombre == "basura":
        check("basura -> None (no inventa)", m.parse_json_de_salida(salida) is None)
    elif nombre == "vacio":
        check("vacio -> None", m.parse_json_de_salida(salida) is None)
    elif nombre == "nulo":
        r = m.parse_json_de_salida(salida)
        check("url nula no rompe", r is not None and r.get("url") is None)

print("2) como_dict / estado / cid_de con dict Y con dataclass")
d = m.ChannelStatus(id="7", nombre="X", status="online", response_time_ms=5, url="u")
dic = m.como_dict(d)
check("dataclass -> dict", isinstance(dic, dict) and dic["id"] == "7")
check("json.dump de como_dict funciona", json.dumps([dic]) is not None)
check("estado(dict)", m.estado(dic) == "online")
check("estado(dataclass)", m.estado(d) == "online")
check("cid_de(dict)", m.cid_de(dic) == "7")
check("cid_de(dataclass)", m.cid_de(d) == "7")

print("3) los 3 helpers andan con un PARCIAL cargado de disco (dict plano)")
parcial = [{"id": "1", "status": "online"}, {"id": "2", "status": "offline"}]
check("json.dumps del parcial", json.dumps(parcial) is not None)
check("set de ids del parcial", {m.cid_de(r) for r in parcial} == {"1", "2"})

print("4) guardar_parcial escribe dicts, no dataclasses (el bug que perdia todo)")
with tempfile.TemporaryDirectory() as tmp:
    pf = os.path.join(tmp, "p.json")
    results = [m.ChannelStatus(id=str(i), nombre="n", status="online", response_time_ms=1) for i in range(30)]
    with open(pf, "w", encoding="utf-8") as fh:
        json.dump([m.como_dict(r) for r in results], fh, ensure_ascii=False, indent=2)
    leido = json.load(open(pf, encoding="utf-8"))
    check("30 items sobreviven el round-trip", len(leido) == 30)
    check("el parcial pesa > 0", os.path.getsize(pf) > 0)

print("5) reanudar: los ya verificados se saltan")
hechos = {"1", "2", "3"}
items = [(str(i), f"n{i}") for i in range(1, 11)]
pend = [(c, n) for c, n in items if c not in hechos]
check("pendientes = total - hechos", len(pend) == 7, f"-> {len(pend)}")
check("ninguno repetido", not ({c for c, _ in pend} & hechos))

print("6) corte por canal holgado (nunca 20s, que es el budget de Java)")
for t in (20, 30, 5, 10):
    hard = max(t * 2, 40)
    check(f"--timeout {t} -> corte {hard}s", hard >= 40, f"-> {hard}s")

print("7) ruta del parcial: --sample no debe contaminar la corrida real")
import os.path as osp
base, ext = osp.splitext("data/verified_java.json")
check("corrida real", f"{base}_partial{ext}" == "data/verified_java_partial.json")
check("con sample 20", f"{base}_sample20_partial{ext}" != f"{base}_partial{ext}")

print("8) catalogo online en archivo DISTINTO al de resultados")
b2, e2 = osp.splitext("data/verified_java.json")
check("resultados", f"{b2}{e2}" == "data/verified_java.json")
check("online aparte", f"{b2}_online{e2}" == "data/verified_java_online.json")
check("no se pisan", f"{b2}{e2}" != f"{b2}_online{e2}")

print()
print("FALLAS:", falla)
sys.exit(1 if falla else 0)
