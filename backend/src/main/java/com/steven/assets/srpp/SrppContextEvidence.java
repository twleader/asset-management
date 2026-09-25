package com.steven.assets.srpp;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Requirement 163／Task 452.1：package 內單一凍結來源的原始 body（不可 UPDATE；隨 package cascade 刪除）。 */
@Entity
@Table(name = "srpp_context_evidence")
public class SrppContextEvidence {
    @EmbeddedId
    private Id id;
    @Column(name = "body", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String body;

    protected SrppContextEvidence() {}

    public SrppContextEvidence(UUID packageId, String sourceId, String body) {
        this.id = new Id(packageId, sourceId);
        this.body = body;
    }

    public UUID getPackageId() { return id.packageId; }
    public String getSourceId() { return id.sourceId; }
    public String getBody() { return body; }

    @Embeddable
    public static class Id implements Serializable {
        @Column(name = "package_id", nullable = false, updatable = false)
        private UUID packageId;
        @Column(name = "source_id", length = 64, nullable = false, updatable = false)
        private String sourceId;

        protected Id() {}

        public Id(UUID packageId, String sourceId) {
            this.packageId = packageId;
            this.sourceId = sourceId;
        }

        public UUID getPackageId() { return packageId; }
        public String getSourceId() { return sourceId; }

        @Override public boolean equals(Object o) {
            return o instanceof Id other && Objects.equals(packageId, other.packageId)
                    && Objects.equals(sourceId, other.sourceId);
        }

        @Override public int hashCode() { return Objects.hash(packageId, sourceId); }
    }
}
