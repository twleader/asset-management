package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Filter;
import java.time.Instant;
import java.util.UUID;

/** Insert-only release request, not an activation flag. Activation is derived from deployed proof. */
@Entity @Table(name="srpp_settings_release") @Filter(name="ownerFilter",condition="owner_user_id = :ownerId")
@Getter @Setter @NoArgsConstructor
public class SrppSettingsRelease {
    @Id private UUID id;
    @Column(name="owner_user_id",nullable=false) private Long ownerUserId;
    @Column(name="draft_id",nullable=false) private UUID draftId;
    @Column(name="run_id",nullable=false) private UUID runId;
    @Column(name="request_sha256",nullable=false,unique=true,length=64) private String requestSha256;
    @Column(name="payload_json",nullable=false,columnDefinition="text") private String payloadJson;
    @Column(name="created_at",nullable=false) private Instant createdAt;
}
