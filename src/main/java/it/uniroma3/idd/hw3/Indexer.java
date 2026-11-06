package it.uniroma3.idd.hw3;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

/** Indicizza tutti i file .txt contenuti (anche nelle sottocartelle) in una directory. */
public final class Indexer {

    /** Statistiche dell'indicizzazione, riportate nella relazione. */
    public record Stats(int files, long bytes, long readAndAddMillis, long commitMillis, long totalMillis) {
        public double docsPerSecond() {
            return totalMillis == 0 ? files : files * 1000.0 / totalMillis;
        }

        public double megabytes() {
            return bytes / (1024.0 * 1024.0);
        }

        @Override
        public String toString() {
            return String.format(Locale.ITALY,
                    "File indicizzati:     %d%n"
                            + "Dimensione totale:    %.2f MB%n"
                            + "Lettura + analisi:    %d ms%n"
                            + "Commit su disco:      %d ms%n"
                            + "Tempo totale:         %d ms%n"
                            + "Throughput:           %.1f file/s",
                    files, megabytes(), readAndAddMillis, commitMillis, totalMillis, docsPerSecond());
        }
    }

    private Indexer() {
    }

    public static Stats index(Path docsDir, Path indexDir) throws IOException {
        if (!Files.isDirectory(docsDir)) {
            throw new IOException("La directory dei documenti non esiste: " + docsDir);
        }
        List<Path> txtFiles;
        try (Stream<Path> walk = Files.walk(docsDir)) {
            txtFiles = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".txt"))
                    .sorted()
                    .toList();
        }

        IndexWriterConfig config = new IndexWriterConfig(Analyzers.perField());
        // Ricrea l'indice da zero a ogni esecuzione (niente duplicati se si rilancia).
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE);

        long bytes = 0;
        long start = System.nanoTime();
        long afterAdd;
        try (Directory dir = FSDirectory.open(indexDir);
             IndexWriter writer = new IndexWriter(dir, config)) {
            for (Path file : txtFiles) {
                String content = readText(file);
                bytes += Files.size(file);
                writer.addDocument(toDocument(docsDir.relativize(file), content));
            }
            afterAdd = System.nanoTime();
            writer.commit();
        }
        long end = System.nanoTime();
        return new Stats(txtFiles.size(), bytes,
                (afterAdd - start) / 1_000_000,
                (end - afterAdd) / 1_000_000,
                (end - start) / 1_000_000);
    }

    static Document toDocument(Path relativePath, String content) {
        // macOS può restituire i nomi con accenti in forma decomposta (a + accento): li ricomponiamo.
        String fileName = Normalizer.normalize(relativePath.getFileName().toString(), Normalizer.Form.NFC);
        Document doc = new Document();
        doc.add(new StringField(Analyzers.FIELD_PATH, relativePath.toString(), Field.Store.YES));
        doc.add(new StoredField(Analyzers.FIELD_FILENAME, fileName));
        doc.add(new TextField(Analyzers.FIELD_NOME, stripExtension(fileName), Field.Store.YES));
        // Il contenuto è memorizzato per poter mostrare uno snippet evidenziato nei risultati.
        doc.add(new TextField(Analyzers.FIELD_CONTENUTO, content, Field.Store.YES));
        return doc;
    }

    /** "Entity_Matching.txt" → "Entity_Matching": l'estensione è uguale per tutti e non va indicizzata. */
    static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /** Legge il file in UTF-8; se non è UTF-8 valido ripiega su ISO-8859-1 (file salvati con vecchi editor). */
    static String readText(Path file) throws IOException {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (MalformedInputException e) {
            return Files.readString(file, StandardCharsets.ISO_8859_1);
        }
    }
}
