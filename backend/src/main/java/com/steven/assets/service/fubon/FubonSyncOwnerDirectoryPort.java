package com.steven.assets.service.fubon;

import java.util.Optional;

public interface FubonSyncOwnerDirectoryPort {

    Optional<Owner> findByNormalizedEmail(String normalizedEmail);

    Optional<Owner> configuredAdmin();

    Optional<Owner> lockById(Long ownerId);

    boolean isConfiguredAdmin(String normalizedEmail);

    record Owner(Long id, String email, boolean active, boolean admin) {}
}
