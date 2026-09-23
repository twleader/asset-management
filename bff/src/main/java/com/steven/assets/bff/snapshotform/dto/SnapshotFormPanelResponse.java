package com.steven.assets.bff.snapshotform.dto;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** 編輯表單單一區塊的完整資料；版本與必要匯率隨同資料一次提交。 */
public record SnapshotFormPanelResponse(
        String panel,
        Long snapshotId,
        Map<String, Object> data,
        List<String> warnings) {
    public SnapshotFormPanelResponse {
        data = Map.copyOf(data);
        warnings = List.copyOf(new LinkedHashSet<>(warnings));
    }
}
