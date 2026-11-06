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
  Main.java               interfaccia a riga di comando
src/test/java/...         test JUnit
corpus/                   23 file .txt di prova
queries.txt               query di test
risultati_query.txt       output delle query di test
```
