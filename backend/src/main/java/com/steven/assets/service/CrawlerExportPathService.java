package com.steven.assets.service;

import com.steven.assets.dto.CrawlerExportPathDto;
import com.steven.assets.model.CrawlerExportSetting;
import com.steven.assets.repository.CrawlerExportSettingRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 公開資訊爬蟲輸出檔案路徑設定（Requirement 38 / Task 209）。全域設定，無租戶。
 *
 * <p><b>路徑模型</b>（沿用 Requirement 34／39／41／42）：DB 只存相對子路徑，實際目錄 = 容器內基底
 * {@code EXPORT_OUTPUT_DIR} resolve 之。本服務只負責「設定」；實際寫檔的是 {@code external-materials-service}
 * 的 {@code NewsPoller}——**兩個容器掛同一個 host 目錄到同一個容器路徑 {@code /home/steven}**，前端資料夾樹
 * （由 {@link ExportScheduleService#browse} 於本服務列舉）看得到的目錄才等於爬蟲寫得到的目錄。
 *
 * <p><b>驗證</b>：以「基底 resolve 子路徑後 normalize 必須仍在基底內」擋 {@code ..} 跳脫與絕對路徑
 * （{@link IllegalArgumentException} → 400）。ext 端寫檔前會再驗一次（縱深防禦：實際持有檔案系統寫入權的是
 * ext，不能只信上游驗過）。
 */
@Service
public class CrawlerExportPathService {

    private static final ZoneId TW_ZONE = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(TW_ZONE);

    private final CrawlerExportSettingRepository repo;

    /** 容器內基底輸出目錄，經 docker volume 對映到 host 家目錄（與 business 的排程匯出共用同一基底）。 */
    private final String baseDir;

    public CrawlerExportPathService(CrawlerExportSettingRepository repo,
                                    @Value("${EXPORT_OUTPUT_DIR:/home/steven}") String baseDir) {
        this.repo = repo;
        this.baseDir = baseDir;
    }

    /** 取某爬蟲的輸出路徑設定；尚未設定時回預設值（不寫入 DB）。 */
    public CrawlerExportPathDto.Response get(String crawlerKey) {
        CrawlerExportSetting s = repo.findByCrawlerKey(crawlerKey).orElseGet(() -> {
            CrawlerExportSetting fallback = new CrawlerExportSetting();
            fallback.setCrawlerKey(crawlerKey);
            fallback.setOutputSubpath(CrawlerExportSetting.DEFAULT_SUBPATH);
            return fallback;
        });
        return toResponse(s);
    }

    /** upsert 某爬蟲的輸出子路徑；跳脫基底者擲 {@link IllegalArgumentException}（→ 400）。 */
    @Transactional
    public CrawlerExportPathDto.Response update(String crawlerKey, CrawlerExportPathDto.Request req) {
        String subpath = normalizeSubpath(req == null ? null : req.outputSubpath());
        resolveDir(subpath); // 驗證不跳脫基底（丟出即擋下）

        CrawlerExportSetting s = repo.findByCrawlerKey(crawlerKey).orElseGet(CrawlerExportSetting::new);
        s.setCrawlerKey(crawlerKey);
        s.setOutputSubpath(subpath);
        s.setUpdatedAt(Instant.now());
        return toResponse(repo.save(s));
    }

    /** 去頭尾空白與結尾斜線（避免 {@code input} 與 {@code input/} 存成兩種值）；空字串 → 預設子路徑。 */
    private static String normalizeSubpath(String subpath) {
        String sub = subpath == null ? "" : subpath.trim();
        while (sub.endsWith("/")) sub = sub.substring(0, sub.length() - 1);
        return sub.isEmpty() ? CrawlerExportSetting.DEFAULT_SUBPATH : sub;
    }

    /**
     * 基底 resolve 子路徑並驗證仍在基底內。開頭的 {@code /} 刻意**不**先剝除——絕對路徑一律回 400 讓使用者
     * 知道只能填相對子路徑，勝過默默改寫語意把 {@code /etc} 當成 {@code <base>/etc}。
     */
    private Path resolveDir(String subpath) {
        Path base = Path.of(baseDir).toAbsolutePath().normalize();
        Path target = base.resolve(subpath).normalize();
        if (!target.startsWith(base)) {
            throw new IllegalArgumentException("輸出子路徑不可跳脫基底目錄，且須為相對路徑：" + subpath);
        }
        return target;
    }

    private CrawlerExportPathDto.Response toResponse(CrawlerExportSetting s) {
        String subpath = normalizeSubpath(s.getOutputSubpath());
        return new CrawlerExportPathDto.Response(
                s.getCrawlerKey(),
                subpath,
                baseDir,
                absolutePathOrNull(subpath),
                s.getUpdatedAt() == null ? null : TS_FMT.format(s.getUpdatedAt()));
    }

    /**
     * 顯示用的完整落點；**讀取時不因既有值不合法而失敗**。PUT 已擋下跳脫值，但 DB 內仍可能存在繞過 API 寫入的
     * 舊值（psql 直改、跨環境備份還原、日後改動 {@code EXPORT_OUTPUT_DIR} 基底）。若讀取也一併擲例外，設定頁會
     * 500 而無法載入，使用者反而**沒有辦法用 UI 把它改回正常值**（唯一的修正入口被自己鎖死）。故此處回 null，
     * 前端顯示「—」、欄位仍可重選並儲存（存檔時照樣驗證）。ext 端遇同樣情形則 fallback 至預設子路徑。
     */
    private String absolutePathOrNull(String subpath) {
        try {
            return resolveDir(subpath).toString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
