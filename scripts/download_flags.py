#!/usr/bin/env python3
"""Descarga banderas en PNG (ancho 80px) desde FlagCDN para moaiplug_daddylive."""
import os
import sys
import urllib.request

COUNTRY_FLAGS = {
    "Albania": "al",
    "Alemania": "de",
    "Argentina": "ar",
    "Australia": "au",
    "Austria": "at",
    "Bélgica": "be",
    "Brasil": "br",
    "Bulgaria": "bg",
    "Canadá": "ca",
    "Catar": "qa",
    "Chile": "cl",
    "Chipre": "cy",
    "Colombia": "co",
    "Croacia": "hr",
    "Dinamarca": "dk",
    "Emiratos Árabes": "ae",
    "Eslovaquia": "sk",
    "Eslovenia": "si",
    "España": "es",
    "Estados Unidos": "us",
    "Finlandia": "fi",
    "Francia": "fr",
    "Grecia": "gr",
    "Hungría": "hu",
    "India": "in",
    "Irlanda": "ie",
    "Israel": "il",
    "Italia": "it",
    "México": "mx",
    "Noruega": "no",
    "Nueva Zelanda": "nz",
    "Países Bajos": "nl",
    "Perú": "pe",
    "Polonia": "pl",
    "Portugal": "pt",
    "Reino Unido": "gb",
    "República Checa": "cz",
    "Rumania": "ro",
    "Rusia": "ru",
    "Serbia": "rs",
    "Suecia": "se",
    "Suiza": "ch",
    "Turquía": "tr",
    "Ucrania": "ua",
    "Uruguay": "uy",
}

def main():
    dest_dir = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "assets", "flags")
    os.makedirs(dest_dir, exist_ok=True)
    
    unique_codes = sorted(set(COUNTRY_FLAGS.values()))
    print(f"Descargando {len(unique_codes)} banderas a {dest_dir}...")
    
    success_count = 0
    for code in unique_codes:
        dest_path = os.path.join(dest_dir, f"{code}.png")
        url = f"https://flagcdn.com/w80/{code}.png"
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
            with urllib.request.urlopen(req) as resp, open(dest_path, "wb") as f:
                f.write(resp.read())
            size = os.path.getsize(dest_path)
            print(f"  [OK] {code}.png ({size} bytes)")
            success_count += 1
        except Exception as e:
            print(f"  [ERR] {code} de {url}: {e}", file=sys.stderr)
            
    print(f"\nFinalizado: {success_count}/{len(unique_codes)} banderas descargadas con exito.")

if __name__ == "__main__":
    main()
