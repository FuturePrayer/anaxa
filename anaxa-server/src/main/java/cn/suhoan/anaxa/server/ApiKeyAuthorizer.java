package cn.suhoan.anaxa.server;

import cn.suhoan.anaxa.common.json.JsonSupport;
import cn.suhoan.anaxa.common.model.CollectionDefinition;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.sun.net.httpserver.Headers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class ApiKeyAuthorizer {
    private static final long RELOAD_CHECK_INTERVAL_NANOS = 1_000_000_000L;

    private final Map<String, AuthenticatedPrincipal> staticKeys;
    private final Path apiKeyFile;

    private volatile LoadedSecurityConfig loadedConfig;
    private volatile long lastReloadCheckNanos;

    ApiKeyAuthorizer(Set<String> apiKeys, Path apiKeyFile) {
        this.staticKeys = buildStaticKeys(Objects.requireNonNull(apiKeys, "apiKeys"));
        this.apiKeyFile = apiKeyFile;
        this.loadedConfig = new LoadedSecurityConfig(-1L, SecurityConfig.empty());
        this.lastReloadCheckNanos = 0L;
    }

    boolean enabled() {
        return !staticKeys.isEmpty() || apiKeyFile != null;
    }

    AuthenticatedPrincipal authenticate(Headers headers) {
        if (!enabled()) {
            return AuthenticatedPrincipal.openAccess();
        }

        String presented = extractPresentedKey(headers);
        if (presented == null || presented.isBlank()) {
            throw new UnauthorizedException("Missing or invalid API key");
        }

        AuthenticatedPrincipal principal = staticKeys.get(presented);
        if (principal != null) {
            return principal;
        }

        principal = loadDynamicConfig().securityConfig().keysBySecret().get(presented);
        if (principal != null) {
            return principal;
        }
        throw new UnauthorizedException("Missing or invalid API key");
    }

    TenantPolicy tenantPolicy(String tenantId) {
        String normalizedTenantId = CollectionDefinition.normalizeTenantId(tenantId);
        TenantPolicy policy = loadDynamicConfig().securityConfig().tenantPolicies().get(normalizedTenantId);
        return policy == null ? TenantPolicy.unrestricted(normalizedTenantId) : policy;
    }

    Map<String, TenantPolicy> configuredTenantPolicies() {
        return loadDynamicConfig().securityConfig().tenantPolicies();
    }

    private LoadedSecurityConfig loadDynamicConfig() {
        if (apiKeyFile == null) {
            return loadedConfig;
        }

        long now = System.nanoTime();
        LoadedSecurityConfig snapshot = loadedConfig;
        if (snapshot.lastModifiedMillis() >= 0L && now - lastReloadCheckNanos < RELOAD_CHECK_INTERVAL_NANOS) {
            return snapshot;
        }

        synchronized (this) {
            snapshot = loadedConfig;
            if (snapshot.lastModifiedMillis() >= 0L && now - lastReloadCheckNanos < RELOAD_CHECK_INTERVAL_NANOS) {
                return snapshot;
            }
            lastReloadCheckNanos = now;
            try {
                long modifiedMillis = Files.exists(apiKeyFile)
                        ? Files.getLastModifiedTime(apiKeyFile).toMillis()
                        : -1L;
                if (modifiedMillis == snapshot.lastModifiedMillis()) {
                    return snapshot;
                }
                LoadedSecurityConfig reloaded = new LoadedSecurityConfig(modifiedMillis, loadFileSecurityConfig());
                loadedConfig = reloaded;
                return reloaded;
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to reload API key file " + apiKeyFile, exception);
            }
        }
    }

    private SecurityConfig loadFileSecurityConfig() throws IOException {
        if (apiKeyFile == null || !Files.exists(apiKeyFile)) {
            return SecurityConfig.empty();
        }

        ApiKeyFileConfig config = JsonSupport.mapper().readValue(Files.readAllBytes(apiKeyFile), ApiKeyFileConfig.class);
        HashMap<String, AuthenticatedPrincipal> keys = new HashMap<>();
        for (ApiKeyConfig entry : config.keys()) {
            Set<Role> roles = parseRoles(entry.roles());
            boolean globalTenantAccess = Boolean.TRUE.equals(entry.globalTenantAccess())
                    || (roles.contains(Role.ADMIN) && entry.tenantId() == null);
            String tenantId = globalTenantAccess
                    ? null
                    : CollectionDefinition.normalizeTenantId(
                            entry.tenantId() == null ? CollectionDefinition.DEFAULT_TENANT : entry.tenantId()
                    );
            keys.put(entry.secret(), new AuthenticatedPrincipal(entry.id(), roles, tenantId, globalTenantAccess));
        }

        HashMap<String, TenantPolicy> tenantPolicies = new HashMap<>();
        for (TenantConfig tenant : config.tenants()) {
            RateLimitPolicy rateLimitPolicy = tenant.rateLimitPerMinute() == null
                    ? null
                    : new RateLimitPolicy(
                    tenant.rateLimitPerMinute(),
                    tenant.rateLimitBurst() == null ? tenant.rateLimitPerMinute() : tenant.rateLimitBurst()
            );
            TenantPolicy policy = new TenantPolicy(
                    tenant.id(),
                    tenant.maxCollections(),
                    tenant.maxLiveVectors(),
                    tenant.maxStorageBytes(),
                    rateLimitPolicy
            );
            tenantPolicies.put(policy.tenantId(), policy);
        }

        return new SecurityConfig(Map.copyOf(keys), Map.copyOf(tenantPolicies));
    }

    private static Map<String, AuthenticatedPrincipal> buildStaticKeys(Set<String> apiKeys) {
        HashMap<String, AuthenticatedPrincipal> keys = new HashMap<>();
        for (String apiKey : apiKeys) {
            String normalized = Objects.requireNonNull(apiKey, "apiKey").trim();
            if (normalized.isEmpty()) {
                continue;
            }
            keys.put(
                    normalized,
                    new AuthenticatedPrincipal(normalized, EnumSet.allOf(Role.class), null, true)
            );
        }
        return Map.copyOf(keys);
    }

    private static String extractPresentedKey(Headers headers) {
        String presented = headers.getFirst("X-API-Key");
        if (presented == null || presented.isBlank()) {
            String authorization = headers.getFirst("Authorization");
            if (authorization != null && authorization.startsWith("Bearer ")) {
                presented = authorization.substring("Bearer ".length()).trim();
            }
        }
        return presented;
    }

    private static Set<Role> parseRoles(List<String> roles) {
        if (roles == null || roles.isEmpty()) {
            throw new IllegalArgumentException("API key roles must not be empty");
        }
        EnumSet<Role> parsed = EnumSet.noneOf(Role.class);
        for (String role : roles) {
            parsed.add(Role.valueOf(Objects.requireNonNull(role, "role").trim().toUpperCase()));
        }
        return Set.copyOf(parsed);
    }

    record ApiKeyFileConfig(List<ApiKeyConfig> keys, List<TenantConfig> tenants) {
        ApiKeyFileConfig {
            keys = keys == null ? List.of() : List.copyOf(keys);
            tenants = tenants == null ? List.of() : List.copyOf(tenants);
        }
    }

    record ApiKeyConfig(
            String id,
            String secret,
            List<String> roles,
            @JsonAlias("tenant") String tenantId,
            Boolean globalTenantAccess
    ) {
        ApiKeyConfig {
            id = Objects.requireNonNull(id, "id").trim();
            secret = Objects.requireNonNull(secret, "secret").trim();
            roles = List.copyOf(Objects.requireNonNull(roles, "roles"));
            tenantId = tenantId == null ? null : tenantId.trim();
            if (id.isEmpty() || secret.isEmpty()) {
                throw new IllegalArgumentException("API key entries require non-blank id and secret");
            }
        }
    }

    record TenantConfig(
            @JsonAlias("tenantId") String id,
            Integer maxCollections,
            Long maxLiveVectors,
            Long maxStorageBytes,
            Integer rateLimitPerMinute,
            Integer rateLimitBurst
    ) {
        TenantConfig {
            id = Objects.requireNonNull(id, "id").trim();
            if (id.isEmpty()) {
                throw new IllegalArgumentException("Tenant config id must not be blank");
            }
        }
    }

    private record SecurityConfig(
            Map<String, AuthenticatedPrincipal> keysBySecret,
            Map<String, TenantPolicy> tenantPolicies
    ) {
        private static SecurityConfig empty() {
            return new SecurityConfig(Map.of(), Map.of());
        }
    }

    private record LoadedSecurityConfig(long lastModifiedMillis, SecurityConfig securityConfig) {
    }

    static final class UnauthorizedException extends RuntimeException {
        private UnauthorizedException(String message) {
            super(message);
        }
    }
}
