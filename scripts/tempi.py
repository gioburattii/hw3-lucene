#!/usr/bin/env python3
"""Misura il tempo di indicizzazione al crescere del numero di file.

Per ogni dimensione sceglie a caso (con seme fisso) un sottoinsieme dei file del corpus,
lo copia in una cartella temporanea e lo indicizza piu' volte con il jar del progetto.
Stampa per ogni dimensione la mediana dei tempi.

Uso:
    python3 scripts/tempi.py corpus_wiki
"""
import random
import re
import shutil
import statistics
import subprocess
import sys
import tempfile
from pathlib import Path

SIZES = [1000, 2500, 5000, None]   # None = tutti i file
RUNS = 5
JAR = "target/hw3-lucene.jar"


def index_once(docs, index):
    out = subprocess.run(["java", "-jar", JAR, "index", str(docs), str(index)],
                         capture_output=True, text=True, check=True).stdout
    millis = int(re.search(r"Tempo totale:\s+(\d+) ms", out).group(1))
    mb = float(re.search(r"Dimensione totale:\s+([\d,]+) MB", out).group(1).replace(",", "."))
    return millis, mb


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    corpus = Path(sys.argv[1])
    files = sorted(corpus.rglob("*.txt"))
    random.Random(42).shuffle(files)
    print(f"{'file':>6} {'MB':>7} {'mediana (s)':>12} {'file/s':>8}   prove (s)")
    with tempfile.TemporaryDirectory() as tmp:
        for size in SIZES:
            subset = files if size is None else files[:size]
            docs = Path(tmp) / "docs"
            shutil.rmtree(docs, ignore_errors=True)
            for i, f in enumerate(subset):
                target = docs / f.parent.name
                target.mkdir(parents=True, exist_ok=True)
                shutil.copy(f, target / f.name)
            times = []
            for _ in range(RUNS):
                millis, mb = index_once(docs, Path(tmp) / "index")
                times.append(millis / 1000)
            med = statistics.median(times)
            print(f"{len(subset):>6} {mb:>7.1f} {med:>12.2f} {len(subset) / med:>8.0f}   "
                  + " ".join(f"{t:.2f}" for t in times), flush=True)


if __name__ == "__main__":
    main()
