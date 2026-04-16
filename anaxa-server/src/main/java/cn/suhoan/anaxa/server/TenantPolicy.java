package cn.suhoan.anaxa.server;

import cn.suhoan.anaxa.common.model.CollectionDefinition;

record TenantPolicy(
        String tenantId,
        Integer maxCollections,
        Long maxLiveVectors,
        Long maxStorageBytes,
        RateLimitPolicy rateLimitPolicy
) {
    TenantPolicy {
        tenantId = CollectionDefinition.normalizeTenantId(tenantId);
        if (maxCollections != null && maxCollections < 0) {
            throw new IllegalArgumentException("maxCollections must not be negative");
        }
        if (maxLiveVectors != null && maxLiveVectors < 0L) {
            throw new IllegalArgumentException("maxLiveVectors must not be negative");
        }
        if (maxStorageBytes != null && maxStorageBytes < 0L) {
            throw new IllegalArgumentException("maxStorageBytes must not be negative");
        }
    }

    static TenantPolicy unrestricted(String tenantId) {
        return new TenantPolicy(CollectionDefinition.normalizeTenantId(tenantId), null, null, null, null);
    }
}
