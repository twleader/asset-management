package com.steven.assets.service.fubon;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class FubonSyncOwnerPolicy implements FubonSyncOwnerPort {
    private static final Pattern EMAIL = Pattern.compile(
            "^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$", Pattern.CASE_INSENSITIVE);

    private final FubonSyncOwnerConfigPort config;
    private final FubonSyncOwnerDirectoryPort directory;

    @Autowired
    public FubonSyncOwnerPolicy(FubonSyncOwnerConfigPort config, FubonSyncOwnerDirectoryPort directory) {
        this.config = config;
        this.directory = directory;
    }

    @Override
    public Decision preflight() {
        String configured = normalizedConfiguredEmail();
        if (configured == null) return new Decision(null, Denial.SYNC_OWNER_NOT_CONFIGURED);

        FubonSyncOwnerDirectoryPort.Owner selected = directory.findByNormalizedEmail(configured).orElse(null);
        FubonSyncOwnerDirectoryPort.Owner admin = directory.configuredAdmin().orElse(null);
        if (!sameActiveAdmin(selected, configured) || !sameActiveAdmin(admin, configured)
                || selected.id() == null || admin.id() == null || !selected.id().equals(admin.id())) {
            return new Decision(null, Denial.NO_OWNER);
        }
        return new Decision(selected.id(), null);
    }

    @Override
    public LockedOwner lockAndRevalidate(Long expectedOwnerId) {
        String configured = normalizedConfiguredEmail();
        if (configured == null) throw new Rejected(Denial.SYNC_OWNER_NOT_CONFIGURED);
        if (expectedOwnerId == null) throw new Rejected(Denial.NO_OWNER);

        FubonSyncOwnerDirectoryPort.Owner owner = directory.lockById(expectedOwnerId)
                .orElseThrow(() -> new Rejected(Denial.NO_OWNER));
        if (!expectedOwnerId.equals(owner.id()) || !sameActiveAdmin(owner, configured)
                || !directory.isConfiguredAdmin(configured)) {
            throw new Rejected(Denial.NO_OWNER);
        }
        return new LockedOwner(owner.id());
    }

    private String normalizedConfiguredEmail() {
        String raw = config.syncOwnerEmail();
        String normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return normalized.isBlank() || !EMAIL.matcher(normalized).matches() ? null : normalized;
    }

    private static boolean sameActiveAdmin(FubonSyncOwnerDirectoryPort.Owner owner, String expectedEmail) {
        return owner != null && owner.id() != null && owner.email() != null
                && expectedEmail.equals(owner.email().trim().toLowerCase(Locale.ROOT))
                && owner.active() && owner.admin();
    }
}
