package com.steven.assets.controller;

import com.steven.assets.dto.CrawlerScheduleDto;
import com.steven.assets.model.CrawlerSchedule;
import com.steven.assets.security.AdminRequiredException;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.service.CrawlerScheduleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 公開資訊爬蟲執行時間設定（Requirement 38 / Task 192）business API。全域設定：
 * GET 開放已登入者；PUT（整批覆寫）以 {@link CurrentUserContext#isAdmin()} 縱深防禦（BFF 已對外擋一層 ADMIN）。
 */
@RestController
@RequestMapping("/api/crawler-schedule")
@RequiredArgsConstructor
public class CrawlerScheduleController {

    private final CrawlerScheduleService service;
    private final CurrentUserContext currentUser;

    /** 取某爬蟲（預設 news-poller）的執行時間點清單。 */
    @GetMapping
    public List<CrawlerScheduleDto> list(
            @RequestParam(name = "crawler", defaultValue = CrawlerSchedule.CRAWLER_NEWS_POLLER) String crawler) {
        return service.list(crawler);
    }

    /** 整批覆寫某爬蟲的執行時間點（限管理者；時分驗證於 service，非法值 → 400）。 */
    @PutMapping
    public List<CrawlerScheduleDto> replace(
            @RequestParam(name = "crawler", defaultValue = CrawlerSchedule.CRAWLER_NEWS_POLLER) String crawler,
            @RequestBody List<CrawlerScheduleDto> times) {
        if (!currentUser.isAdmin()) {
            throw new AdminRequiredException();
        }
        return service.replace(crawler, times);
    }
}
