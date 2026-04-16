package cn.suhoan.anaxa.server;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

@Name("cn.suhoan.anaxa.Search")
@Label("Anaxa Search")
@Category({"AnaxaDB", "Query"})
final class SearchJfrEvent extends Event {
    @Label("Trace Id")
    String traceId;

    @Label("Collection")
    String collection;

    @Label("Top K")
    int topK;

    @Label("Filter Clauses")
    int filterClauses;

    @Label("Result Count")
    int resultCount;

    @Label("Slow Query")
    boolean slowQuery;

    @Label("Duration Millis")
    long durationMillis;
}
