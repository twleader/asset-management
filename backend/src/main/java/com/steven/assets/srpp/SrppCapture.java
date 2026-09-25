package com.steven.assets.srpp;

import com.steven.assets.dto.LatestAssetsDto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Requirement 163／Task 452.6：同一 owner／規則包／時段的凍結來源（sources 依 sourceId 排序）。 */
public record SrppCapture(
        long ownerId,
        SupportedPolicy policy,
        LocalDate tradingDate,
        String slot,
        LatestAssetsDto.Response assets,
        String assetsRevision,
        Instant capturedAt,
        List<SrppSourceEvidence> sources) {}
