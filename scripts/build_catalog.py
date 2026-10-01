#!/usr/bin/env python3
"""Genera el array `canales` del manifest.json del plugin moai_daddylive.

Baja `GET https://{domain}/api/channels` (daddylive.li|.app|.mov) y escribe
`build/canales.json` (solo el array) con PackagedPluginChannel:
    { "id": "<id numérico>", "nombre": ..., "logo": ..., "categoria": ..., "pais": ... }

Filtros:
  - solo ids numéricos
  - sin canales 18+ (el endpoint no los incluye)
Orden estable por id numérico para manifests reproducibles.
Uso: python3 scripts/build_catalog.py [--out build/canales.json] [--domain daddylive.li]
"""
import argparse
import json
import os
import re
import sys
import urllib.request

DOMAINS = ["daddylive.li", "daddylive.app", "daddylive.mov"]
UA = ("Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")

COUNTRY_MAP = {
    # Nombres completos
    "espana": "España", "españa": "España", "spain": "España",
    "italia": "Italia", "italy": "Italia",
    "francia": "Francia", "france": "Francia",
    "alemania": "Alemania", "germany": "Alemania", "deutschland": "Alemania",
    "reino unido": "Reino Unido", "united kingdom": "Reino Unido",
    "estados unidos": "Estados Unidos", "united states": "Estados Unidos",
    "brasil": "Brasil", "brazil": "Brasil",
    "portugal": "Portugal",
    "argentina": "Argentina",
    "mexico": "México", "méxico": "México",
    "australia": "Australia",
    "canada": "Canadá", "canadá": "Canadá",
    "turquia": "Turquía", "turquía": "Turquía", "turkey": "Turquía",
    "india": "India",
    "pakistan": "Pakistán", "pakistán": "Pakistán",
    "polonia": "Polonia", "poland": "Polonia",
    "grecia": "Grecia", "greece": "Grecia",
    "rusia": "Rusia", "russia": "Rusia",
    "ucrania": "Ucrania", "ukraine": "Ucrania",
    "croacia": "Croacia", "croatia": "Croacia",
    "serbia": "Serbia",
    "austria": "Austria",
    "bulgaria": "Bulgaria",
    "israel": "Israel",
    "dinamarca": "Dinamarca", "denmark": "Dinamarca",
    "suecia": "Suecia", "sweden": "Suecia",
    "rumania": "Rumania", "romania": "Rumania",
    "chipre": "Chipre", "cyprus": "Chipre",
    "noruega": "Noruega", "norway": "Noruega",
    "eslovenia": "Eslovenia", "slovenia": "Eslovenia",
    "eslovaquia": "Eslovaquia", "slovakia": "Eslovaquia",
    "albania": "Albania",
    "paises bajos": "Países Bajos", "netherlands": "Países Bajos", "holanda": "Países Bajos",
    "belgica": "Bélgica", "belgium": "Bélgica",
    "finlandia": "Finlandia", "finland": "Finlandia",
    "suiza": "Suiza", "switzerland": "Suiza",
    "hungria": "Hungría", "hungary": "Hungría",
    "republica checa": "República Checa", "czech": "República Checa",
    "sudafrica": "Sudáfrica", "south africa": "Sudáfrica",
    "chile": "Chile",
    "colombia": "Colombia",
    "peru": "Perú", "perú": "Perú",
    "uruguay": "Uruguay",
    "irlanda": "Irlanda", "ireland": "Irlanda",
    "arabia saudita": "Arabia Saudita", "saudi": "Arabia Saudita",
    "qatar": "Catar", "catar": "Catar",
}

CODE_MAP = {
    "USA": "Estados Unidos",
    "US": "Estados Unidos",
    "UK": "Reino Unido",
    "DE": "Alemania",
    "CZ": "República Checa",
    "SK": "Eslovaquia",
    "NL": "Países Bajos",
    "MX": "México",
    "CA": "Canadá",
    "NZ": "Nueva Zelanda",
    "IT": "Italia",
    "UAE": "Emiratos Árabes",
    "AU": "Australia",
    "ES": "España",
    "ESP": "España",
    "AR": "Argentina",
    "ZA": "Sudáfrica",
    "FR": "Francia",
    "PL": "Polonia",
    "PT": "Portugal",
    "BR": "Brasil",
    "IN": "India",
    "GR": "Grecia",
    "RS": "Serbia",
    "HR": "Croacia",
    "SE": "Suecia",
    "NO": "Noruega",
    "DK": "Dinamarca",
    "AT": "Austria",
    "CH": "Suiza",
    "BE": "Bélgica",
    "RO": "Rumania",
    "BG": "Bulgaria",
    "IL": "Israel",
    "CY": "Chipre",
    "CL": "Chile",
    "CO": "Colombia",
}

