package cn.suhoan.anaxa.server;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

final class RequestRateLimiter {
    private final RateLimitPolicy defaultPolicy;
    private final ConcurrentHashMap<String, TokenBucket> buckets;

    RequestRateLimiter(int rateLimitPerMinute, int burstCapacity) {
        this.defaultPolicy = new RateLimitPolicy(
                rateLimitPerMinute,
                burstCapacity > 0 ? burstCapacity : rateLimitPerMinute
        );
        this.buckets = new ConcurrentHashMap<>();
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
        TokenBucket bucket = buckets.computeIfAbsent(bucketKey.toString(), ignored -> new TokenBucket(effectivePolicy.burstCapacity()));
        return bucket.tryAcquire(effectivePolicy.burstCapacity(), refillTokensPerNano);
    }

    RateLimitPolicy defaultPolicy() {
        return defaultPolicy;
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

        private TokenBucket(int capacity) {
            this.tokens = capacity;
            this.lastRefillNanos = System.nanoTime();
        }

        private synchronized boolean tryAcquire(int capacity, double refillTokensPerNano) {
            long now = System.nanoTime();
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
    }
}
