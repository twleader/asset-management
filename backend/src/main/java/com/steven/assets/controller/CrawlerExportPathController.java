package com.steven.assets.controller;

import com.steven.assets.dto.CrawlerExportPathDto;
import com.steven.assets.model.CrawlerSchedule;
import com.steven.assets.security.AdminRequiredException;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.service.CrawlerExportPathService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公開資訊爬蟲輸出檔案路徑設定（Requirement 38 / Task 212）business API。全域設定：
 * GET 開放已登入者；PUT 以 {@link CurrentUserContext#isAdmin()} 縱深防禦（BFF 已對外擋一層 ADMIN）——
 * 此設定決定服務往主機檔案系統寫入的位置，比照排程設定限管理者。
 *
 * <p>目錄列舉沿用既有 {@code GET /api/export-schedule/browse}（同義＝列出同一基底下子目錄），本控制器不再實作一份。
 */
@RestController
@RequestMapping("/api/crawler-export-path")
@RequiredArgsConstructor
public class CrawlerExportPathController {

    private final CrawlerExportPathService service;
    private final CurrentUserContext currentUser;

    /** 取某爬蟲（預設 news-poller）的輸出路徑設定。 */
    @GetMapping
    public CrawlerExportPathDto.Response get(
            @RequestParam(name = "crawler", defaultValue = CrawlerSchedule.CRAWLER_NEWS_POLLER) String crawler) {
        return service.get(crawler);
    }

    /** 更新輸出子路徑（限管理者；跳脫基底或絕對路徑於 service 擋下 → 400）。 */
    @PutMapping
    public CrawlerExportPathDto.Response update(
            @RequestParam(name = "crawler", defaultValue = CrawlerSchedule.CRAWLER_NEWS_POLLER) String crawler,
            @RequestBody CrawlerExportPathDto.Request req) {
        if (!currentUser.isAdmin()) {
            throw new AdminRequiredException();
        }
        return service.update(crawler, req);
    }
}
