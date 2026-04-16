package cn.suhoan.anaxa.engine;

public interface EngineObserver {
    EngineObserver NO_OP = new EngineObserver() {
    };

    default void onFlushCompleted(FlushMetrics metrics) {
    }

    default void onCompactionCompleted(CompactionMetrics metrics) {
    }

    default void onSearchCompleted(CollectionSearchMetrics metrics) {
    }
}
