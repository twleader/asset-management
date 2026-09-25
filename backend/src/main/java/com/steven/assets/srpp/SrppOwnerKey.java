package com.steven.assets.srpp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Requirement 163／Task 452.1：每個 owner 一個隨機 UUID，供 context 的 {@code ownerKey} 使用，
 * 避免回應暴露 owner id 或 email。不加 {@code ownerFilter}；查詢一律帶明確 ownerId。
 */
@Entity
@Table(name = "srpp_owner_key")
public class SrppOwnerKey {
    @Id
    @Column(name = "owner_user_id", nullable = false, updatable = false)
    private Long ownerUserId;
    @Column(name = "owner_key", nullable = false, updatable = false, unique = true)
    private UUID ownerKey;
    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    protected SrppOwnerKey() {}

    public SrppOwnerKey(Long ownerUserId, UUID ownerKey, Instant createdAt) {
        this.ownerUserId = ownerUserId;
        this.ownerKey = ownerKey;
        this.createdAt = createdAt;
    }

    public Long getOwnerUserId() { return ownerUserId; }
    public UUID getOwnerKey() { return ownerKey; }
    public Instant getCreatedAt() { return createdAt; }
}
