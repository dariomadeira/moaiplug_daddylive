#!/usr/bin/env python3
"""Genera el array `canales` del manifest.json del plugin moai_daddylive.

Baja `GET https://{domain}/api/channels` (daddylive.li|.app|.mov) y escribe
`build/canales.json` (solo el array) con PackagedPluginChannel:
    { "id": "<id numérico>", "nombre": ..., "logo": "", "categoria": ..., "pais": ... }

Filtros:
  - solo ids numéricos (los `stream-N` no son reproducibles HOY; PLAN 4d)
  - sin canales 18+ (el endpoint no los incluye)
Orden estable por id numérico para manifests reproducibles.
Uso: python3 scripts/build_catalog.py [--out build/canales.json] [--domain daddylive.li]
"""
import argparse
import json
import re
import sys
import urllib.request

DOMAINS = ["daddylive.li", "daddylive.app", "daddylive.mov"]
UA = ("Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")

COUNTRY_KB = {
    "spain": "España", "españa": "España", "italy": "Italia", "italia": "Italia",
    "france": "Francia", "francia": "Francia", "germany": "Alemania", "deutschland": "Alemania",
    "uk": "Reino Unido", "usa": "Estados Unidos", "brasil": "Brasil", "brazil": "Brasil",
    "portugal": "Portugal", "argentina": "Argentina", "mexico": "México", "mexico": "México",
    "australia": "Australia", "canada": "Canadá", "turkey": "Turquía", "turquia": "Turquía",
    "india": "India", "pakistan": "Pakistán", "poland": "Polonia", "polonia": "Polonia",
    "greece": "Grecia", "rusia": "Rusia", "russia": "Rusia", "ukraine": "Ucrania",
}

CATEGORY_SPORTS = [
    "soccer", "football", "futbol", "fútbol", "tennis", "tenis", "formula", "f1",
    "motogp", "nba", "nfl", "mlb", "nhl", "ufc", "boxing", "boxeo", "cricket",
    "rugby", "golf", "hockey", "basket", "basketball", "baloncesto", "cycling",
    "cycling", "darts", "snooker", "volleyball", "voleibol", "handball", "handbol",
    "mma", "wwe", "aew", "fight", "lucha", "deporte", "sport", "auto", "racing",
]
CATEGORY_NEWS = ["news", "noticias", "24h", "cnn", "bbc", "sky news", "al jazeera"]
CATEGORY_KIDS = ["kids", "disney", "cartoon", "nickelodeon", "infantil", "baby"]
CATEGORY_MOVIE = ["movie", "cinema", "cine", "series", "hbo", "star", "axn", "tnt"]


def fetch_channels(domain):
    url = f"https://{domain}/api/channels"
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=15) as r:
        if r.status != 200:
            raise RuntimeError(f"HTTP {r.status} en {url}")
        return json.loads(r.read().decode("utf-8", "replace"))


def country_of(name):
    low = name.lower()
    for kb, country in COUNTRY_KB.items():
        if kb in low:
            return country
    return ""


def category_of(name):
    low = name.lower()
    if any(k in low for k in CATEGORY_NEWS):
        return "Noticias"
    if any(k in low for k in CATEGORY_KIDS):
        return "Infantil"
    if any(k in low for k in CATEGORY_MOVIE):
        return "Cine"
    if any(k in low for k in CATEGORY_SPORTS):
        return "Deportes"
    return "General"


def build_channel(raw):
    name = (raw.get("channel_name") or "").strip()
    url = raw.get("url") or ""
    m = re.search(r"id=([0-9]+)", url)
    if not m:
        return None
    cid = m.group(1)
    if not re.fullmatch(r"[0-9]+", cid):
        return None
    return {
        "id": cid,
        "nombre": name,
        "logo": "",
        "categoria": category_of(name),
        "pais": country_of(name),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="build/canales.json")
    ap.add_argument("--domain", choices=DOMAINS + ["auto"], default="auto")
    args = ap.parse_args()

    errors = []
    channels = {}
    for domain in ([args.domain] if args.domain != "auto" else DOMAINS):
        try:
            raw = fetch_channels(domain)
            for item in raw:
                c = build_channel(item) if isinstance(item, dict) else None
                if c:
                    channels[c["id"]] = c
            print(f"[build_catalog] {domain}: {len(channels)} canales numéricos", file=sys.stderr)
            break
        except Exception as e:  # noqa: BLE001
            errors.append(f"{domain}: {e}")
    if not channels:
        sys.exit("ERROR: no se pudo bajar el catálogo: " + "; ".join(errors))

    ordered = [channels[k] for k in sorted(channels, key=lambda x: int(x))]
    import os
    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(ordered, f, ensure_ascii=False, indent=2)
    print(f"[build_catalog] ESCRIBIENDO {args.out} con {len(ordered)} canales", file=sys.stderr)


if __name__ == "__main__":
    main()