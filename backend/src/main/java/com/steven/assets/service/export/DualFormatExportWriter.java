package com.steven.assets.service.export;

import com.steven.assets.service.GdriveOutputSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * 把同一份匯出的兩種格式寫成兩個檔，並（啟用時）同步到 Google Drive（Requirement 55 / Task 269）。
 *
 * <p><b>{@code baseName} 不含副檔名</b>——這是「主檔名完全相同」的結構性保證：呼叫端沒有機會讓兩份檔名分岔。
 * 十個匯出點原本各寫一份落檔邏輯（三份直接 {@code Files.write}、其餘 tmp＋{@code ATOMIC_MOVE}），
 * 本元件把「寫兩份 ＋ 同步 Drive ＋ 組狀態字串 ＋ 截斷」收斂成一處。
 */
@Component
@Slf4j
public class DualFormatExportWriter {

    /**
     * {@code last_run_status} 的上限。實測自運行中 DB：九張設定表中八張為 {@code varchar(500)}、
     * {@code stock_alert_export_setting} 為 512、{@code crawler_export_setting} 無此欄。<b>取最短者</b>。
     */
    private static final int LOCAL_STATUS_MAX = 500;
    /** {@code gdrive_last_status} 為 {@code varchar(512)}（實測自運行中 DB，各表皆同）。 */
    private static final int GDRIVE_STATUS_MAX = 512;

    /**
     * 合併狀態字串時，<b>兩半各自先截斷再合併</b>的每半上限。
     *
     * <p>直接把兩半接起來再從尾端截，會在長路徑情境下把 {@code ／json …} 整段切掉——那正好違反
     * 「必須能分辨是哪一份成功、哪一份失敗」的契約（兩半各自可能已達 512，相加超過 1000）。
     * 扣掉 {@code "xlsx "} 與 {@code "／json "} 共 12 字元的固定開銷後對半分。
     */
    private static final int LOCAL_HALF_MAX = (LOCAL_STATUS_MAX - 12) / 2;
    private static final int GDRIVE_HALF_MAX = (GDRIVE_STATUS_MAX - 12) / 2;

    private final GdriveOutputSupport gdrive;

    public DualFormatExportWriter(GdriveOutputSupport gdrive) {
        this.gdrive = gdrive;
    }

    /**
     * @param jsonFile       該份 render 或寫檔失敗時為 {@code null}
     * @param xlsxFile       同上。呼叫端要判斷「兩份都成功」時，判準即 {@code jsonFile != null && xlsxFile != null}
     * @param localStatus    已截斷至 500 字元內
     * @param gdriveStatus   已截斷至 512 字元內；未啟用 Drive 時為 {@code null}（不碰狀態欄）
     * @param xlsxGdrivePath Drive 落點，供 run-now 回報；未上傳為 {@code null}
     */
    public record DualResult(Path jsonFile, Path xlsxFile,
                             String localStatus, String gdriveStatus,
                             String xlsxGdrivePath, String jsonGdrivePath) {}

