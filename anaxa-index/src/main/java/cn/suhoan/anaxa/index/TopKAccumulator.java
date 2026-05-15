package cn.suhoan.anaxa.index;

import cn.suhoan.anaxa.common.model.SearchHit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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

    public boolean offer(String id, float score, Map<String, Object> payload, long sequence) {
        if (heap.size() < limit) {
            heap.offer(new SearchHit(id, score, payload, sequence));
            return true;
        }

        SearchHit smallest = heap.peek();
        if (smallest == null || compare(score, sequence, smallest) <= 0) {
            return false;
        }
        // 生产查询的精确扫描会评估大量候选；只有确认能进入 topK 后才复制 payload，减少无效对象分配。
        heap.poll();
        heap.offer(new SearchHit(id, score, payload, sequence));
        return true;
    }

    public List<SearchHit> toSortedList() {
        ArrayList<SearchHit> hits = new ArrayList<>(heap);
        hits.sort(RESULT_ORDER);
        return hits;
    }

    private static int compare(float score, long sequence, SearchHit right) {
        int scoreComparison = Float.compare(score, right.score());
        return scoreComparison != 0 ? scoreComparison : Long.compare(sequence, right.sequence());
    }
}
