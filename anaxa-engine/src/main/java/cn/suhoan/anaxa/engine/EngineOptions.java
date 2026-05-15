package cn.suhoan.anaxa.engine;

import java.util.Objects;

public record EngineOptions(
        int maxConcurrentSourceSearches,
        long warmupYieldPollMillis,
        int foregroundSearchesPerSourceSearch,
        int minAdaptiveSourceSearches,
        int adaptiveRecoverySearches
) {
    public static final int DEFAULT_MAX_CONCURRENT_SOURCE_SEARCHES = 4;
    public static final long DEFAULT_WARMUP_YIELD_POLL_MILLIS = 2L;
    public static final int DEFAULT_FOREGROUND_SEARCHES_PER_SOURCE_SEARCH = 16;
    public static final int DEFAULT_MIN_ADAPTIVE_SOURCE_SEARCHES = 1;
    public static final int DEFAULT_ADAPTIVE_RECOVERY_SEARCHES = 64;
    public static final EngineOptions DEFAULT = new EngineOptions(
            DEFAULT_MAX_CONCURRENT_SOURCE_SEARCHES,
            DEFAULT_WARMUP_YIELD_POLL_MILLIS,
            DEFAULT_FOREGROUND_SEARCHES_PER_SOURCE_SEARCH,
            DEFAULT_MIN_ADAPTIVE_SOURCE_SEARCHES,
            DEFAULT_ADAPTIVE_RECOVERY_SEARCHES
    );

    public EngineOptions {
        if (maxConcurrentSourceSearches <= 0) {
            throw new IllegalArgumentException("maxConcurrentSourceSearches must be positive");
        }
        if (warmupYieldPollMillis <= 0L) {
            throw new IllegalArgumentException("warmupYieldPollMillis must be positive");
        }
        if (foregroundSearchesPerSourceSearch <= 0) {
            throw new IllegalArgumentException("foregroundSearchesPerSourceSearch must be positive");
        }
        if (minAdaptiveSourceSearches <= 0) {
            throw new IllegalArgumentException("minAdaptiveSourceSearches must be positive");
        }
        if (minAdaptiveSourceSearches > maxConcurrentSourceSearches) {
            throw new IllegalArgumentException("minAdaptiveSourceSearches must not exceed maxConcurrentSourceSearches");
        }
        if (adaptiveRecoverySearches <= 0) {
            throw new IllegalArgumentException("adaptiveRecoverySearches must be positive");
        }
    }

    public static EngineOptions defaults() {
        return DEFAULT;
    }

    public int sourceSearchLimit(int activeForegroundSearches) {
        int adaptiveLimit = maxConcurrentSourceSearches - (activeForegroundSearches / foregroundSearchesPerSourceSearch);
        return Math.max(minAdaptiveSourceSearches, Math.min(maxConcurrentSourceSearches, adaptiveLimit));
    }

    public static EngineOptions ofNullable(
            Integer maxConcurrentSourceSearches,
            Long warmupYieldPollMillis,
            Integer foregroundSearchesPerSourceSearch,
            Integer minAdaptiveSourceSearches,
            Integer adaptiveRecoverySearches
    ) {
        EngineOptions defaults = defaults();
        return new EngineOptions(
                Objects.requireNonNullElse(maxConcurrentSourceSearches, defaults.maxConcurrentSourceSearches()),
                Objects.requireNonNullElse(warmupYieldPollMillis, defaults.warmupYieldPollMillis()),
                Objects.requireNonNullElse(foregroundSearchesPerSourceSearch, defaults.foregroundSearchesPerSourceSearch()),
                Objects.requireNonNullElse(minAdaptiveSourceSearches, defaults.minAdaptiveSourceSearches()),
                Objects.requireNonNullElse(adaptiveRecoverySearches, defaults.adaptiveRecoverySearches())
        );
    }
}