CATEGORY_SPORTS = [
    "soccer", "football", "futbol", "fútbol", "futebol", "tennis", "tenis",
    "formula", "f1", "motogp", "moto gp", "nba", "nfl", "mlb", "nhl", "ufc",
    "boxing", "boxeo", "cricket", "cric", "rugby", "golf", "hockey", "basket",
    "basketball", "baloncesto", "cycling", "darts", "snooker", "volleyball",
    "voleibol", "handball", "handbol", "mma", "wwe", "aew", "fight", "lucha",
    "deporte", "sport", "spor", "auto", "racing", "espn", "dazn", "bein",
    "tsn", "sportsnet", "supersport", "laliga", "premier", "liga de campeones",
    "bundesliga", "serie a", "ligue 1", "eredivisie", "copa", "champions",
    "eurosport", "viaplay", "arena sport", "eleven", "sport tv", "polsat sport",
    "sky sport", "tnt sport", "star sport", "tyc", "claro sport", "fox sport",
    "fan duel", "fanduel", "gol play", "super sport", "athletic", "tabii spor",
    "joj sport"
]

CATEGORY_NEWS = [
    "news", "noticias", "24h", "cnn", "bbc news", "sky news", "al jazeera",
    "euronews", "cbs news", "abc news", "fox news", "espnews", "msnbc",
    "bloomberg", "weather"
]

CATEGORY_KIDS = [
    "kids", "disney", "cartoon", "nickelodeon", "infantil", "baby", "boomerang",
    "cbeebies", "cartoonito", "clan", "toonz", "nick"
]

CATEGORY_MOVIE = [
    "movie", "cinema", "cine", "series", "hbo", "starz", "showtime", "axn",
    "paramount", "amc", "cinemax", "film", "tnt", "warner", "fx", "fox life",
    "hallmark"
]

CATEGORY_ADULT = [
    "pornhub", "xvideos", "xnxx", "xhamster", "youporn", "spankbang", "redtube",
    "brazzers", "realitykings", "bangbros", "naughtyamerica", "beeg", "eporner",
    "tube8", "pornhd", "tnaflix", "drtuber", "porntrex", "sexvid", "motherless",
    "playboy", "hustler", "penthouse", "venus", "sextreme", "xxx", "erotic",
    "dorcel", "vivid", "babes", "x-rated", "18+", "+18", "adultos"
]

