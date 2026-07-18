package com.steven.assets.dto;

/**
 * 爬蟲輸出檔案路徑設定（Requirement 38 / Task 209）。
 *
 * <p>{@code baseDir} 與 {@code absolutePath} 為**衍生顯示值**（由基底 resolve 子路徑得出），只出現在 response、
 * 不入庫；DB 只存 {@code outputSubpath}（CLAUDE.md「禁止存入可計算得出的衍生值」）。前端據此顯示完整落點，
 * 使用者不必自行拼接。
 */
public class CrawlerExportPathDto {

    /** 讀取／更新後的設定內容。 */
    public record Response(
            String crawlerKey,
            String outputSubpath,
            String baseDir,
            String absolutePath,
            String updatedAt
    ) {}

    /** 更新請求：只帶子路徑（空字串／null → 後端正規化為預設值）。 */
    public record Request(String outputSubpath) {}
}
