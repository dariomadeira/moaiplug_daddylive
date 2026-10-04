import urllib.request, ssl

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
url = "https://edge.cowedd4855ws.sbs/premium521/index.m3u8"

for ref in [
    "https://daddyliveplayer.st/",
    "https://daddyliveplayer.st/premiumtv/worldsportz4u.php?id=521",
    "https://daddylive.li/",
    ""
]:
    try:
        headers = {"User-Agent": UA}
        if ref:
            headers["Referer"] = ref
        req = urllib.request.Request(url, headers=headers)
        with urllib.request.urlopen(req, timeout=5, context=ctx) as r:
            body = r.read().decode("utf-8", "replace")
            print("Ref: %r -> Status %s, body len %s, is_m3u8=%s, preview=%r" % (
                ref, r.status, len(body), body.startswith("#EXTM3U"), body[:60]
            ))
    except Exception as e:
        print("Ref: %r -> Error %s" % (ref, e))
