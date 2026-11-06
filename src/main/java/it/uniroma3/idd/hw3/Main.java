package it.uniroma3.idd.hw3;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Punto di ingresso a riga di comando.
 *
 * <pre>
 *   index   &lt;cartella-documenti&gt; &lt;cartella-indice&gt;   crea l'indice
 *   search  &lt;cartella-indice&gt;                        legge le query da console
 *   batch   &lt;cartella-indice&gt; &lt;file-query&gt;          esegue le query di un file (una per riga)
 *   analyze &lt;nome|contenuto&gt; &lt;testo...&gt;              mostra i token prodotti dall'analyzer
 * </pre>
 */
public final class Main {

    private static final int TOP_K = 10;

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            return;
        }
        switch (args[0]) {
            case "index" -> {
                require(args, 3);
                Path docs = Path.of(args[1]);
                Path index = Path.of(args[2]);
                System.out.println("Indicizzazione di " + docs.toAbsolutePath() + " ...");
                Indexer.Stats stats = Indexer.index(docs, index);
                System.out.println(stats);
                System.out.println("Indice salvato in " + index.toAbsolutePath());
            }
            case "search" -> {
                require(args, 2);
                interactive(Path.of(args[1]));
            }
            case "batch" -> {
                require(args, 3);
                batch(Path.of(args[1]), Path.of(args[2]));
            }
            case "analyze" -> {
                require(args, 3);
                String field = args[1].startsWith("n") ? Analyzers.FIELD_NOME : Analyzers.FIELD_CONTENUTO;
                String text = String.join(" ", List.of(args).subList(2, args.length));
                System.out.println(field + ": " + Analyzers.tokens(Analyzers.perField(), field, text));
            }
            default -> usage();
        }
    }

    private static void interactive(Path indexDir) throws IOException {
        try (Searcher searcher = new Searcher(indexDir);
             BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            System.out.println("Indice aperto: " + searcher.numDocs() + " documenti.");
            System.out.println("Sintassi: nome|contenuto termine \"frase\" +obbligatorio -escluso   (:q per uscire)");
            while (true) {
                System.out.print("\n> ");
                String line = in.readLine();
                if (line == null || line.strip().equals(":q") || line.strip().equalsIgnoreCase("esci")) {
                    break;
                }
                if (line.isBlank()) {
                    continue;
                }
                runAndPrint(searcher, line);
            }
        }
    }

    private static void batch(Path indexDir, Path queriesFile) throws IOException {
        try (Searcher searcher = new Searcher(indexDir)) {
            int n = 0;
            for (String line : Files.readAllLines(queriesFile, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                System.out.println("\n================================================================");
                System.out.println("Q" + (++n) + ": " + line);
                System.out.println("================================================================");
                runAndPrint(searcher, line);
            }
        }
    }

    private static void runAndPrint(Searcher searcher, String line) throws IOException {
        try {
            Searcher.print(searcher.search(line, TOP_K), System.out);
        } catch (SimpleQueryParser.QuerySyntaxException e) {
            System.out.println("Errore: " + e.getMessage());
        }
    }

    private static void require(String[] args, int n) {
        if (args.length < n) {
            usage();
            System.exit(1);
        }
    }

    private static void usage() {
        System.out.println("""
                Uso:
                  java -jar hw3-lucene.jar index   <cartella-documenti> <cartella-indice>
                  java -jar hw3-lucene.jar search  <cartella-indice>
                  java -jar hw3-lucene.jar batch   <cartella-indice> <file-query>
                  java -jar hw3-lucene.jar analyze <nome|contenuto> <testo...>
                """);
    }
}
