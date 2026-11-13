#!/usr/bin/env python3
"""Costruisce il corpus di file .txt a partire da voci di Wikipedia in italiano.

Due passi:

  1. fetch  - per ogni argomento di TOPICS interroga l'API pubblica di it.wikipedia.org
              (ricerca "deepcat:<categoria>": voci della categoria e delle sue sottocategorie,
              ordinate per numero di link entranti) e salva in <raw>/<argomento>.jsonl
              titolo, testo semplice e categorie di ogni voce. Si usano richieste da 50 voci
              perche' l'API accetta poche richieste al minuto da client anonimi: in caso di
              risposta 429 si aspetta il tempo indicato dall'header Retry-After.

  2. build  - trasforma le voci scaricate in file .txt:

                  <corpus>/<argomento>/<Titolo_della_voce>.txt

              L'argomento di una voce NON e' quello della ricerca con cui e' stata trovata
              (deepcat attraversa molte sottocategorie e porta dentro voci poco pertinenti)
              ma e' deciso dalle categorie scritte nella voce stessa: ogni argomento ha
              un'espressione regolare (campo "regex" di TOPICS) e la voce va nel primo
              argomento con cui almeno una sua categoria e' compatibile; se nessuna lo e',
              finisce nella cartella "altro". Viene scritto anche <corpus>/manifest.tsv.

Uso:
    python3 scripts/build_corpus.py fetch corpus_wiki_raw
    python3 scripts/build_corpus.py build corpus_wiki_raw corpus_wiki
"""
import json
import re
import sys
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

API = "https://it.wikipedia.org/w/api.php"
USER_AGENT = "hw3-lucene-corpus-builder/1.0 (progetto didattico; python-urllib)"
PER_TOPIC = 400      # numero massimo di voci scaricate per argomento
BATCH = 50           # voci per richiesta (massimo consentito dall'API)
MIN_CHARS = 500      # le voci piu' corte (abbozzi) vengono scartate

# cartella, categoria radice per la ricerca, regex sulle categorie della voce
TOPICS = [
    ("vulcani", "Vulcani", r"^(Vulcani|Stratovulcani|Caldere|Vulcanologia|Eruzioni)"),
    ("primi_piatti", "Primi piatti", r"^(Primi piatti|Piatti a base di (pasta|riso|gnocchi)|Tipi di pasta|Zuppe|Minestre|Risotti)"),
    ("pittori", "Pittori italiani del XVI secolo", r"^Pittori italiani del XVI secolo"),
    ("linguaggi", "Linguaggi di programmazione", r"^Linguaggi (di programmazione|di scripting|di markup|funzionali|orientati)"),
    ("cani", "Razze canine", r"^Razze canine"),
    ("imperatori", "Imperatori romani", r"^Imperatori romani"),
    ("malattie", "Malattie infettive", r"^Malattie (infettive|virali|batteriche|parassitarie|tropicali|a trasmissione)"),
    ("fiumi", "Fiumi d'Italia", r"^(Fiumi|Affluenti) d"),
    ("scacchi", "Scacchi", r"^(Scacchi|Scacchisti|Aperture|Tornei.* di scacchi|Campion.* di scacchi|Terminologia scacchistica)"),
    ("dolci", "Dolci", r"^(Dolci|Biscotti|Torte|Dessert)"),
    ("formaggi", "Formaggi italiani", r"^Formaggi"),
    ("dinosauri", "Dinosauri", r"^(Dinosauri(?! immaginari)|Saurischi|Ornitischi|Teropodi|Sauropodi|Ornitopodi|Ceratopsi|Celurosauri|Diplodocidi|\w+sauridi$)"),
    ("nobel_fisica", "Vincitori del premio Nobel per la fisica", r"^Vincitori del premio Nobel per la fisica"),
    ("castelli", "Castelli d'Italia", r"^(Castelli|Rocche|Fortezze) d"),
    ("vini", "Vini italiani", r"^(Vini|Vitigni)"),
    ("opere_liriche", "Opere liriche", r"^Opere liriche"),
    ("tennisti", "Tennisti italiani", r"^Tennisti italiani"),
]


