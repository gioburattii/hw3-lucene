package it.uniroma3.idd.hw3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Hw3Test {

    @TempDir
    static Path tmp;
    static Path index;

    @BeforeAll
    static void buildIndex() throws Exception {
        Path docs = Files.createDirectories(tmp.resolve("docs/sub"));
        Files.writeString(docs.resolve("../SchemaMatching_2024.txt"), "Lo schema matching allinea gli attributi.", StandardCharsets.UTF_8);
        Files.writeString(docs.resolve("../Città.txt"), "Roma è una bella città.", StandardCharsets.UTF_8);
        Files.writeString(docs.resolve("indici.txt"), "L'indice invertito dell'ingegneria dei dati.", StandardCharsets.UTF_8);
        Files.writeString(docs.resolve("ignorato.md"), "questo file non è .txt", StandardCharsets.UTF_8);
        index = tmp.resolve("index");
        Indexer.Stats stats = Indexer.index(tmp.resolve("docs"), index);
        assertEquals(3, stats.files(), "solo i file .txt, anche nelle sottocartelle");
    }

    @Test
    void nomeAnalyzerSpezzaCamelCaseSeparatoriENumeri() {
        assertEquals(List.of("schemamatching", "schema", "matching", "valentine", "2024"),
                Analyzers.tokens(Analyzers.nomeAnalyzer(), "nome", "SchemaMatching_Valentine-2024"));
    }

    @Test
    void contenutoAnalyzerRimuoveElisioniStopwordAccentiEFaStemming() {
        assertEquals(List.of("indic", "citta", "indic"),
                Analyzers.tokens(Analyzers.contenutoAnalyzer(), "contenuto", "L'indice della città e gli indici"));
    }

    @Test
    void ricercaPerNome() throws Exception {
        try (Searcher s = new Searcher(index)) {
            assertEquals("SchemaMatching_2024.txt", s.search("nome matching", 10).hits().get(0).path());
            assertEquals("Città.txt", s.search("nome citta", 10).hits().get(0).path());
        }
    }

    @Test
    void phraseQueryConStopwordSulContenuto() throws Exception {
        try (Searcher s = new Searcher(index)) {
            Searcher.Result r = s.search("contenuto \"ingegneria dei dati\"", 10);
            assertEquals(1, r.totalHits());
            assertTrue(r.hits().get(0).path().endsWith("indici.txt"));
            assertEquals(0, s.search("contenuto \"dati ingegneria\"", 10).totalHits(), "l'ordine conta");
        }
    }

    @Test
    void esclusioneConMeno() throws Exception {
        try (Searcher s = new Searcher(index)) {
            assertEquals(0, s.search("contenuto +roma -città", 10).totalHits());
        }
    }

    @Test
    void erroriDiSintassi() {
        SimpleQueryParser p = new SimpleQueryParser(Analyzers.perField());
        assertThrows(SimpleQueryParser.QuerySyntaxException.class, () -> p.parse("titolo lucene"));
        assertThrows(SimpleQueryParser.QuerySyntaxException.class, () -> p.parse("nome"));
        assertThrows(SimpleQueryParser.QuerySyntaxException.class, () -> p.parse("contenuto il di"));
        assertThrows(SimpleQueryParser.QuerySyntaxException.class, () -> p.parse("contenuto -roma"));
    }

    @Test
    void metricheDiValutazione() {
        // classifica: R, N, R, N  -  rilevanti: a, c, z (z non viene trovato)
        Evaluator.Metrics m = Evaluator.evaluate(List.of("a", "b", "c", "d"), java.util.Set.of("a", "c", "z"));
        assertEquals(2, m.hits());
        assertEquals(0.5, m.precision(), 1e-9);
        assertEquals(2.0 / 3, m.recall(), 1e-9);
        assertEquals(0.5, m.pAt10(), 1e-9);
        assertEquals((1.0 + 2.0 / 3) / 3, m.avgPrecision(), 1e-9);
    }

    @Test
    void profiloStandardSalvatoNellIndice() throws Exception {
        Path std = tmp.resolve("index-standard");
        Indexer.index(tmp.resolve("docs"), std, Analyzers.PROFILE_STANDARD);
        try (Searcher s = new Searcher(std)) {
            assertEquals(Analyzers.PROFILE_STANDARD, s.profile());
            // senza stemming "indici" non trova "indice"
            assertTrue(s.searchAll("contenuto indici").isEmpty());
        }
        try (Searcher s = new Searcher(index)) {
            assertEquals(1, s.searchAll("contenuto indici").size());
        }
    }
}
