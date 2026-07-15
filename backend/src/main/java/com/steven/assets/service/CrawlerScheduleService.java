package com.steven.assets.service;

import com.steven.assets.dto.CrawlerScheduleDto;
import com.steven.assets.model.CrawlerSchedule;
import com.steven.assets.repository.CrawlerScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 公開資訊爬蟲執行時間設定（Requirement 38 / Task 192）。全域設定，無租戶。
 * 讀：某爬蟲的時間點清單（時分升序）。寫：整批覆寫（先驗證＋去重，再 delete+insert）。
 */
@Service
@RequiredArgsConstructor
public class CrawlerScheduleService {

    private final CrawlerScheduleRepository repo;

    /** 取某爬蟲的執行時間點清單（時分升序）。 */
    public List<CrawlerScheduleDto> list(String crawlerKey) {
        return repo.findByCrawlerKeyOrderByRunHourAscRunMinuteAsc(crawlerKey).stream()
                .map(CrawlerScheduleDto::from)
                .toList();
    }

    /**
     * 整批覆寫某爬蟲的執行時間點：驗證時分範圍、以 (hour,minute) 去重（同一時間點多筆只留一筆、
     * enabled 取 OR），再刪除既有列後重新寫入。非法時分擲 {@link IllegalArgumentException}（→ 400）。
     */
    @Transactional
    public List<CrawlerScheduleDto> replace(String crawlerKey, List<CrawlerScheduleDto> times) {
        List<CrawlerScheduleDto> input = times == null ? List.of() : times;
        // 去重：key = hour*60+minute，enabled 取 OR（同時間點只要有一筆啟用即啟用）
        Map<Integer, Boolean> merged = new LinkedHashMap<>();
        for (CrawlerScheduleDto t : input) {
            if (t == null) continue;
            if (t.hour() < 0 || t.hour() > 23) {
                throw new IllegalArgumentException("時（hour）須介於 0–23：" + t.hour());
            }
            if (t.minute() < 0 || t.minute() > 59) {
                throw new IllegalArgumentException("分（minute）須介於 0–59：" + t.minute());
            }
            int key = t.hour() * 60 + t.minute();
            merged.merge(key, t.enabled(), (a, b) -> a || b);
        }

        repo.deleteByCrawlerKey(crawlerKey);
        Instant now = Instant.now();
        List<CrawlerSchedule> rows = new ArrayList<>();
        for (Map.Entry<Integer, Boolean> e : merged.entrySet()) {
            CrawlerSchedule s = new CrawlerSchedule();
            s.setCrawlerKey(crawlerKey);
            s.setRunHour(e.getKey() / 60);
            s.setRunMinute(e.getKey() % 60);
            s.setEnabled(Boolean.TRUE.equals(e.getValue()));
            s.setUpdatedAt(now);
            rows.add(s);
        }
        repo.saveAll(rows);
        return list(crawlerKey);
    }
}
