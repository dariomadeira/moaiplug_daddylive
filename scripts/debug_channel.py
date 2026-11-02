#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Diagnóstico punta a punta de un canal 24/7 de thedaddy.pk.

Sigue la misma cadena que el plugin (v0.5.1):

    thedaddy.pk/watch.php?id=N
      -> iframe dembed.st/premiumtv/daddy.php?id=N
      -> const EDGE = "https://edge...."  (dominio que rota)
      -> EDGE/api/playlist-token?slug=premiumN
      -> EDGE/premiumN/index.m3u8?t=TOKEN
      -> primer segmento enmascarado (PNG)

Uso:
    py -3 scripts/debug_channel.py [id] [--full]
"""
import argparse
import re
import ssl
import struct
import sys
import urllib.request

UA = ("Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
SITE = "https://thedaddy.pk"

# Algunos CDN usan certificados que Python no valida en ciertas redes.
SSL_CTX = ssl.create_default_context()
SSL_CTX.check_hostname = False
SSL_CTX.verify_mode = ssl.CERT_NONE

EDGE_RE = re.compile(r"(?:const\s+)?EDGE\s*=\s*[\"'](https?://[^\"']+)")
SRC_RE = re.compile(r"(?:const\s+)?SRC\s*=\s*[\"'](https?://[^/\"']+)")
TOKEN_RE = re.compile(r'"token"\s*:\s*"([^"]+)"')
IFRAME_RE = re.compile(r'<iframe[^>]+src=["\']([^"\']+)', re.I)


def get(url, headers=None, timeout=20):
    req = urllib.request.Request(url, headers=headers or {})
    with urllib.request.urlopen(req, timeout=timeout, context=SSL_CTX) as r:
        return r.status, r.read()


def text(data):
    return data.decode("utf-8", "replace")


def absolute(url, base=SITE + "/"):
    if url.startswith("//"):
        return "https:" + url
    if url.startswith("/"):
        return SITE + url
    if not url.startswith("http"):
        from urllib.parse import urljoin
        return urljoin(base, url)
    return url


def resolve_player(channel_id):
    """Sigue watch.php -> stream-N.php -> iframe dembed hasta hallar EDGE."""
    url = f"{SITE}/watch.php?id={channel_id}"
    page = ""
    for _ in range(3):
        try:
            _, body = get(url, {"User-Agent": UA, "Referer": SITE + "/"})
        except Exception as e:  # noqa: BLE001
            print(f"  aviso: {url} falló ({e})")
            break
        page = text(body)
        edge = discover_edge(page)
        if edge:
            return url, page, edge
        m = IFRAME_RE.search(page)
        if not m:
            break
        url = absolute(m.group(1))
    return url, page, None


def discover_edge(page):
    m = EDGE_RE.search(page)
    if not m:
        m = SRC_RE.search(page)
    return m.group(1).rstrip("/") if m else None


def unwrap_layout(raw):
    """Identifica el layout del segmento sin reproducirlo."""
    if raw[:8] == b"\x89PNG\r\n\x1a\n":
        off, idat = 8, b""
        while off + 12 <= len(raw):
            n = struct.unpack(">I", raw[off:off + 4])[0]
            tag = raw[off + 4:off + 8]
            if tag == b"IDAT":
                idat += raw[off + 8:off + 8 + n]
            off += 12 + n
        if idat[:1] == b"\x47" and len(idat) % 188 == 0:
            return "PNG con IDAT = MPEG-TS crudo (tvnow247)"
        try:
            import zlib
            data = zlib.decompress(idat)
        except Exception:  # noqa: BLE001
            return "PNG (IDAT no inflable)"
        if b"TIKTIKPX" in data:
            return "PNG + TIKTIKPX + gzip(TS) (TikTok CDN)"
        return "PNG (layout de píxeles desconocido)"
    if raw[:4] == b"RIFF" and raw[8:12] == b"WEBP":
        return "WebP (posible TS en EXIF)"
    if raw[:1] == b"\x47" and len(raw) % 188 == 0:
        return "MPEG-TS crudo"
    if b"TIKTIKRAW" in raw[:4096]:
        return "TIKTIKRAW + TS"
    return "desconocido (primeros bytes: %s)" % raw[:8].hex()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("channel_id", nargs="?", default="521")
    ap.add_argument("--full", action="store_true",
                    help="muestra también el inicio del playlist")
    args = ap.parse_args()
    cid = args.channel_id

    print(f"[1] Canal {cid} -> {SITE}/watch.php")
    print("[2] Siguiendo watch.php -> stream -> player y buscando EDGE")
    purl, _, edge = resolve_player(cid)
    print(f"    player: {purl}")
    if not edge:
        sys.exit("    ERROR: no se encontró const EDGE / SRC")
    print(f"    EDGE: {edge}")

    headers = {"User-Agent": UA, "Referer": "https://dembed.st/",
               "Origin": "https://dembed.st"}

    print("[3] Pidiendo token")
    code, tok = get(f"{edge}/api/playlist-token?slug=premium{cid}", headers)
    m = TOKEN_RE.search(text(tok))
    if code != 200 or not m:
        sys.exit(f"    ERROR: HTTP {code} {text(tok)[:200]}")
    print(f"    token: {m.group(1)[:16]}... ({len(m.group(1))} chars)")

    print("[4] Pidiendo playlist")
    pl_url = f"{edge}/premium{cid}/index.m3u8?t={m.group(1)}"
    code, pl = get(pl_url, headers)
    playlist = text(pl)
    print(f"    HTTP {code}, {len(playlist)} bytes")
    if not playlist.startswith("#EXTM3U"):
        sys.exit(f"    ERROR: playlist inválido: {playlist[:200]}")
    if args.full:
        print(playlist[:800])

    seg = next((ln.strip() for ln in playlist.splitlines()
                if ln.strip() and not ln.startswith("#")), None)
    if not seg:
        sys.exit("    ERROR: playlist sin segmentos")
    if not seg.startswith("http"):
        seg = pl_url.rsplit("/", 1)[0] + "/" + seg

    print("[5] Descargando primer segmento")
    code, raw = get(seg, headers)
    print(f"    HTTP {code}, {len(raw)} bytes")
    print(f"    layout: {unwrap_layout(raw)}")
    ok = len(raw) > 188 and unwrap_layout(raw) != "desconocido"
    print("RESULTADO:", "OK" if ok else "REVISAR")


if __name__ == "__main__":
    main()
