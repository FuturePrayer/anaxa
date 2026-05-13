package cn.suhoan.anaxa.server;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

final class RequestRateLimiter {
    private static final int MAX_BUCKETS = 100_000;
    private static final long IDLE_TTL_NANOS = TimeUnit.MINUTES.toNanos(10L);
    private static final long CLEANUP_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(30L);

    private final RateLimitPolicy defaultPolicy;
    private final ConcurrentHashMap<String, TokenBucket> buckets;
    private final AtomicLong nextCleanupNanos;

    RequestRateLimiter(int rateLimitPerMinute, int burstCapacity) {
        this.defaultPolicy = new RateLimitPolicy(
                rateLimitPerMinute,
                burstCapacity > 0 ? burstCapacity : rateLimitPerMinute
        );
        this.buckets = new ConcurrentHashMap<>();
        this.nextCleanupNanos = new AtomicLong(System.nanoTime() + CLEANUP_INTERVAL_NANOS);
    }

    boolean enabled() {
        return defaultPolicy.enabled();
    }

    boolean tryAcquire(String key) {
        return tryAcquire(key, defaultPolicy);
    }

    boolean tryAcquire(String key, RateLimitPolicy policy) {
        RateLimitPolicy effectivePolicy = Objects.requireNonNull(policy, "policy");
        if (!effectivePolicy.enabled()) {
            return true;
        }

        double refillTokensPerNano = effectivePolicy.rateLimitPerMinute() / 60_000_000_000.0D;
        BucketKey bucketKey = new BucketKey(Objects.requireNonNull(key, "key"), effectivePolicy.rateLimitPerMinute(), effectivePolicy.burstCapacity());
        maybeCleanup();
        TokenBucket bucket = buckets.computeIfAbsent(bucketKey.toString(), ignored -> new TokenBucket(effectivePolicy.burstCapacity()));
        return bucket.tryAcquire(effectivePolicy.burstCapacity(), refillTokensPerNano);
    }

    RateLimitPolicy defaultPolicy() {
        return defaultPolicy;
    }

    int bucketCount() {
        return buckets.size();
    }

    private void maybeCleanup() {
        long now = System.nanoTime();
        long scheduled = nextCleanupNanos.get();
        if (now < scheduled || !nextCleanupNanos.compareAndSet(scheduled, now + CLEANUP_INTERVAL_NANOS)) {
            return;
        }

        buckets.entrySet().removeIf(entry -> entry.getValue().idleAndFull(now, IDLE_TTL_NANOS));
        if (buckets.size() <= MAX_BUCKETS) {
            return;
        }

        int toRemove = buckets.size() - MAX_BUCKETS;
        buckets.entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByValue(java.util.Comparator.comparingLong(TokenBucket::lastAccessNanos)))
                .limit(toRemove)
                .forEach(entry -> buckets.remove(entry.getKey(), entry.getValue()));
    }

    private record BucketKey(String key, int rateLimitPerMinute, int burstCapacity) {
        @Override
        public String toString() {
            return key + "|" + rateLimitPerMinute + "|" + burstCapacity;
        }
    }

    private static final class TokenBucket {
        private double tokens;
        private long lastRefillNanos;
        private long lastAccessNanos;

        private TokenBucket(int capacity) {
            this.tokens = capacity;
            this.lastRefillNanos = System.nanoTime();
            this.lastAccessNanos = this.lastRefillNanos;
        }

        private synchronized boolean tryAcquire(int capacity, double refillTokensPerNano) {
            long now = System.nanoTime();
            lastAccessNanos = now;
            long elapsed = now - lastRefillNanos;
            if (elapsed > 0L) {
                tokens = Math.min(capacity, tokens + elapsed * refillTokensPerNano);
                lastRefillNanos = now;
            }

            if (tokens >= 1.0D) {
                tokens -= 1.0D;
                return true;
            }
            return false;
        }

        private synchronized boolean idleAndFull(long now, long idleTtlNanos) {
            return now - lastAccessNanos >= idleTtlNanos && tokens >= 1.0D;
        }

        private synchronized long lastAccessNanos() {
            return lastAccessNanos;
        }
    }
}
