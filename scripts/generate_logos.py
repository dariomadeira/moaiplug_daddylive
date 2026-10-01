#!/usr/bin/env python3
"""Genera logos para todos los canales usando múltiples estrategias.

Estrategia:
1. Reglas existentes (LOGO_RULES)
2. Fallback por país (logo genérico por país)
3. Fallback por categoría (logo genérico por categoría)
4. Logo con iniciales (generado automáticamente)

Uso:
    python3 scripts/generate_logos.py --input build/canales_online_java.json --output build/canales_con_logos.json
"""
import argparse
import json
import os
import re
import sys
from typing import Optional

# Logos genéricos por país
COUNTRY_LOGOS = {
    "Argentina": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/argentina/flag-ar.png",
    "Australia": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/australia/flag-au.png",
    "Austria": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/austria/flag-at.png",
    "Bélgica": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/belgium/flag-be.png",
    "Brasil": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/brazil/flag-br.png",
    "Canadá": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/canada/flag-ca.png",
    "Chile": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/chile/flag-cl.png",
    "Colombia": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/colombia/flag-co.png",
    "Croacia": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/croatia/flag-hr.png",
    "Chipre": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/cyprus/flag-cy.png",
    "Dinamarca": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/denmark/flag-dk.png",
    "Eslovaquia": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/slovakia/flag-sk.png",
    "Eslovenia": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/slovenia/flag-si.png",
    "España": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/spain/flag-es.png",
    "Estados Unidos": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/united-states/flag-us.png",
    "Francia": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/flag-fr.png",
    "Grecia": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/greece/flag-gr.png",
    "Hungría": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/hungary/flag-hu.png",
    "India": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/india/flag-in.png",
    "Irlanda": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/ireland/flag-ie.png",
    "Israel": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/israel/flag-il.png",
    "Italia": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/italy/flag-it.png",
    "México": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/mexico/flag-mx.png",
    "Noruega": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/norway/flag-no.png",
    "Países Bajos": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/netherlands/flag-nl.png",
    "Perú": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/peru/flag-pe.png",
    "Polonia": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/poland/flag-pl.png",
    "Portugal": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/portugal/flag-pt.png",
    "Reino Unido": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/united-kingdom/flag-gb.png",
    "Rumania": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/romania/flag-ro.png",
    "Rusia": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/russia/flag-ru.png",
    "Serbia": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/serbia/flag-rs.png",
    "Suecia": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/sweden/flag-se.png",
    "Turquía": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/flag-tr.png",
    "Ucrania": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/ukraine/flag-ua.png",
    "Uruguay": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/uruguay/flag-uy.png",
    "Albania": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/albania/flag-al.png",
    "Azerbaiyán": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/azerbaijan/flag-az.png",
    "Emiratos Árabes": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/united-arab-emirates/flag-ae.png",
    "Catar": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/qatar/flag-qa.png",
    "Arabia Saudita": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/saudi-arabia/flag-sa.png",
    "Sudáfrica": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/south-africa/flag-za.png",
    "Nueva Zelanda": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/new-zealand/flag-nz.png",
    "República Checa": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/czech-republic/flag-cz.png",
    "Pakistán": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/pakistan/flag-pk.png",
    "Bangladesh": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/bangladesh/flag-bd.png",
    "Vietnam": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/vietnam/flag-vn.png",
    "Indonesia": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/indonesia/flag-id.png",
    "Malta": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/malta/flag-mt.png",
    "Islandia": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/iceland/flag-is.png",
    "Luxemburgo": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/luxembourg/flag-lu.png",
    "Marruecos": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/morocco/flag-ma.png",
    "Túnez": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/tunisia/flag-tn.png",
    "Argelia": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/algeria/flag-dz.png",
    "Nigeria": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/nigeria/flag-ng.png",
    "Kenia": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/kenya/flag-ke.png",
    "Egipto": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/egypt/flag-eg.png",
    "Ucrania": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/ukraine/flag-ua.png",
    "Bulgaria": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/bulgaria/flag-bg.png",
    "Costa Rica": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/costa-rica/flag-cr.png",
    "Ecuador": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/ecuador/flag-ec.png",
    "Panamá": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/panama/flag-pa.png",
    "Paraguay": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/paraguay/flag-py.png",
    "República Dominicana": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/dominican-republic/flag-do.png",
    "Trinidad y Tobago": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/trinidad-and-tobago/flag-tt.png",
    "Jamaica": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/jamaica/flag-jm.png",
    "Puerto Rico": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/puerto-rico/flag-pr.png",
    "Honduras": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/honduras/flag-hn.png",
    "Guatemala": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/guatemala/flag-gt.png",
    "El Salvador": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/el-salvador/flag-sv.png",
    "Nicaragua": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/nicaragua/flag-ni.png",
    "Bolivia": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/bolivia/flag-bo.png",
    "Cuba": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/cuba/flag-cu.png",
    "Haití": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/haiti/flag-ht.png",
    "Moldavia": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/moldova/flag-md.png",
    "Bielorrusia": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/belarus/flag-by.png",
    "Lituania": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/lithuania/flag-lt.png",
    "Letonia": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/latvia/flag-lv.png",
    "Estonia": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/estonia/flag-ee.png",
    "Finlandia": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/finland/flag-fi.png",
    "Suiza": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/switzerland/flag-ch.png",
    "Portugal": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/portugal/flag-pt.png",
    "Austria": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/austria/flag-at.png",
    "Alemania": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/germany/flag-de.png",
    "Países Bajos": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/netherlands/flag-nl.png",
    "Bélgica": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/belgium/flag-be.png",
    "Francia": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/flag-fr.png",
    "Reino Unido": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/united-kingdom/flag-gb.png",
    "Irlanda": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/ireland/flag-ie.png",
    "Italia": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/italy/flag-it.png",
    "España": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/spain/flag-es.png",
    "Grecia": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/greece/flag-gr.png",
    "Turquía": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/flag-tr.png",
    "Rusia": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/russia/flag-ru.png",
    "Polonia": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/poland/flag-pl.png",
    "Ucrania": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/ukraine/flag-ua.png",
    "Rumania": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/romania/flag-ro.png",
    "Hungría": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/hungary/flag-hu.png",
    "Bulgaria": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/bulgaria/flag-bg.png",
    "Serbia": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/serbia/flag-rs.png",
    "Croacia": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/croatia/flag-hr.png",
    "Eslovaquia": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/slovakia/flag-sk.png",
    "Eslovenia": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/slovenia/flag-si.png",
    "Lituania": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/lithuania/flag-lt.png",
    "Letonia": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/latvia/flag-lv.png",
    "Estonia": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/estonia/flag-ee.png",
    "Finlandia": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/finland/flag-fi.png",
    "Noruega": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/norway/flag-no.png",
    "Suecia": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/sweden/flag-se.png",
    "Dinamarca": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/denmark/flag-dk.png",
    "Islandia": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/iceland/flag-is.png",
    "Luxemburgo": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/luxembourg/flag-lu.png",
    "Malta": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/malta/flag-mt.png",
    "Chipre": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/cyprus/flag-cy.png",
    "Mónaco": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/monaco/flag-mc.png",
    "Andorra": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/andorra/flag-ad.png",
    "San Marino": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/san-marino/flag-sm.png",
    "Liechtenstein": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/liechtenstein/flag-li.png",
    "Moldavia": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/moldova/flag-md.png",
    "Bielorrusia": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/belarus/flag-by.png",
    "Macedonia del Norte": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/north-macedonia/flag-mk.png",
    "Montenegro": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/montenegro/flag-me.png",
    "Bosnia y Herzegovina": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/bosnia-and-herzegovina/flag-ba.png",
    "Albania": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/albania/flag-al.png",
    "Kosovo": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/kosovo/flag-xk.png",
}

