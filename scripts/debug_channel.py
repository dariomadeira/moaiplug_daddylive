# -*- coding: utf-8 -*-
import urllib.request
import re
import base64
import ssl
import json

# Ignorar errores de certificado SSL
ssl_context = ssl.create_default_context()
ssl_context.check_hostname = False
ssl_context.verify_mode = ssl.CERT_NONE

UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

def fetch(url, headers=None, timeout=10):
    req = urllib.request.Request(url, headers=headers or {})
    with urllib.request.urlopen(req, timeout=timeout, context=ssl_context) as r:
        return r.status, r.read().decode("utf-8", "replace")

def decrypt_econfig(encoded):
    """Port del decrypt de DaddyliveResolver.java (Scheme B)."""
    try:
        dec1 = base64.b64decode(encoded)
        total = len(dec1)
        part_length = (total + 3) // 4
        order = [2, 0, 3, 1]
        parts = []
        for i in range(4):
            from_idx = i * part_length
            to_idx = min(from_idx + part_length, total)
            if from_idx >= total:
                parts.append("")
                continue
            parts.append(dec1[from_idx:to_idx].decode("iso-8859-1"))
        rearranged = []
        for i in range(4):
            s = parts[i]
            if len(s) >= 4:
                s = s[:3] + s[4:]
            try:
                rearranged.append(base64.b64decode(s.encode("iso-8859-1")))
            except Exception:
                rearranged.append(b"")
        combined = b"".join(rearranged)
        return base64.b64decode(combined).decode("utf-8", errors="replace")
    except Exception as e:
        return f"ERROR: {e}"

def extract_m3u8(html):
    """Extraer m3u8 del HTML usando múltiples esquemas."""
    # Scheme B: _econfig
    m = re.search(r'window\._econfig\s*=\s*["\']([^"\']+)["\']', html)
    if m:
        print(f"  _econfig encontrado, intentando decrypt...")
        try:
            json_str = decrypt_econfig(m.group(1))
            print(f"  JSON (primeros 200 chars): {json_str[:200]}")
            for key in ["stream_url_nop2p", "stream_url"]:
                m2 = re.search(rf'"{key}"\s*:\s*"([^"]+)"', json_str)
                if m2:
                    url = m2.group(1).replace("\\/", "/").replace("\\u0026", "&").replace('\\"', '"').replace("\\\\", "\\")
                    print(f"  URL encontrada ({key}): {url}")
                    return url
        except Exception as e:
            print(f"  Error en decrypt: {e}")
    
    # Scheme A: source: window.atob('...')
    m = re.search(r"source\s*:\s*window\.atob\(\s*['\"]([^'\"]+)['\"]\s*\)", html)
    if m:
        print(f"  Scheme A: atob encontrado")
        try:
            decoded = base64.b64decode(m.group(1)).decode("utf-8")
            if ".m3u8" in decoded:
                print(f"  URL encontrada: {decoded.strip()}")
                return decoded.strip()
        except Exception as e:
            print(f"  Error en atob: {e}")
    
    # Scheme C: m3u8 directo
    m = re.search(r'["\']((?:https?:)?//[^"\']+\.m3u8[^"\']*)["\']', html)
    if m:
        url = m.group(1)
        if url.startswith("//"):
            url = "https:" + url
        print(f"  Scheme C: m3u8 directo: {url}")
        return url
    
    # Scheme D: JSON con source/url
    for key in ["source", "url"]:
        m = re.search(rf'"{key}"\s*:\s*"([^"]+)"', html)
        if m:
            url = m.group(1).replace("\\/", "/")
            if ".m3u8" in url:
                if url.startswith("//"):
                    url = "https:" + url
                print(f"  Scheme D: {key}: {url}")
                return url
    
    # Scheme E: data-src o src con m3u8
    for attr in ["data-src", "src"]:
        m = re.search(rf'{attr}\s*=\s*["\']([^"\']+\.m3u8[^"\']*)["\']', html)
        if m:
            url = m.group(1)
            if url.startswith("//"):
                url = "https:" + url
            print(f"  Scheme E: {attr}: {url}")
            return url
    
    return None

