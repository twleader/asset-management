package com.steven.assets.integration.fubon;

import com.steven.assets.model.AppUser;
import com.steven.assets.repository.AppUserRepository;
import com.steven.assets.service.UserAdminService;
import com.steven.assets.service.fubon.FubonSyncOwnerConfigPort;
import com.steven.assets.service.fubon.FubonSyncOwnerDirectoryPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class FubonSyncOwnerDirectoryAdapter implements FubonSyncOwnerConfigPort, FubonSyncOwnerDirectoryPort {
    private final AppUserRepository users;
    private final UserAdminService configuredAdmin;
    private final String rawSyncOwnerEmail;

    @Autowired
    public FubonSyncOwnerDirectoryAdapter(AppUserRepository users, UserAdminService configuredAdmin,
            @Value("${fubon.sync-owner-email:}") String rawSyncOwnerEmail) {
        this.users = users;
        this.configuredAdmin = configuredAdmin;
        this.rawSyncOwnerEmail = rawSyncOwnerEmail;
    }

    @Override
    public String syncOwnerEmail() {
        return rawSyncOwnerEmail;
    }

    @Override
    public Optional<Owner> findByNormalizedEmail(String normalizedEmail) {
        return users.findByEmail(normalizedEmail).map(FubonSyncOwnerDirectoryAdapter::owner);
    }

    @Override
    public Optional<Owner> configuredAdmin() {
        return configuredAdmin.configuredAdmin().map(FubonSyncOwnerDirectoryAdapter::owner);
    }

    @Override
    public Optional<Owner> lockById(Long ownerId) {
        return users.findByIdForUpdate(ownerId).map(FubonSyncOwnerDirectoryAdapter::owner);
    }

    @Override
    public boolean isConfiguredAdmin(String normalizedEmail) {
        return configuredAdmin.isConfiguredAdmin(normalizedEmail);
    }

    private static Owner owner(AppUser user) {
        return new Owner(user.getId(), user.getEmail(), user.isActive(), user.isAdmin());
    }
}
