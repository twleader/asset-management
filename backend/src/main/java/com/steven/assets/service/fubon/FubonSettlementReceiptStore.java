package com.steven.assets.service.fubon;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.*;
import java.util.Optional;

/** Immutable, owner-scoped settlement attempts. Latest includes failed attempts. */
public interface FubonSettlementReceiptStore {
    record Receipt(long id, long owner, LocalDate queryDate, Instant observedAt, String status,
            String accountFingerprint, String payloadHash, ObjectNode payload, String reason) {
        public Receipt { payload = payload.deepCopy(); }
        public ObjectNode payload() { return payload.deepCopy(); }
    }
    Optional<Receipt> latest(long owner);
    long append(Receipt receipt);
}
