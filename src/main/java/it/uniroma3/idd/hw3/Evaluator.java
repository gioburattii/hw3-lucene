package it.uniroma3.idd.hw3;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Valuta il sistema su un insieme di query di cui si conoscono i documenti rilevanti.
 *
 * <p>File di ingresso (testo, campi separati da tabulazione, righe con # ignorate):
 * <ul>
 *   <li>query: {@code id <TAB> query} (la query usa la stessa sintassi della console);</li>
 *   <li>rilevanti: {@code id <TAB> percorso del file rilevante}, una riga per documento.</li>
 * </ul>
 * Per ogni query si recupera l'<em>intero</em> insieme dei risultati e si calcolano le metriche
 * di {@link Metrics}.
 */
public final class Evaluator {

    /**
     * Metriche di una query.
     *
     * @param retrieved    documenti restituiti
     * @param relevant     documenti rilevanti secondo il riferimento
     * @param hits         documenti restituiti e rilevanti
     * @param precision    hits / retrieved, sull'intero insieme dei risultati
     * @param recall       hits / relevant, sull'intero insieme dei risultati
     * @param f1           media armonica di precision e recall
     * @param pAt10        precision sui primi 10 risultati (rilevanti nei primi 10, diviso 10)
     * @param rAt100       recall sui primi 100 risultati
     * @param avgPrecision average precision: media delle precision misurate a ogni documento
     *                     rilevante trovato (0 per quelli non trovati); premia chi mette i rilevanti in alto
     */
    public record Metrics(int retrieved, int relevant, int hits, double precision, double recall,
                          double f1, double pAt10, double rAt100, double avgPrecision) {
    }

    private Evaluator() {
    }

    public static Metrics evaluate(List<String> ranked, Set<String> relevant) {
        int hits = 0;
        int hitsAt10 = 0;
        int hitsAt100 = 0;
        double sumPrecision = 0;
        for (int i = 0; i < ranked.size(); i++) {
            if (relevant.contains(ranked.get(i))) {
                hits++;
                sumPrecision += (double) hits / (i + 1);
                if (i < 10) {
                    hitsAt10++;
                }
                if (i < 100) {
                    hitsAt100++;
                }
            }
        }
        double precision = ranked.isEmpty() ? 0 : (double) hits / ranked.size();
        double recall = relevant.isEmpty() ? 0 : (double) hits / relevant.size();
        double f1 = precision + recall == 0 ? 0 : 2 * precision * recall / (precision + recall);
        // P@10: rilevanti fra i primi 10, diviso sempre per 10 (anche se i risultati sono meno).
        double pAt10 = hitsAt10 / 10.0;
        double rAt100 = relevant.isEmpty() ? 0 : (double) hitsAt100 / relevant.size();
        double ap = relevant.isEmpty() ? 0 : sumPrecision / relevant.size();
        return new Metrics(ranked.size(), relevant.size(), hits, precision, recall, f1, pAt10, rAt100, ap);
    }

    /**
     * @param details se vero, sotto ogni query stampa alcuni errori: i primi falsi positivi della
     *                classifica e alcuni rilevanti non trovati (utile per capire le cause)
     */
    public static void run(Searcher searcher, Path queriesFile, Path relevantFile, PrintStream out,
                           boolean details) throws IOException {
        Map<String, Set<String>> relevant = new HashMap<>();
        for (String[] f : readTsv(relevantFile)) {
            relevant.computeIfAbsent(f[0], k -> new HashSet<>()).add(nfc(f[1]));
        }

        out.printf("Indice: %d documenti, profilo analyzer \"%s\"%n%n", searcher.numDocs(), searcher.profile());
        out.printf("%-4s %-52s %7s %7s %7s %7s %7s %7s %7s %7s %7s%n",
                "id", "query", "trovati", "rilev.", "giusti", "P", "R", "F1", "P@10", "R@100", "AP");
        double sumP = 0, sumR = 0, sumF1 = 0, sumP10 = 0, sumR100 = 0, sumAp = 0;
        int n = 0;
        for (String[] f : readTsv(queriesFile)) {
            List<String> ranked = new ArrayList<>();
            try {
                for (String path : searcher.searchAll(f[1])) {
                    ranked.add(nfc(path));
                }
            } catch (SimpleQueryParser.QuerySyntaxException e) {
                out.printf("%-4s %-52s errore: %s%n", f[0], f[1], e.getMessage());
                continue;
            }
            Set<String> rel = relevant.getOrDefault(f[0], Set.of());
            Metrics m = evaluate(ranked, rel);
            out.printf(Locale.ROOT, "%-4s %-52s %7d %7d %7d %7.3f %7.3f %7.3f %7.3f %7.3f %7.3f%n",
                    f[0], f[1], m.retrieved(), m.relevant(), m.hits(),
                    m.precision(), m.recall(), m.f1(), m.pAt10(), m.rAt100(), m.avgPrecision());
            if (details) {
                List<String> falsePositives = new ArrayList<>();
                for (int i = 0; i < ranked.size() && falsePositives.size() < 5; i++) {
                    if (!rel.contains(ranked.get(i))) {
                        falsePositives.add((i + 1) + ". " + ranked.get(i));
                    }
                }
                Set<String> found = new HashSet<>(ranked);
                List<String> missed = rel.stream().filter(p -> !found.contains(p)).sorted().limit(5).toList();
                out.println("       primi falsi positivi: " + falsePositives);
                out.println("       rilevanti non trovati: " + missed);
            }
            sumP += m.precision();
            sumR += m.recall();
            sumF1 += m.f1();
            sumP10 += m.pAt10();
            sumR100 += m.rAt100();
            sumAp += m.avgPrecision();
            n++;
        }
        if (n > 0) {
            out.printf(Locale.ROOT, "%-4s %-52s %7s %7s %7s %7.3f %7.3f %7.3f %7.3f %7.3f %7.3f%n",
                    "", "MEDIA su " + n + " query (AP medio = MAP)", "", "", "",
                    sumP / n, sumR / n, sumF1 / n, sumP10 / n, sumR100 / n, sumAp / n);
        }
    }

    private static List<String[]> readTsv(Path file) throws IOException {
        List<String[]> rows = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] fields = line.split("\t", 2);
            if (fields.length == 2) {
                rows.add(new String[] {fields[0].strip(), fields[1].strip()});
            }
        }
        return rows;
    }

    /** Su macOS i nomi con accenti possono arrivare in forma decomposta: si confrontano in forma NFC. */
    private static String nfc(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFC);
    }
}