channel_id = "521"
domain = "daddylive.li"
embed_url = f"https://{domain}/player/embed.php?id={channel_id}"

print(f"Probando canal {channel_id} en {domain}...")
print(f"URL: {embed_url}")

try:
    code, body = fetch(embed_url, {"User-Agent": UA, "Referer": f"https://{domain}/"})
    print(f"Status: {code}")
    print(f"Body length: {len(body)}")
    
    # Buscar PLAYERS
    pm = re.search(r"PLAYERS\s*=\s*(\[[\s\S]*?\])", body)
    if pm:
        print(f"PLAYERS encontrado!")
        slots = re.findall(r'\{\s*"src"\s*:\s*"([^"]+)"\s*,\s*"hls"\s*:\s*(true|false)\s*\}', pm.group(1))
        # Unescape URLs
        slots = [(src.replace("\\/", "/").replace("\\\\", "\\"), hls) for src, hls in slots]
        print(f"Slots encontrados: {len(slots)}")
        for i, (src, hls) in enumerate(slots[:5]):
            print(f"  [{i}] {src} (hls={hls})")
        
        # Filtrar slots spam
        spam = ["nontongo", "gomstream", "worldsportz4u"]
        non_spam_slots = [(src, hls) for src, hls in slots if not any(s in src.lower() for s in spam)]
        print(f"Slots no spam: {len(non_spam_slots)}")
        
        # Probar cada slot no spam
        for src, hls in non_spam_slots:
            if src.startswith("//"):
                src = "https:" + src
            elif src.startswith("/"):
                src = f"https://{domain}" + src
            print(f"\nProbando slot: {src}")
            
            try:
                ref = re.match(r"https?://[^/]+", src).group(0) + "/"
                code2, body2 = fetch(src, {"User-Agent": UA, "Referer": ref})
                print(f"Status: {code2}")
                print(f"Body length: {len(body2)}")
                
                # Buscar iframe
                iframe = re.search(r'<iframe[^>]+src=["\']([^"\']+)["\']', body2)
                if iframe:
                    sub = iframe.group(1)
                    if sub.startswith("//"):
                        sub = "https:" + sub
                    elif sub.startswith("/"):
                        sub = f"https://{domain}" + sub
                    print(f"Iframe encontrado: {sub}")
                    
                    code3, body3 = fetch(sub, {"User-Agent": UA, "Referer": src})
                    print(f"Status iframe: {code3}")
                    print(f"Body length: {len(body3)}")
                    
                    # Extraer m3u8
                    m3u8_url = extract_m3u8(body3)
                    if m3u8_url:
                        print(f"\nValidando m3u8: {m3u8_url}")
                        try:
                            code4, body4 = fetch(m3u8_url, {"User-Agent": UA, "Referer": sub})
                            print(f"Status m3u8: {code4}")
                            if body4.startswith("#EXTM3U"):
                                print("✓ CANAL ONLINE!")
                            else:
                                print(f"✗ m3u8 no válido: {body4[:100]}")
                        except Exception as e:
                            print(f"Error validando m3u8: {e}")
                    else:
                        print("No se encontró m3u8 en el iframe")
                        # Buscar m3u8 directo en el body del iframe
                        m3u8 = re.search(r'["\']((?:https?:)?//[^"\']+\.m3u8[^"\']*)["\']', body3)
                        if m3u8:
                            print(f"m3u8 directo en iframe: {m3u8.group(1)}")
                        else:
                            print("m3u8 directo no encontrado")
                            print(f"Body preview: {body3[:500]}")
                else:
                    print("Iframe no encontrado")
                    # Buscar m3u8 directo
                    m3u8 = re.search(r'["\']((?:https?:)?//[^"\']+\.m3u8[^"\']*)["\']', body2)
                    if m3u8:
                        print(f"m3u8 directo: {m3u8.group(1)}")
                    else:
                        print("m3u8 directo no encontrado")
                        print(f"Body preview: {body2[:500]}")
            except Exception as e:
                print(f"Error: {e}")
    else:
        print("PLAYERS no encontrado")
        print(f"Body preview: {body[:500]}")
except Exception as e:
    print(f"Error: {e}")
    import traceback
    traceback.print_exc()
