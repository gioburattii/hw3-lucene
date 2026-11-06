package it.uniroma3.idd.hw3;

import java.io.Closeable;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.uhighlight.DefaultPassageFormatter;
import org.apache.lucene.search.uhighlight.UnifiedHighlighter;
import org.apache.lucene.store.FSDirectory;

/** Interroga l'indice e restituisce i risultati ordinati per rilevanza (BM25). */
public final class Searcher implements Closeable {

    public record Hit(int rank, float score, String path, String snippet) {
    }

    public record Result(String input, String luceneQuery, long totalHits, double millis, List<Hit> hits) {
    }

    private final DirectoryReader reader;
    private final IndexSearcher searcher;
    private final SimpleQueryParser parser;
    private final UnifiedHighlighter highlighter;

    public Searcher(Path indexDir) throws IOException {
        Analyzer analyzer = Analyzers.perField();
        this.reader = DirectoryReader.open(FSDirectory.open(indexDir));
        this.searcher = new IndexSearcher(reader);   // similarità di default: BM25
        this.parser = new SimpleQueryParser(analyzer);
        this.highlighter = UnifiedHighlighter.builder(searcher, analyzer)
                .withFormatter(new DefaultPassageFormatter("[", "]", " ... ", false))
                .build();
    }

    public int numDocs() {
        return reader.numDocs();
    }

    public Result search(String input, int k) throws IOException, SimpleQueryParser.QuerySyntaxException {
        SimpleQueryParser.ParsedQuery pq = parser.parse(input);
        long start = System.nanoTime();
        TopDocs top = searcher.search(pq.query(), k);
        double millis = (System.nanoTime() - start) / 1_000_000.0;

        String[] snippets = highlighter.highlight(pq.field(), pq.query(), top, 2);
        StoredFields stored = searcher.storedFields();
        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < top.scoreDocs.length; i++) {
            ScoreDoc sd = top.scoreDocs[i];
            Document doc = stored.document(sd.doc);
            String snippet = snippets[i] != null ? snippets[i] : doc.get(pq.field());
            hits.add(new Hit(i + 1, sd.score, doc.get(Analyzers.FIELD_PATH), oneLine(snippet, 220)));
        }
        return new Result(input, pq.query().toString(), top.totalHits.value(), millis, hits);
    }

    public static void print(Result r, PrintStream out) {
        out.printf(Locale.ITALY, "Query Lucene: %s%n", r.luceneQuery());
        out.printf(Locale.ITALY, "%d documenti trovati in %.2f ms%n", r.totalHits(), r.millis());
        for (Hit h : r.hits()) {
            out.printf(Locale.ITALY, "%3d. [%.3f] %s%n", h.rank(), h.score(), h.path());
            if (h.snippet() != null && !h.snippet().isBlank()) {
                out.printf("       %s%n", h.snippet());
            }
        }
    }

    private static String oneLine(String s, int max) {
        if (s == null) {
            return "";
        }
        String flat = s.replaceAll("\\s+", " ").strip();
        return flat.length() <= max ? flat : flat.substring(0, max) + "...";
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }
}
