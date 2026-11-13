#!/usr/bin/env python3
"""Costruisce il riferimento (ground truth) per la valutazione, senza usare Lucene.

Per ogni query di QUERIES stabilisce quali file del corpus sono rilevanti con uno di tre criteri,
tutti calcolati in modo esaustivo su tutti i file (e' questo che permette di misurare la recall):

  argomento  sono rilevanti tutti e soli i file della cartella indicata, cioe' le voci che
             Wikipedia stessa classifica in quell'argomento (vedi build_corpus.py);
  titolo     sono rilevanti i file il cui titolo soddisfa un'espressione regolare che elenca
             a mano le forme accettate (singolare/plurale, con o senza accento);
  testo      sono rilevanti i file il cui testo soddisfa un'espressione regolare
             (una ricerca "alla grep", indipendente da tokenizzazione e stemming).

Scrive due file nella cartella di uscita:
  query.tsv      id <TAB> query
  rilevanti.tsv  id <TAB> percorso del file rilevante

Uso:
    python3 scripts/build_gold.py corpus_wiki valutazione
"""
import re
import sys
from pathlib import Path

# id, query, criterio, argomento del criterio
QUERIES = [
    # --- campo nome ---
    ("N1", "nome castello", "titolo", r"\bcastell[oi]\b"),
    ("N2", "nome lago", "titolo", r"\blag(o|hi)\b"),
    ("N3", "nome pecorino", "titolo", r"\bpecorin[oi]\b"),
    ("N4", "nome eruzione vesuvio", "titolo", r"\beruzion[ei]\b.*\bvesuvio\b"),
    ("N5", 'nome "isola di"', "titolo", r"\bisola d[i']"),
    # --- campo contenuto: ricerche per argomento ---
    ("C1", "contenuto vulcano eruzione lava cratere", "argomento", "vulcani"),
    ("C2", "contenuto formaggio latte stagionatura caglio", "argomento", "formaggi"),
    ("C3", "contenuto scacchi partita apertura mossa", "argomento", "scacchi"),
    ("C4", "contenuto imperatore romano senato legioni", "argomento", "imperatori"),
    ("C5", "contenuto dinosauro fossili cretaceo", "argomento", "dinosauri"),
    ("C6", "contenuto vino vitigno uve gradazione", "argomento", "vini"),
    ("C7", "contenuto tennista torneo ATP singolare doppio", "argomento", "tennisti"),
    ("C8", "contenuto +razza +cane pelo mantello", "argomento", "cani"),
    # --- campo contenuto: phrase query ---
    ("F1", 'contenuto "premio nobel per la fisica"', "testo", r"\bpremi[oi]\s+nobel\s+per\s+la\s+fisica\b"),
    ("F2", 'contenuto "seconda guerra mondiale"', "testo", r"\bseconda\s+guerra\s+mondiale\b"),
    ("F3", 'contenuto "linguaggio di programmazione"', "testo", r"\blinguagg(io|i)\s+di\s+programmazione\b"),
    ("F4", 'contenuto "denominazione di origine protetta"', "testo", r"\bdenominazion[ei]\s+di\s+origine\s+protett[ae]\b"),
]


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    corpus, out = Path(sys.argv[1]), Path(sys.argv[2])
    out.mkdir(parents=True, exist_ok=True)
    docs = []          # (argomento, percorso relativo, titolo)
    with open(corpus / "manifest.tsv", encoding="utf-8") as f:
        next(f)
        for line in f:
            topic, path, title, _, _ = line.rstrip("\n").split("\t")
            docs.append((topic, path, title))
    texts = None       # letti solo se servono
    with open(out / "query.tsv", "w", encoding="utf-8") as fq, \
            open(out / "rilevanti.tsv", "w", encoding="utf-8") as fr:
        fq.write("# id<TAB>query\n")
        fr.write("# id<TAB>file rilevante\n")
        for qid, query, kind, arg in QUERIES:
            if kind == "argomento":
                relevant = [p for t, p, _ in docs if t == arg]
            elif kind == "titolo":
                rx = re.compile(arg, re.IGNORECASE)
                relevant = [p for _, p, title in docs if rx.search(title)]
            else:
                if texts is None:
                    texts = {p: (corpus / p).read_text(encoding="utf-8") for _, p, _ in docs}
                rx = re.compile(arg, re.IGNORECASE)
                relevant = [p for _, p, _ in docs if rx.search(texts[p])]
            fq.write(f"{qid}\t{query}\n")
            fr.writelines(f"{qid}\t{p}\n" for p in relevant)
            print(f"{qid:3s} {len(relevant):5d} rilevanti   {query}   [{kind}: {arg}]")


if __name__ == "__main__":
    main()
