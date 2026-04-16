package cn.suhoan.anaxa.server;

record RateLimitPolicy(int rateLimitPerMinute, int burstCapacity) {
    RateLimitPolicy {
        if (rateLimitPerMinute < 0) {
            throw new IllegalArgumentException("rateLimitPerMinute must not be negative");
        }
        if (burstCapacity < 0) {
            throw new IllegalArgumentException("burstCapacity must not be negative");
        }
    }

    boolean enabled() {
        return rateLimitPerMinute > 0 && burstCapacity > 0;
    }
}
