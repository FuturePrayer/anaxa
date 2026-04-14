package cn.suhoan.anaxa.index;

import cn.suhoan.anaxa.common.model.SearchHit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

public final class TopKAccumulator {
    private static final Comparator<SearchHit> HEAP_ORDER = Comparator
            .comparingDouble(SearchHit::score)
            .thenComparingLong(SearchHit::sequence);

    private static final Comparator<SearchHit> RESULT_ORDER = Comparator
            .comparingDouble(SearchHit::score)
            .reversed()
            .thenComparing(Comparator.comparingLong(SearchHit::sequence).reversed())
            .thenComparing(SearchHit::id);

    private final int limit;
    private final PriorityQueue<SearchHit> heap;

    public TopKAccumulator(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        this.limit = limit;
        this.heap = new PriorityQueue<>(limit, HEAP_ORDER);
    }

    public void offer(SearchHit hit) {
        if (heap.size() < limit) {
            heap.offer(hit);
            return;
        }

        SearchHit smallest = heap.peek();
        if (smallest != null && HEAP_ORDER.compare(hit, smallest) > 0) {
            heap.poll();
            heap.offer(hit);
        }
    }

    public List<SearchHit> toSortedList() {
        ArrayList<SearchHit> hits = new ArrayList<>(heap);
        hits.sort(RESULT_ORDER);
        return hits;
    }
}
