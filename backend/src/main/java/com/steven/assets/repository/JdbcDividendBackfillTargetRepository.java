package com.steven.assets.repository;

import com.steven.assets.service.DividendBackfillTargetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * 回補對象清單的 JDBC adapter（Task 357.3a）。
 *
 * <p><b>取聯集而不是只取 {@code stock_dividend_history}</b>：後者只涵蓋「曾經成功落地過股利」的
 * 標的，主檔裡新加、還沒抓過的標的會被漏掉；使用者要的是全量回補。</p>
 *
 * <p><b>只跑台股與美股</b>：{@code DividendPersister} 的 warmup／排程本來就只跑這兩個市場，
 * 英股 UCITS ETF 的配息由 {@code MarketDataFetchService.getDividendRate} 即時走 Yahoo，
 * 根本不寫 {@code stock_dividend_history}——把英股放進來只會製造一整批必然失敗的紀錄。</p>
 *
 * <p><b>排除台股 {@code 0000}</b>：那是大盤指數不是個股，沒有配息事件，理由同
 * {@code StockSourceQuery.collectAllStockCodes} 既有的排除。</p>
 *
 * <p>走 {@link JdbcTemplate} 而非 JPA：這是一次性的跨表聯集查詢，沒有對應 entity，
 * 也不值得為它新增一個。</p>
 */
@Repository
@RequiredArgsConstructor
public class JdbcDividendBackfillTargetRepository implements DividendBackfillTargetRepository {

    private static final String SQL = """
            SELECT code, market
              FROM (SELECT stock_code AS code, market FROM stock_dividend_history
                    UNION
                    SELECT code, market FROM stock) AS universe
             WHERE code IS NOT NULL AND market IN ('台股', '美股')
               AND NOT (code = '0000' AND market = '台股')
             ORDER BY market, code
            """;

    private final JdbcTemplate jdbc;

    @Override
    public List<Target> findBackfillTargets() {
        List<Target> targets = new ArrayList<>();
        // 多列查詢的 callback 一律寫成 void 區塊：expression lambda 會被解析成
        // ResultSetExtractor，rs 未定位、runtime 才炸。
        jdbc.query(SQL, (ResultSet rs) -> {
            targets.add(new Target(rs.getString("code"), rs.getString("market")));
        });
        return List.copyOf(targets);
    }
}