def api(params):
    params = {"action": "query", "format": "json", "formatversion": "2", **params}
    url = API + "?" + urllib.parse.urlencode(params)
    for attempt in range(20):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(req, timeout=120) as resp:
                return json.load(resp)
        except urllib.error.HTTPError as e:
            if e.code != 429:
                raise
            time.sleep(int(e.headers.get("Retry-After") or 30) + 1)
        except OSError:      # problema di rete temporaneo
            time.sleep(5 * (attempt + 1))
    raise RuntimeError("API non raggiungibile: " + url)


def fetch(raw_dir):
    raw_dir.mkdir(parents=True, exist_ok=True)
    start = time.time()
    for folder, category, _ in TOPICS:
        target = raw_dir / (folder + ".jsonl")
        if target.exists():          # argomento gia' scaricato: si puo' riprendere dopo un'interruzione
            continue
        docs, offset = [], 0
        while offset is not None and len(docs) < PER_TOPIC:
            data = api({"generator": "search", "gsrsearch": f'deepcat:"{category}"',
                        "gsrnamespace": "0", "gsrlimit": str(BATCH), "gsroffset": str(offset),
                        "gsrsort": "incoming_links_desc",
                        "prop": "cirrusdoc", "cdincludes": "text|category|title"})
            for page in data.get("query", {}).get("pages", []):
                cd = page.get("cirrusdoc") or []
                if cd:
                    src = cd[0]["source"]
                    docs.append({"pageid": page["pageid"], "title": page["title"],
                                 "text": src.get("text", ""), "categories": src.get("category", [])})
            offset = data.get("continue", {}).get("gsroffset")
        tmp = target.with_suffix(".tmp")
        with open(tmp, "w", encoding="utf-8") as f:
            for d in docs:
                f.write(json.dumps(d, ensure_ascii=False) + "\n")
        tmp.rename(target)
        print(f"{folder:14s} {len(docs):4d} voci   ({time.time() - start:.0f} s)", flush=True)


def file_name(title):
    """'Guerra di Troia (film 1961)' -> 'Guerra_di_Troia_(film_1961).txt'"""
    name = unicodedata.normalize("NFC", title).replace(" ", "_")
    name = re.sub(r'[/\\:*?"<>|]', "-", name)
    return name[:150] + ".txt"


def topic_of(categories):
    for folder, _, regex in TOPICS:
        if any(re.search(regex, c) for c in categories):
            return folder
    return "altro"


def build(raw_dir, out_dir):
    seen, rows = set(), []
    for folder, _, _ in TOPICS:
        with open(raw_dir / (folder + ".jsonl"), encoding="utf-8") as f:
            for line in f:
                d = json.loads(line)
                if d["pageid"] in seen or len(d["text"]) < MIN_CHARS:
                    continue
                seen.add(d["pageid"])
                topic = topic_of(d["categories"])
                name = file_name(d["title"])
                (out_dir / topic).mkdir(parents=True, exist_ok=True)
                (out_dir / topic / name).write_text(d["text"] + "\n", encoding="utf-8")
                rows.append((topic, f"{topic}/{name}", d["title"], str(d["pageid"]), str(len(d["text"]))))
    rows.sort()
    with open(out_dir / "manifest.tsv", "w", encoding="utf-8") as f:
        f.write("argomento\tfile\ttitolo\tpageid\tcaratteri\n")
        f.writelines("\t".join(r) + "\n" for r in rows)
    counts = {}
    for r in rows:
        counts[r[0]] = counts.get(r[0], 0) + 1
    for topic, n in sorted(counts.items()):
        print(f"{topic:14s} {n:5d}")
    print(f"Totale: {len(rows)} file, {sum(int(r[4]) for r in rows) / 1e6:.1f} milioni di caratteri")


if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "fetch":
        fetch(Path(sys.argv[2]))
    elif len(sys.argv) == 4 and sys.argv[1] == "build":
        build(Path(sys.argv[2]), Path(sys.argv[3]))
    else:
        sys.exit(__doc__)
