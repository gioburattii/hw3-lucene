package it.uniroma3.idd.hw3;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.StopFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.FlattenGraphFilterFactory;
import org.apache.lucene.analysis.core.LowerCaseFilterFactory;
import org.apache.lucene.analysis.custom.CustomAnalyzer;
import org.apache.lucene.analysis.it.ItalianAnalyzer;
import org.apache.lucene.analysis.it.ItalianLightStemFilter;
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilterFactory;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.util.ElisionFilter;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.miscellaneous.WordDelimiterGraphFilterFactory;
import org.apache.lucene.analysis.pattern.PatternTokenizerFactory;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

/**
 * Definisce i campi dell'indice e l'analyzer usato per ciascuno di essi.
 * Lo stesso analyzer viene usato sia in indicizzazione sia in interrogazione,
 * così i termini della query vengono normalizzati esattamente come quelli dei documenti.
 */
public final class Analyzers {

    /** Nome del file senza estensione: campo testuale, analizzato con {@link #nomeAnalyzer()}. */
    public static final String FIELD_NOME = "nome";
    /** Testo del file: campo testuale, analizzato con {@link ItalianAnalyzer}. */
    public static final String FIELD_CONTENUTO = "contenuto";
    /** Percorso relativo del file: chiave univoca, non analizzata. */
    public static final String FIELD_PATH = "path";
    /** Nome completo del file (con estensione): solo memorizzato, per la stampa dei risultati. */
    public static final String FIELD_FILENAME = "filename";

    private Analyzers() {
    }

    /**
     * Analyzer per i nomi di file. Un nome come {@code SchemaMatching_Valentine-2024}
     * diventa i token {@code schemamatching, schema, matching, valentine, 2024}:
     * <ol>
     *   <li>PatternTokenizer: spezza su tutto ciò che non è lettera o cifra
     *       ({@code _ - . spazio} ecc., separatori tipici dei nomi di file);</li>
     *   <li>WordDelimiterGraphFilter: spezza il camelCase e i passaggi lettere/cifre
     *       ({@code homework3 → homework, 3}) mantenendo anche il token originale;</li>
     *   <li>FlattenGraphFilter: necessario dopo un filtro "a grafo" in indicizzazione;</li>
     *   <li>LowerCaseFilter e ASCIIFoldingFilter: ricerca insensibile a maiuscole e accenti.</li>
     * </ol>
     * Niente stopword né stemming: i nomi sono brevi e ogni parola è significativa.
     */
    public static Analyzer nomeAnalyzer() {
        try {
            return CustomAnalyzer.builder()
                    .withTokenizer(PatternTokenizerFactory.class,
                            "pattern", "[^\\p{L}\\p{N}]+",
                            "group", "-1")
                    .addTokenFilter(WordDelimiterGraphFilterFactory.class,
                            "generateWordParts", "1",
                            "generateNumberParts", "1",
                            "splitOnCaseChange", "1",
                            "splitOnNumerics", "1",
                            "preserveOriginal", "1")
                    .addTokenFilter(FlattenGraphFilterFactory.class)
                    .addTokenFilter(LowerCaseFilterFactory.class)
                    .addTokenFilter(ASCIIFoldingFilterFactory.class)
                    .build();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Articoli e preposizioni articolate elise in italiano (stessa lista dell'ItalianAnalyzer di Lucene). */
    private static final CharArraySet ARTICOLI = new CharArraySet(List.of(
            "c", "l", "all", "dall", "dell", "nell", "sull", "coll", "pell",
            "gl", "agl", "dagl", "degl", "negl", "sugl", "un", "m", "t", "s", "v", "d"), true);

    /**
     * Analyzer per il contenuto: è l'{@link ItalianAnalyzer} di Lucene con in più l'ASCII folding.
     * <ol>
     *   <li>StandardTokenizer: segmentazione Unicode (UAX#29) delle parole;</li>
     *   <li>ElisionFilter: {@code l'indice → indice}, {@code dell'analisi → analisi};</li>
     *   <li>LowerCaseFilter;</li>
     *   <li>StopFilter con le stopword italiane di Lucene ({@code il, di, che, è, ...});</li>
     *   <li>ASCIIFoldingFilter: {@code città → citta}. L'ItalianLightStemFilter toglie gli accenti
     *       solo dalle parole di almeno 6 lettere, quindi senza questo passo "città" e "citta"
     *       sarebbero termini diversi;</li>
     *   <li>ItalianLightStemFilter: stemming leggero ({@code indici, indice → indic}).</li>
     * </ol>
     */
    public static Analyzer contenutoAnalyzer() {
        return new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                Tokenizer source = new StandardTokenizer();
                TokenStream result = new ElisionFilter(source, ARTICOLI);
                result = new LowerCaseFilter(result);
                result = new StopFilter(result, ItalianAnalyzer.getDefaultStopSet());
                result = new ASCIIFoldingFilter(result);
                result = new ItalianLightStemFilter(result);
                return new TokenStreamComponents(source, result);
            }

            @Override
            protected TokenStream normalize(String fieldName, TokenStream in) {
                return new ASCIIFoldingFilter(new LowerCaseFilter(new ElisionFilter(in, ARTICOLI)));
            }
        };
    }

    /** Analyzer complessivo: sceglie l'analyzer giusto in base al nome del campo. */
    public static Analyzer perField() {
        return new PerFieldAnalyzerWrapper(
                contenutoAnalyzer(),
                Map.of(FIELD_NOME, nomeAnalyzer()));
    }

    /** Restituisce i token prodotti da un analyzer: utile per capire e documentare le scelte. */
    public static List<String> tokens(Analyzer analyzer, String field, String text) {
        List<String> out = new ArrayList<>();
        try (TokenStream ts = analyzer.tokenStream(field, text)) {
            CharTermAttribute term = ts.addAttribute(CharTermAttribute.class);
            ts.reset();
            while (ts.incrementToken()) {
                out.add(term.toString());
            }
            ts.end();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }
}
