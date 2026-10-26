package com.steven.assets.service;

import com.steven.assets.service.port.FundMaintenancePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class FundMaintenanceService {
    private final FundMaintenancePort maintenance;
    public Map<String, Object> refreshNav() { return maintenance.refreshNav(); }
    public Map<String, Object> refreshDividend() { return maintenance.refreshDividend(); }
    public Map<String, Object> backfillNav(int years) { return maintenance.backfillNav(years); }
    public Map<String, Object> backfillDividend(int years) { return maintenance.backfillDividend(years); }
}
