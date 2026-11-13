# Homework 3 – Indicizzazione e ricerca di file .txt con Apache Lucene

Ingegneria dei Dati 2026/2027. Il programma indicizza i file `.txt` di una cartella (sottocartelle comprese)
su due campi, **nome** del file e **contenuto**, e permette di interrogare l'indice da console.

## Requisiti

- Java 21
- Maven 3.9+

## Compilazione

```bash
mvn package
```

Il comando compila, esegue i test JUnit e crea il jar eseguibile `target/hw3-lucene.jar`.

## Uso

```bash
# 1. creare l'indice (viene ricreato da zero a ogni esecuzione)
java -jar target/hw3-lucene.jar index corpus index

# 2. interrogarlo da console
java -jar target/hw3-lucene.jar search index

# 3. eseguire tutte le query di un file (una per riga) e salvare i risultati
java -jar target/hw3-lucene.jar batch index queries.txt > risultati_query.txt

# vedere i token che un analyzer produce per un testo
java -jar target/hw3-lucene.jar analyze nome "SchemaMatching_Valentine-2024"
java -jar target/hw3-lucene.jar analyze contenuto "L'indice della città"
```

All'avvio Lucene stampa un avviso sul modulo `jdk.incubator.vector`: non è un errore e si può ignorare
(per nasconderlo basta aggiungere `2>/dev/null` in fondo al comando).

## Corpus di prova

Nel repository c'è un piccolo corpus scritto a mano (`corpus/`, 23 file) pensato per mettere alla prova
gli analyzer (camelCase, accenti, file non UTF-8). Per le misure dei tempi e per la valutazione si usa
invece un corpus di **6.323 voci di Wikipedia in italiano** (52 MB), che non è versionato ma si ricrea così:

```bash
# scarica le voci di 17 categorie tramite l'API di it.wikipedia.org (circa 12 minuti)
python3 scripts/build_corpus.py fetch corpus_wiki_raw

# crea i file corpus_wiki/<argomento>/<Titolo>.txt e corpus_wiki/manifest.tsv
python3 scripts/build_corpus.py build corpus_wiki_raw corpus_wiki

java -jar target/hw3-lucene.jar index corpus_wiki index_wiki
```

L'elenco delle voci usate nelle misure riportate nella relazione è in [`valutazione/manifest.tsv`](valutazione/manifest.tsv)
(Wikipedia cambia nel tempo: riscaricando oggi il corpus i numeri possono variare leggermente).

## Valutazione

```bash
# costruisce query.tsv e rilevanti.tsv (documenti rilevanti per ogni query), senza usare Lucene
python3 scripts/build_gold.py corpus_wiki valutazione

# precision, recall, F1, P@10, R@100 e average precision per ogni query ("dettagli" elenca alcuni errori)
java -jar target/hw3-lucene.jar eval index_wiki valutazione/query.tsv valutazione/rilevanti.tsv dettagli
```

Per confrontare le scelte sugli analyzer, `index` accetta un terzo argomento con il profilo da usare
(il profilo viene salvato nell'indice, quindi `search`, `batch` ed `eval` lo riconoscono da soli):

| Profilo               | Campo `nome`        | Campo `contenuto`                          |
|-----------------------|---------------------|--------------------------------------------|
| `italiano` (default)  | analyzer su misura  | analyzer italiano con ItalianLightStem     |
| `snowball`            | analyzer su misura  | come sopra, ma con lo stemmer Snowball     |
| `standard`            | StandardAnalyzer    | StandardAnalyzer                           |

```bash
java -jar target/hw3-lucene.jar index corpus_wiki index_wiki_standard standard
java -jar target/hw3-lucene.jar eval index_wiki_standard valutazione/query.tsv valutazione/rilevanti.tsv
```

I risultati ottenuti sono in `valutazione/risultati_<profilo>.txt`.

## Sintassi delle query

```
<campo> elemento elemento ...
```

| Elemento        | Significato                                              | Esempio                               |
|-----------------|----------------------------------------------------------|---------------------------------------|
| `termine`       | il documento *dovrebbe* contenerlo (OR, alza il punteggio)| `nome schema matching`                |
| `"una frase"`   | phrase query: le parole devono comparire in sequenza     | `contenuto "entity resolution"`       |
| `+termine`      | il documento *deve* contenerlo                           | `contenuto +lucene indice`            |
| `-termine`      | il documento *non deve* contenerlo                       | `contenuto +lucene -elasticsearch`    |

`<campo>` è `nome` oppure `contenuto`. I risultati sono ordinati con BM25 e mostrati con uno snippet in cui
i termini trovati sono tra parentesi quadre. Per uscire dalla console: `:q`.

## Analyzer

| Campo       | Catena di analisi                                                                                          |
|-------------|------------------------------------------------------------------------------------------------------------|
| `nome`      | PatternTokenizer su `[^\p{L}\p{N}]+` → WordDelimiterGraph (camelCase, lettere/cifre, mantiene l'originale) → FlattenGraph → LowerCase → ASCIIFolding |
| `contenuto` | StandardTokenizer → Elision (articoli italiani) → LowerCase → Stop (stopword italiane) → ASCIIFolding → ItalianLightStem |

Le motivazioni sono nella relazione e nei commenti di [`Analyzers.java`](src/main/java/it/uniroma3/idd/hw3/Analyzers.java).

## Struttura

```
src/main/java/it/uniroma3/idd/hw3/
  Analyzers.java          campi e analyzer
  Indexer.java            lettura dei file .txt e creazione dell'indice, con misura dei tempi
  SimpleQueryParser.java  traduzione della sintassi semplice in query Lucene
  Searcher.java           esecuzione delle query ed evidenziazione degli snippet
  Evaluator.java          calcolo delle metriche di valutazione
  Main.java               interfaccia a riga di comando
src/test/java/...         test JUnit
scripts/build_corpus.py   scarica le voci di Wikipedia e crea il corpus di file .txt
scripts/build_gold.py     costruisce l'insieme dei documenti rilevanti per le query di valutazione
corpus/                   23 file .txt di prova scritti a mano
queries.txt               query di test sul corpus piccolo  (output: risultati_query.txt)
queries_wiki.txt          query di test sul corpus Wikipedia (output: risultati_query_wiki.txt)
valutazione/              query di valutazione, documenti rilevanti, elenco delle voci e risultati
```
