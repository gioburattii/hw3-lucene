package it.uniroma3.idd.hw3;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.util.QueryBuilder;

/**
 * Traduce la sintassi semplice richiesta dall'homework in una query Lucene.
 *
 * <pre>
 *   &lt;campo&gt; elemento elemento ...
 *
 *   campo     := nome | contenuto
 *   elemento  := [+|-] termine | [+|-] "frase tra virgolette"
 * </pre>
 *
 * Esempi:
 * <ul>
 *   <li>{@code nome schema matching} → file il cui nome contiene "schema" o "matching";</li>
 *   <li>{@code contenuto "entity resolution"} → phrase query sul contenuto;</li>
 *   <li>{@code contenuto +lucene indice -elasticsearch} → deve contenere "lucene",
 *       "indice" alza il punteggio, "elasticsearch" esclude il documento.</li>
 * </ul>
 * Senza prefisso gli elementi sono in OR (SHOULD): più elementi corrispondono, più alto è il punteggio BM25.
 * Ogni termine e ogni frase passa attraverso l'analyzer del campo scelto, come in indicizzazione.
 */
public final class SimpleQueryParser {

    /** Una frase tra virgolette oppure una parola, ciascuna con un eventuale prefisso + o -. */
    private static final Pattern ELEMENT = Pattern.compile("([+-]?)\"([^\"]*)\"?|(\\S+)");

    /** Query pronta da eseguire, con il campo interrogato. */
    public record ParsedQuery(String field, Query query) {
    }

    public static final class QuerySyntaxException extends Exception {
        public QuerySyntaxException(String message) {
            super(message);
        }
    }

    private final QueryBuilder builder;

    public SimpleQueryParser(Analyzer analyzer) {
        this.builder = new QueryBuilder(analyzer);
    }

    public ParsedQuery parse(String line) throws QuerySyntaxException {
        String trimmed = line == null ? "" : line.strip();
        if (trimmed.isEmpty()) {
            throw new QuerySyntaxException("Query vuota.");
        }
        String[] parts = trimmed.split("\\s+", 2);
        String field = fieldFor(parts[0]);
        if (parts.length < 2 || parts[1].isBlank()) {
            throw new QuerySyntaxException("Dopo '" + parts[0] + "' serve almeno un termine.");
        }

        BooleanQuery.Builder bool = new BooleanQuery.Builder();
        int clauses = 0;
        boolean hasPositive = false;
        Matcher m = ELEMENT.matcher(parts[1]);
        while (m.find()) {
            String prefix;
            Query q;
            if (m.group(2) != null) {                       // "frase tra virgolette"
                prefix = m.group(1);
                q = builder.createPhraseQuery(field, m.group(2));
            } else {                                        // parola singola
                String word = m.group(3);
                prefix = "";
                if (word.length() > 1 && (word.charAt(0) == '+' || word.charAt(0) == '-')) {
                    prefix = word.substring(0, 1);
                    word = word.substring(1);
                }
                // Una "parola" può produrre più token (es. "data-integration"): restano in OR.
                q = builder.createBooleanQuery(field, word, BooleanClause.Occur.SHOULD);
            }
            if (q == null) {
                continue;   // solo stopword o punteggiatura: l'analyzer non ha prodotto token
            }
            BooleanClause.Occur occur = switch (prefix) {
                case "+" -> BooleanClause.Occur.MUST;
                case "-" -> BooleanClause.Occur.MUST_NOT;
                default -> BooleanClause.Occur.SHOULD;
            };
            hasPositive |= occur != BooleanClause.Occur.MUST_NOT;
            bool.add(q, occur);
            clauses++;
        }
        if (clauses == 0) {
            throw new QuerySyntaxException("Nessun termine significativo (forse solo stopword?).");
        }
        if (!hasPositive) {
            throw new QuerySyntaxException("Servono anche termini da cercare, non solo esclusioni (-).");
        }
        return new ParsedQuery(field, bool.build());
    }

    private static String fieldFor(String keyword) throws QuerySyntaxException {
        return switch (keyword.toLowerCase(Locale.ROOT)) {
            case "nome", "name" -> Analyzers.FIELD_NOME;
            case "contenuto", "content" -> Analyzers.FIELD_CONTENUTO;
            default -> throw new QuerySyntaxException(
                    "La query deve iniziare con 'nome' o 'contenuto' (trovato: '" + keyword + "').");
        };
    }
}
