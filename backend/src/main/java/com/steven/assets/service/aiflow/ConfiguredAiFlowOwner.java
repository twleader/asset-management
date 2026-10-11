package com.steven.assets.service.aiflow;

import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.service.UserAdminService;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

@Component
public final class ConfiguredAiFlowOwner implements AiFlowOwnerPort {
    private final UserAdminService users;
    private final CurrentUserContext context;
    public ConfiguredAiFlowOwner(UserAdminService users, CurrentUserContext context) {
        this.users = users; this.context = context;
    }
    @Override public long configuredOwner() {
        try {
            var owner = users.configuredAdmin().filter(u -> u.isActive() && u.isAdmin() && u.getId() != null && u.getId() > 0)
                    .orElseThrow(() -> new AiFlowProblem("OWNER_UNAVAILABLE"));
            return owner.getId();
        } catch (DataAccessException ignored) { throw new AiFlowProblem("OWNER_UNAVAILABLE"); }
    }
    @Override public long readOwner() {
        if (!context.hasUser() || !context.isAdmin() || !"ACTIVE".equals(context.getStatus()))
            throw new AiFlowProblem("FORBIDDEN");
        long owner = configuredOwner();
        if (!Long.valueOf(owner).equals(context.getEffectiveUserId())) throw new AiFlowProblem("FORBIDDEN");
        return owner;
    }
}