# Logos genéricos por categoría
CATEGORY_LOGOS = {
    "Deportes": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/generic/sport-generic.png",
    "Noticias": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/generic/news-generic.png",
    "Cine": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/generic/movie-generic.png",
    "Infantil": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/generic/kids-generic.png",
    "General": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/generic/tv-generic.png",
    "Adultos": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/generic/adult-generic.png",
}

# Reglas de logos existentes (mismas que en build_catalog.py)
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


def get_logo_by_rules(nombre: str) -> Optional[str]:
    """Buscar logo usando las reglas existentes."""
    low = nombre.lower()
    for pattern, url in LOGO_RULES:
        if re.search(pattern, low):
            return url
    return None


def get_logo_by_country(pais: str) -> Optional[str]:
    """Buscar logo genérico por país."""
    return COUNTRY_LOGOS.get(pais)


def get_logo_by_category(categoria: str) -> Optional[str]:
    """Buscar logo genérico por categoría."""
    return CATEGORY_LOGOS.get(categoria)


def generate_initials_logo(nombre: str) -> str:
    """Generar un logo con las iniciales del canal."""
    # Extraer iniciales (primeras letras de cada palabra)
    words = re.findall(r"[A-Za-z]+", nombre)
    if len(words) >= 2:
        initials = "".join(w[0].upper() for w in words[:3])
    elif len(words) == 1:
        initials = words[0][:3].upper()
    else:
        initials = "TV"
    
    # Generar URL de logo con iniciales (usando un servicio de placeholders)
    # Nota: En producción, podrías generar una imagen real con las iniciales
    return f"https://via.placeholder.com/100x100/333333/FFFFFF?text={initials}"


