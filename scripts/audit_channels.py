#!/usr/bin/env python3
"""Audita la salud de los canales de DaddyLive verificando resolución HLS en vivo.

Uso:
  python3 scripts/audit_channels.py [--limit 30] [--workers 8] [--manifest manifest.json]
"""
import argparse
import base64
import json
import re
import sys
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor

UA = ("Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")


def check_channel(channel):
    cid = channel["id"]
    name = channel["nombre"]
    t0 = time.time()
    try:
        # 1. Embed
        req = urllib.request.Request(
            f"https://daddylive.app/player/embed.php?id={cid}",
            headers={"User-Agent": UA, "Referer": "https://daddylive.app/"}
        )
        with urllib.request.urlopen(req, timeout=3.5) as r:
            html = r.read().decode("utf-8", "replace").replace("\/", "/")

        m = re.search(r"https?://(?:www\.)?freetvspor\.cfd/live/stream-[^"'\s]+", html)
        if not m:
            return {"id": cid, "name": name, "online": False, "status": "no_slot", "time": round(time.time() - t0, 2)}
        slot_url = m.group(0)

        # 2. Slot iframe
        req = urllib.request.Request(
            slot_url,
            headers={"User-Agent": UA, "Referer": f"https://daddylive.app/player/embed.php?id={cid}"}
        )
        with urllib.request.urlopen(req, timeout=3.5) as r:
            slot_html = r.read().decode("utf-8", "replace")

        im = re.search(r"<iframe[^>]+src=["']([^"']+)["']", slot_html)
        if not im:
            return {"id": cid, "name": name, "online": False, "status": "no_iframe", "time": round(time.time() - t0, 2)}
        tiestep_url = im.group(1)
        if tiestep_url.startswith("//"):
            tiestep_url = "https:" + tiestep_url

        # 3. Tiestep config
        req = urllib.request.Request(tiestep_url, headers={"User-Agent": UA, "Referer": slot_url})
        with urllib.request.urlopen(req, timeout=3.5) as r:
            tiestep_html = r.read().decode("utf-8", "replace")

        em = re.search(r"window\._econfig\s*=\s*['"]([^'"]+)['"\]", tiestep_html)
        if not em:
            return {"id": cid, "name": name, "online": False, "status": "no_econfig", "time": round(time.time() - t0, 2)}

        raw = base64.b64decode(em.group(1))
        part_len = (len(raw) + 3) // 4
        parts = [raw[i * part_len : (i + 1) * part_len] for i in range(4)]
        rearranged = []
        for idx in [2, 0, 3, 1]:
            p = parts[idx]
            if len(p) >= 4:
                p = p[:3] + p[4:]
            rearranged.append(base64.b64decode(p))
        dec_json = json.loads(base64.b64decode(b"".join(rearranged)).decode("utf-8", "replace"))
        m3u8 = dec_json.get("stream_url_nop2p") or dec_json.get("stream_url")
        if not m3u8:
            return {"id": cid, "name": name, "online": False, "status": "no_m3u8", "time": round(time.time() - t0, 2)}

        # 4. Ping m3u8 header
        req = urllib.request.Request(m3u8, headers={"User-Agent": UA, "Referer": "https://tiestep.top/"})
        with urllib.request.urlopen(req, timeout=3.5) as r:
            first_bytes = r.read(15).decode("utf-8", "replace")
            if "#EXTM3U" in first_bytes:
                return {"id": cid, "name": name, "online": True, "status": "OK", "time": round(time.time() - t0, 2)}
            return {"id": cid, "name": name, "online": False, "status": "invalid_hls", "time": round(time.time() - t0, 2)}
    except Exception as e:
        return {"id": cid, "name": name, "online": False, "status": str(e), "time": round(time.time() - t0, 2)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--manifest", default="manifest.json")
    ap.add_argument("--limit", type=int, default=30)
    ap.add_argument("--workers", type=int, default=6)
    args = ap.parse_args()

    with open(args.manifest, "r", encoding="utf-8") as f:
        data = json.load(f)
    canales = data.get("canales", [])
    if args.limit > 0:
        canales = canales[:args.limit]

    print(f"[audit] Verificando {len(canales)} canales con {args.workers} workers...", file=sys.stderr)
    online_count = 0
    results = []
    with ThreadPoolExecutor(max_workers=args.workers) as ex:
        for res in ex.map(check_channel, canales):
            results.append(res)
            mark = "✓" if res["online"] else "✗"
            if res["online"]:
                online_count += 1
            print(f"[{mark}] #{res['id']} {res['name'][:30]:30} | {res['status']} ({res['time']}s)")

    pct = (online_count / len(canales)) * 100 if canales else 0
    print(f"
[audit] TOTAL: {online_count}/{len(canales)} online ({pct:.1f}%)", file=sys.stderr)


if __name__ == "__main__":
    main()
