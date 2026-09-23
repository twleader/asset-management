package com.steven.assets.bff.dashboard.dto;

import java.util.List;
import java.util.Map;

/** 一個 Panel 的完整回應；warning 與資料在同一次回應提交。 */
public record DashboardPanelResponse(
        String panel,
        Long snapshotId,
        Map<String, Object> data,
        List<String> warnings) {
    public DashboardPanelResponse {
        data = Map.copyOf(data);
        warnings = List.copyOf(warnings);
    }
}
