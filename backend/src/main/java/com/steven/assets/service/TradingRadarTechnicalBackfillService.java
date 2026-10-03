package com.steven.assets.service;

import com.steven.assets.client.ExternalTechnicalHistoryBackfillClient;
import com.steven.assets.dto.TradingRadarTechnicalBackfillJobDto;
import com.steven.assets.security.CurrentUserContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class TradingRadarTechnicalBackfillService {
    private final CurrentUserContext currentUser;
    private final ExternalTechnicalHistoryBackfillClient external;
    private final ConcurrentMap<String, TradingRadarTechnicalBackfillJobDto> snapshots = new ConcurrentHashMap<>();

    public TradingRadarTechnicalBackfillService(CurrentUserContext currentUser,
                                                ExternalTechnicalHistoryBackfillClient external) {
        this.currentUser = currentUser; this.external = external;
    }

    public TradingRadarTechnicalBackfillJobDto start() {
        requireAdmin();
        cleanup();
        TradingRadarTechnicalBackfillJobDto job = external.start();
        snapshots.put(job.jobId(), job);
        return job;
    }

    public TradingRadarTechnicalBackfillJobDto get(String jobId) {
        requireAdmin();
        cleanup();
        TradingRadarTechnicalBackfillJobDto job = external.get(jobId);
        snapshots.put(job.jobId(), job);
        return job;
    }

    private void cleanup() {
        OffsetDateTime cutoff = OffsetDateTime.now().minus(Duration.ofHours(24));
        snapshots.entrySet().removeIf(entry -> {
            TradingRadarTechnicalBackfillJobDto job = entry.getValue();
            String expiry = job.completedAt() == null ? job.createdAt() : job.completedAt();
            try { return OffsetDateTime.parse(expiry).isBefore(cutoff); }
            catch (RuntimeException invalid) { return true; }
        });
    }

    private void requireAdmin() {
        if (!currentUser.hasUser() || !currentUser.isAdmin())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "管理者權限不足");
    }
}
