package com.steven.assets.service.fubon;

public interface FubonSyncOwnerPort {

    Decision preflight();

    LockedOwner lockAndRevalidate(Long expectedOwnerId);

    enum Denial { SYNC_OWNER_NOT_CONFIGURED, NO_OWNER }

    record Decision(Long ownerId, Denial denial) {
        public boolean allowed() {
            return ownerId != null;
        }
    }

    record LockedOwner(Long ownerId) {}

    final class Rejected extends RuntimeException {
        private final Denial denial;

        public Rejected(Denial denial) {
            super(denial.name());
            this.denial = denial;
        }

        public Denial denial() {
            return denial;
        }
    }
}
