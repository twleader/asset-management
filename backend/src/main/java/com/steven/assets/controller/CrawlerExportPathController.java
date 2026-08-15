package com.steven.assets.controller;

import com.steven.assets.dto.CrawlerExportPathDto;
import com.steven.assets.model.CrawlerSchedule;
import com.steven.assets.security.AdminRequiredException;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.service.CrawlerExportPathService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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

    /**
     * 手動「立即匯出」（Requirement 63 / Task 280）：<b>只重產檔案</b>——不抓取、不寫 {@code news_headline}，
     * 由 DB 現有資料產出 {@code .json} ＋ {@code .xlsx} 兩份並（啟用時）同步 Drive。
     *
     * <p>限管理者：會寫入主機檔案系統與使用者的 Google 雲端硬碟，與「修改輸出路徑」同級。
     * 實際執行者是 {@code external-materials-service}，service 只做 proxy。
     */
    @PostMapping("/run-now")
    public CrawlerExportPathDto.RunNowResponse runNow(
            @RequestParam(name = "crawler", defaultValue = CrawlerSchedule.CRAWLER_NEWS_POLLER) String crawler) {
        if (!currentUser.isAdmin()) {
            throw new AdminRequiredException();
        }
        return service.runNow(crawler);
    }

    /**
     * 手動「立即抓取並匯出」（Requirement 63 / Task 280）：<b>完整跑一輪</b>——抓取 →
     * upsert {@code news_headline} → 產出兩份檔案 → Drive 同步。
     *
     * <p>限管理者：除上述之外還會對外部網站發出請求並寫 {@code news_headline}。
     */
    @PostMapping("/fetch-and-run-now")
    public CrawlerExportPathDto.RunNowResponse fetchAndRunNow(
            @RequestParam(name = "crawler", defaultValue = CrawlerSchedule.CRAWLER_NEWS_POLLER) String crawler) {
        if (!currentUser.isAdmin()) {
            throw new AdminRequiredException();
        }
        return service.fetchAndRunNow(crawler);
    }

    /**
     * 公開觸發「重新搜尋」（Requirement 71）：免登入版「立即抓取並匯出」。
     *
     * <p>不接受 {@code crawler} 參數（固定 news-poller）、<b>不檢查 {@link CurrentUserContext#isAdmin()}</b>——
     * 這是本端點相對既有兩支 ADMIN 端點的唯一差異，由 {@link CrawlerExportPathService#publicRescan()}
     * 內建的全域 30 秒 Redis 冷卻取代 ADMIN 驗證作為濫用防護。
     */
    @PostMapping("/public-rescan")
    public CrawlerExportPathDto.RunNowResponse publicRescan() {
        return service.publicRescan();
    }
}