def assign_logo(channel: dict) -> dict:
    """Asignar un logo a un canal usando múltiples estrategias."""
    nombre = channel.get("nombre", "")
    pais = channel.get("pais", "General")
    categoria = channel.get("categoria", "General")
    
    # Estrategia 1: Reglas existentes
    logo = get_logo_by_rules(nombre)
    if logo:
        channel["logo"] = logo
        channel["logo_source"] = "rules"
        return channel
    
    # Estrategia 2: Fallback por país
    logo = get_logo_by_country(pais)
    if logo:
        channel["logo"] = logo
        channel["logo_source"] = "country"
        return channel
    
    # Estrategia 3: Fallback por categoría
    logo = get_logo_by_category(categoria)
    if logo:
        channel["logo"] = logo
        channel["logo_source"] = "category"
        return channel
    
    # Estrategia 4: Logo con iniciales
    logo = generate_initials_logo(nombre)
    channel["logo"] = logo
    channel["logo_source"] = "initials"
    return channel


def main():
    ap = argparse.ArgumentParser(description="Generar logos para canales")
    ap.add_argument("--input", required=True, help="Archivo JSON con canales")
    ap.add_argument("--output", required=True, help="Archivo JSON de salida")
    args = ap.parse_args()
    
    # Leer canales
    with open(args.input, encoding="utf-8") as f:
        channels = json.load(f)
    
    print(f"[logos] Procesando {len(channels)} canales...", file=sys.stderr)
    
    # Asignar logos
    stats = {"rules": 0, "country": 0, "category": 0, "initials": 0}
    for channel in channels:
        channel = assign_logo(channel)
        source = channel.get("logo_source", "unknown")
        stats[source] = stats.get(source, 0) + 1
        channel.pop("logo_source", None)
    
    # Guardar resultado
    os.makedirs(os.path.dirname(args.output) or ".", exist_ok=True)
    with open(args.output, "w", encoding="utf-8") as f:
        json.dump(channels, f, ensure_ascii=False, indent=2)
    
    # Estadísticas
    print(f"\n[logos] {'='*60}", file=sys.stderr)
    print(f"[logos] RESULTADO:", file=sys.stderr)
    print(f"[logos]   ✓ Con logo (reglas): {stats['rules']}", file=sys.stderr)
    print(f"[logos]   ✓ Con logo (país): {stats['country']}", file=sys.stderr)
    print(f"[logos]   ✓ Con logo (categoría): {stats['category']}", file=sys.stderr)
    print(f"[logos]   ✓ Con logo (iniciales): {stats['initials']}", file=sys.stderr)
    print(f"[logos]   Total: {len(channels)}", file=sys.stderr)
    print(f"[logos]   Canales con logo: {len(channels)} (100%)", file=sys.stderr)


if __name__ == "__main__":
    main()
