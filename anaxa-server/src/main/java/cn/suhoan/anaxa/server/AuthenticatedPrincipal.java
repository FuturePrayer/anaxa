package cn.suhoan.anaxa.server;

import cn.suhoan.anaxa.common.model.CollectionDefinition;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

record AuthenticatedPrincipal(String id, Set<Role> roles, String tenantId, boolean globalTenantAccess) {
    private static final Set<Role> OPEN_ROLES = Set.copyOf(EnumSet.allOf(Role.class));
    private static final AuthenticatedPrincipal OPEN_ACCESS = new AuthenticatedPrincipal(
            "public",
            OPEN_ROLES,
            CollectionDefinition.DEFAULT_TENANT,
            true
    );
    private static final AuthenticatedPrincipal ANONYMOUS = new AuthenticatedPrincipal(
            "anonymous",
            Set.of(),
            CollectionDefinition.DEFAULT_TENANT,
            false
    );

    AuthenticatedPrincipal(String id, Set<Role> roles) {
        this(id, roles, CollectionDefinition.DEFAULT_TENANT, false);
    }

    AuthenticatedPrincipal {
        id = Objects.requireNonNull(id, "id");
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
        tenantId = tenantId == null ? null : CollectionDefinition.normalizeTenantId(tenantId);
    }

    boolean allows(Role requiredRole) {
        if (requiredRole == null) {
            return true;
        }
        if (roles.contains(Role.ADMIN)) {
            return true;
        }
        return switch (requiredRole) {
            case READER -> roles.contains(Role.READER) || roles.contains(Role.WRITER);
            case WRITER -> roles.contains(Role.WRITER);
            case ADMIN -> false;
        };
    }

    boolean canAccessTenant(String requestedTenantId) {
        return globalTenantAccess || Objects.equals(defaultTenantId(), CollectionDefinition.normalizeTenantId(requestedTenantId));
    }

    String defaultTenantId() {
        return tenantId == null ? CollectionDefinition.DEFAULT_TENANT : tenantId;
    }

    static AuthenticatedPrincipal openAccess() {
        return OPEN_ACCESS;
    }

    static AuthenticatedPrincipal anonymous() {
        return ANONYMOUS;
    }
}
