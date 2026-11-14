package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Filter;
import java.time.Instant;
import java.util.UUID;

/** Input and output JSON are frozen run evidence, independent from later profile/draft changes. */
@Entity @Table(name="srpp_settings_run") @Filter(name="ownerFilter",condition="owner_user_id = :ownerId")
@Getter @Setter @NoArgsConstructor
public class SrppSettingsRun {
    @Id private UUID id;
    @Column(name="owner_user_id",nullable=false) private Long ownerUserId;
    @Column(name="draft_id",nullable=false) private UUID draftId;
    @Column(name="draft_revision",nullable=false) private long draftRevision;
    @Column(nullable=false,length=10) private String mode;
    @Column(nullable=false,length=20) private String status;
    @Column(name="config_sha256",nullable=false,length=64) private String configSha256;
    @Column(name="input_sha256",nullable=false,length=64) private String inputSha256;
    @Column(name="result_sha256",length=64) private String resultSha256;
    @Column(name="request_json",nullable=false,columnDefinition="text") private String requestJson;
    @Column(name="result_json",columnDefinition="text") private String resultJson;
    @Column(name="changes_json",nullable=false,columnDefinition="text") private String changesJson;
    @Column(name="ai_status",nullable=false,length=20) private String aiStatus;
    @Column(name="ai_interpretation",columnDefinition="text") private String aiInterpretation;
    @Column(name="error_code",length=80) private String errorCode;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    @Column(name="completed_at") private Instant completedAt;
}
