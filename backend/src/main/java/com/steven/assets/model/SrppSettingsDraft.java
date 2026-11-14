package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Filter;
import java.time.Instant;
import java.util.UUID;

/** Values and baseline differences are deliberately frozen audit snapshots (Requirement 208). */
@Entity @Table(name="srpp_settings_draft") @Filter(name="ownerFilter",condition="owner_user_id = :ownerId")
@Getter @Setter @NoArgsConstructor
public class SrppSettingsDraft {
    @Id private UUID id;
    @Column(name="owner_user_id",nullable=false) private Long ownerUserId;
    @Column(nullable=false,length=100) private String name;
    @Column(nullable=false) private long revision;
    @Column(name="base_bundle_sha256",nullable=false,length=64) private String baseBundleSha256;
    @Column(name="model_inputs_sha256",nullable=false,length=64) private String modelInputsSha256;
    @Column(name="catalog_version",nullable=false,length=80) private String catalogVersion;
    @Column(name="catalog_sha256",nullable=false,length=64) private String catalogSha256;
    @Column(name="config_sha256",nullable=false,length=64) private String configSha256;
    @Column(name="values_json",nullable=false,columnDefinition="text") private String valuesJson;
    @Column(name="changes_json",nullable=false,columnDefinition="text") private String changesJson;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    @Column(name="updated_at",nullable=false) private Instant updatedAt;
}