LOGO_RULES = [
    # === USA ===
    (r"\bespn", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/united-states/espn-us.png"),
    (r"\bfox sports|\bfox hd|\bfoxny|\bfox\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/united-states/fox-sports-1-us.png"),
    (r"\bnba\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/united-states/nba-tv-us.png"),
    (r"\bnfl\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/united-states/nfl-network-us.png"),
    (r"\bmlb\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/united-states/mlb-network-us.png"),
    (r"\bnhl\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/united-states/nhl-network-us.png"),
    (r"\btnt\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/united-states/tnt-us.png"),
    (r"\bhbo\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/united-states/hbo-us.png"),
    (r"\bdiscovery\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/united-states/discovery-channel-us.png"),
    (r"\bhistory\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/united-states/history-channel-us.png"),
    (r"\bcartoon network\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/united-states/cartoon-network-us.png"),
    (r"\bnickelodeon\b|\bnick\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/united-states/nickelodeon-us.png"),
    (r"\bdisney\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/united-states/disney-channel-us.png"),
    (r"\bstarz\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/united-states/starz-us.png"),
    (r"\bshowtime\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/united-states/showtime-us.png"),
    (r"\bnbc\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/united-states/nbc-us.png"),
    (r"\bparamount\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/united-states/paramount-network-us.png"),
    (r"\bcnn\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/united-states/cnn-us.png"),
    (r"\babc\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/united-states/abc-us.png"),
    (r"\bcbs\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/united-states/cbs-us.png"),
    (r"\bfox news\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/united-states/fox-news-us.png"),
    (r"\bmsnbc\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/united-states/msnbc-us.png"),
    (r"\bbloomberg\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/united-states/bloomberg-us.png"),
    (r"\bweather\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/united-states/weather-channel-us.png"),
    
    # === UK ===
    (r"\bsky sports|\bsky sport", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/united-kingdom/sky-sports-main-event-uk.png"),
    (r"\bbt sport|\bbtt\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/united-kingdom/tnt-sports-1-uk.png"),
    (r"\bbbc\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/united-kingdom/bbc-one-uk.png"),
    (r"\bitv\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/united-kingdom/itv-uk.png"),
    (r"\bchannel 4\b|\bch4\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/united-kingdom/channel-4-uk.png"),
    (r"\bchannel 5\b|\bch5\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/united-kingdom/channel-5-uk.png"),
    (r"\bsky news\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/united-kingdom/sky-news-uk.png"),
    
    # === Sports International ===
    (r"\bf1\b|formula 1", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/united-kingdom/sky-sports-f1-uk.png"),
    (r"\bmotogp\b|\bmoto gp", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/spain/movistar-plus-es.png"),
    (r"\bchampions\b|\bliga de campeones", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/spain/movistar-plus-es.png"),
    (r"\blaliga\b|\bliga\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/spain/movistar-plus-es.png"),
    (r"\bpremier\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/united-kingdom/sky-sports-main-event-uk.png"),
    (r"\bbundesliga\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/germany/sky-sport-1-de.png"),
    (r"\bserie a\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/italy/sky-sport-251-it.png"),
    (r"\bligue 1\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/eurosport-1-fr.png"),
    (r"\beredivisie\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/netherlands/ziggo-sport-nl.png"),
    
    # === Spain ===
    (r"\bdazn\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/spain/dazn-1-es.png"),
    (r"\bmovistar|\bm\+\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/spain/movistar-plus-es.png"),
    (r"\beurosport\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/eurosport-1-fr.png"),
    (r"\bgol play\b|\bgol television\b|\bgol tv\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/spain/gol-play-es.png"),
    (r"\bcanal\+\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/canal-plus-sport-fr.png"),
    
    # === France ===
    (r"\bbein\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/united-states/bein-sports-us.png"),
    (r"\brmc\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/rmc-sport-1-fr.png"),
    (r"\bcanal\+", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/canal-plus-sport-fr.png"),
    
    # === Germany ===
    (r"\bsky sport\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/germany/sky-sport-1-de.png"),
    (r"\bmagenta\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/germany/magenta-sport-de.png"),
    (r"\bdazn\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/germany/dazn-1-de.png"),
    
    # === Italy ===
    (r"\bsky sport\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/italy/sky-sport-251-it.png"),
    (r"\bdazn\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/italy/dazn-1-it.png"),
    
    # === Portugal ===
    (r"\bsport tv\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/portugal/sport-tv-1-pt.png"),
    (r"\beleven\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/portugal/eleven-sports-1-pt.png"),
    
    # === Poland ===
    (r"\bpolsat\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/poland/polsat-sport-1-pl.png"),
    (r"\btvp\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/poland/tvp-sport-pl.png"),
    
    # === Balkans ===
    (r"\barena sport\b|\barena\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/serbia/arena-sport-1-rs.png"),
    (r"\bsport klub\b|\bsportklub\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/croatia/sport-klub-1-hr.png"),
    (r"\brts\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/serbia/rts-1-rs.png"),
    (r"\bhrt\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/croatia/hrt-1-hr.png"),
    (r"\bslovenia\b|\bslov\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/slovenia/rts-si.png"),
    
    # === Turkey ===
    (r"\btrt\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/trt-1-tr.png"),
    (r"\btv8\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/tv8-tr.png"),
    
    # === Middle East ===
    (r"\bbein\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/united-states/bein-sports-us.png"),
    (r"\bmbc\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/saudi-arabia/mbc-1-sa.png"),
    (r"\bal jazeera\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/qatar/al-jazeera-qa.png"),
    
    # === Latin America ===
    (r"\btyc sports\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/argentina/tyc-sports-ar.png"),
    (r"\btelefe\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/argentina/telefe-ar.png"),
    (r"\bespn brasil|\bespn br\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/brazil/espn-br.png"),
    (r"\bsportv\b|\bsport tv\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/brazil/sportv-br.png"),
    (r"\bcombate\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/brazil/combate-br.png"),
    (r"\bpremiere\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/brazil/premiere-br.png"),
    (r"\bfox sports\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/mexico/fox-sports-mx.png"),
    (r"\bazteca\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/mexico/azteca-7-mx.png"),
    (r"\btelevisa\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/mexico/televisa-mx.png"),
    (r"\bespn\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/mexico/espn-mx.png"),
    (r"\bcaracol\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/colombia/caracol-tv-co.png"),
    (r"\brcn\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/colombia/rcn-tv-co.png"),
    (r"\bcaracol\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/colombia/caracol-tv-co.png"),
    
    # === Canada ===
    (r"\btsn\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/canada/tsn-1-ca.png"),
    (r"\bsportsnet\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/canada/sportsnet-ontario-ca.png"),
    
    # === South Africa ===
    (r"\bsupersport\b|\bsuper sport\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/south-africa/supersport-grandstand-za.png"),
    
    # === Australia ===
    (r"\bfox sports\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/australia/fox-sports-1-au.png"),
    (r"\bsky sport\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/australia/sky-sport-1-au.png"),
    
    # === India ===
    (r"\bstar sports\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/india/star-sports-1-in.png"),
    (r"\bsony\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/india/sony-sports-in.png"),
    (r"\bten sports\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/india/ten-sports-in.png"),
    
    # === Netherlands ===
    (r"\bziggo\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/netherlands/ziggo-sport-nl.png"),
    
    # === Belgium ===
    (r"\beleven\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/belgium/eleven-sports-1-be.png"),
    
    # === Generic Sports ===
    (r"\bsport\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/generic/sport-generic.png"),
    (r"\btv\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/generic/tv-generic.png"),
    (r"\bcanal\b", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/generic/canal-generic.png"),
]


def fetch_channels(domain):
    url = f"https://{domain}/api/channels"
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=15) as r:
        if r.status != 200:
            raise RuntimeError(f"HTTP {r.status} en {url}")
        return json.loads(r.read().decode("utf-8", "replace"))


def country_of(name):
    low = name.lower()
    for kb, country in COUNTRY_MAP.items():
        if re.search(r"\b" + re.escape(kb) + r"\b", low):
            return country
    tokens = re.findall(r"\b[A-Za-z0-9]+\b", name)
    for tok in tokens:
        if tok in CODE_MAP:
            return CODE_MAP[tok]
    return "General"


def category_of(name, cid=None):
    low = name.lower()
    if cid and str(cid).isdigit() and 501 <= int(cid) <= 520:
        return "Adultos"
    if "adult swim" in low:
        return "General"
    if any(k in low for k in CATEGORY_ADULT):
        return "Adultos"
    if any(k in low for k in CATEGORY_SPORTS):
        return "Deportes"
    if any(k in low for k in CATEGORY_NEWS):
        return "Noticias"
    if any(k in low for k in CATEGORY_KIDS):
        return "Infantil"
    if any(k in low for k in CATEGORY_MOVIE):
        return "Cine"
    return "General"


def logo_of(name):
    low = name.lower()
    for pattern, url in LOGO_RULES:
        if re.search(pattern, low):
            return url
    return ""


def build_channel(raw):
    name = (raw.get("channel_name") or "").strip()
    url = raw.get("url") or ""
    m = re.search(r"id=([0-9]+)", url)
    if not m:
        return None
    cid = m.group(1)
    if not re.fullmatch(r"[0-9]+", cid):
        return None
    cat = category_of(name, cid)
    country = "Adultos" if cat == "Adultos" else country_of(name)
    return {
        "id": cid,
        "nombre": name,
        "logo": logo_of(name),
        "categoria": cat,
        "pais": country,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="build/canales.json")
    ap.add_argument("--domain", choices=DOMAINS + ["auto"], default="auto")
    ap.add_argument("--online-only", default="", help="Archivo JSON con canales online (de verify_with_java.py)")
    args = ap.parse_args()

    # Si se especifica --online-only, usar solo esos canales
    if args.online_only:
        with open(args.online_only, encoding="utf-8") as f:
            online_channels = json.load(f)
        online_ids = {c["id"] for c in online_channels}
        print(f"[build_catalog] Filtrando por {len(online_ids)} canales online verificados", file=sys.stderr)
    else:
        online_ids = None

    errors = []
    channels = {}
    for domain in ([args.domain] if args.domain != "auto" else DOMAINS):
        try:
            raw = fetch_channels(domain)
            for item in raw:
                c = build_channel(item) if isinstance(item, dict) else None
                if c:
                    # Filtrar por canales online si se especificó
                    if online_ids is not None and c["id"] not in online_ids:
                        continue
                    channels[c["id"]] = c
            print(f"[build_catalog] {domain}: {len(channels)} canales numéricos", file=sys.stderr)
            break
        except Exception as e:  # noqa: BLE001
            errors.append(f"{domain}: {e}")
    if not channels:
        sys.exit("ERROR: no se pudo bajar el catálogo: " + "; ".join(errors))

    ordered = [channels[k] for k in sorted(channels, key=lambda x: int(x))]
    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(ordered, f, ensure_ascii=False, indent=2)
    print(f"[build_catalog] ESCRIBIENDO {args.out} con {len(ordered)} canales", file=sys.stderr)


if __name__ == "__main__":
    main()