    /**
     * 寫兩份檔並（啟用時）同步 Drive。
     *
     * <p><b>{@code jsonBytes}／{@code xlsxBytes} 允許為 {@code null}</b>，代表該份 render 失敗——
     * 本元件跳過該份、照寫另一份。這是「一份 render 失敗不得中斷另一份」那條驗收條件的落腳點：
     * render 發生在呼叫端、在本元件之前，簽章若不允許 null，render 擲例外時根本走不到這裡。
     *
     * <p><b>一份失敗不擲例外</b>；只有兩份都不可能成功的情形（{@code dir} 建不出來、{@code baseName} 不合法）才擲。
     *
     * <p><b>跨兩個檔案的原子性做不到，明文接受</b>：極短暫的時間窗內可能只有一份是新的。本元件
     * <b>不含任何刪除目標檔的程式路徑</b>（只刪自己建的 tmp）——「兩份都成功才算成功、否則刪掉已寫的那一份」
     * 是新的風險，且磁碟滿時會讓使用者連舊檔都失去。
     */
    public DualResult write(Long ownerUserId, Path dir, String baseName,
                            byte[] jsonBytes, byte[] xlsxBytes,
                            boolean gdriveEnabled, String gdriveSubpath) throws IOException {
        validateBaseName(dir, baseName);
        Files.createDirectories(dir);

        StringBuilder xlsxStatus = new StringBuilder();
        StringBuilder jsonStatus = new StringBuilder();
        Path xlsxFile = writeOne(dir, baseName, ".xlsx", xlsxBytes, xlsxStatus);
        Path jsonFile = writeOne(dir, baseName, ".json", jsonBytes, jsonStatus);
        String localStatus = truncate(xlsxStatus.toString(), LOCAL_HALF_MAX)
                + "／" + truncate(jsonStatus.toString(), LOCAL_HALF_MAX);

        String gdriveStatus = null;
        String xlsxGdrivePath = null;
        String jsonGdrivePath = null;
        // 順序不可顛倒：本機那一份是既有的留存機制，兩份都寫成功才上傳。
        if (gdriveEnabled && xlsxFile != null && jsonFile != null) {
            GdriveOutputSupport.SyncResult x = gdrive.syncQuietly(ownerUserId, gdriveSubpath, xlsxFile);
            GdriveOutputSupport.SyncResult j = gdrive.syncQuietly(ownerUserId, gdriveSubpath, jsonFile);
            xlsxGdrivePath = x.path();
            jsonGdrivePath = j.path();
            // 字串契約（t270／t271／t272 的斷言都依賴它）：必須能分辨是哪一份成功、哪一份失敗。
            // 兩半各自先截斷再合併——GdriveOutputSupport 已把單邊截到 512，相加會超過 1000，
            // 合併後才從尾端截會把 ／json 那一整段切掉。
            gdriveStatus = "xlsx " + truncate(x.status(), GDRIVE_HALF_MAX)
                    + "／json " + truncate(j.status(), GDRIVE_HALF_MAX);
        } else if (gdriveEnabled) {
            gdriveStatus = truncate("略過：本機未兩份皆成功，不上傳", GDRIVE_STATUS_MAX);
        }

        return new DualResult(jsonFile, xlsxFile, localStatus, gdriveStatus,
                xlsxGdrivePath, jsonGdrivePath);
    }

    /**
     * 寫一份檔：同目錄唯一 tmp ＋ {@code ATOMIC_MOVE}（下游程式可能正在讀同一個檔，就地覆寫會讓對方讀到半截）。
     *
     * @return 成功的落點；{@code bytes} 為 null（render 失敗）或寫檔失敗時回 {@code null}
     */
    private Path writeOne(Path dir, String baseName, String ext, byte[] bytes, StringBuilder status) {
        String label = ext.substring(1);
        if (bytes == null) {
            status.append(label).append(" render 失敗");
            return null;
        }
        Path file = dir.resolve(baseName + ext);
        Path tmp = dir.resolve(baseName + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            status.append(label).append(" 成功：").append(file).append("（").append(bytes.length).append(" bytes）");
            return file;
        } catch (IOException | RuntimeException e) {
            log.warn("雙格式匯出寫檔失敗 {}{}：{}", baseName, ext, e.getMessage(), e);
            status.append(label).append(" 失敗：").append(e.getMessage());
            return null;
        } finally {
            try {
                Files.deleteIfExists(tmp);   // move 成功後為 no-op；write 失敗時清掉殘留
            } catch (IOException ignored) {
                // 清 tmp 失敗不影響本輪結果
            }
        }
    }

    /**
     * {@code baseName} 的路徑逃脫重驗。
     *
     * <p><b>這是搬移既有防線，不是新增</b>——{@code AssetTransactionExportScheduleService.writeToDir} 原有這段
     * （交易紀錄的排程名會進檔名，而 DB 值可能被繞過 API 以 psql 直改）。改用本元件後若不搬過來，
     * 就是在重構中靜默弄丟一道安全檢查。
     */
    private static void validateBaseName(Path dir, String baseName) {
        if (baseName == null || baseName.isBlank()) {
            throw new IllegalArgumentException("匯出主檔名不得為空");
        }
        if (baseName.contains("/") || baseName.contains("\\") || baseName.contains("..")) {
            throw new IllegalArgumentException("匯出主檔名不得含路徑分隔字元或 ..：" + baseName);
        }
        Path base = dir.toAbsolutePath().normalize();
        for (String ext : new String[]{".json", ".xlsx"}) {
            Path target = base.resolve(baseName + ext).normalize();
            if (!target.startsWith(base) || target.getParent() == null || !target.getParent().equals(base)) {
                throw new IllegalArgumentException("匯出主檔名不合法，拒絕寫入：" + baseName);
            }
        }
    }

    /**
     * 截斷至欄位上限內。<b>截斷在本元件做、不是呼叫端做</b>——漏截斷的後果是 JPA save 擲
     * {@code DataException}，把一次<b>本機其實已寫成功</b>的匯出記成失敗。
     */
    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
