package com.steven.assets.externalmaterials.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * {@code stock_dividend_history}（配息 current-state）的<b>唯讀</b>投影，供 Requirement 74 / Task 334
 * 的美股歷史殖利率推導取用。
 *
 * <p><b>這是一條刻意限縮的跨邊界唯讀依賴，寫入端不在本服務。</b>本服務的
 * {@link DividendPersister} 只 append {@code stock_dividend_snapshot} / {@code _event} /
 * {@code _fetch_observation} 三張 evidence 表，current-state 的 ACTIVE／CANCELLED 決策與投影一律由
 * business-services 的 {@code DividendCurrentStateProjectionService} 負責。本類別<b>只 SELECT</b>，
 * 不得新增任何 INSERT／UPDATE／DELETE；要改配息事實一律回 business 端改。</p>
 *
 * <p><b>必須過濾 {@code event_status='ACTIVE'}。</b>來源撤回的權息事件會被標成
 * {@code 'CANCELLED'} 而不是刪除（欄位由 changeset {@code v1.94.0-radar-event-observations.sql}
 * 追加，只有這兩個值）；不濾就會把已撤回的配息算進殖利率。backend 的兩個讀取端
 * （{@code JdbcDividendCurrentStateRepository}、{@code StockDividendHistoryRepository}）都有濾。</p>
 */
@Component
@RequiredArgsConstructor
public class DividendHistoryQuery {

    private final JdbcTemplate jdbc;

    /**
     * 一筆現金股利事件。{@code eventKey}／{@code source} 一併帶出，是因為 Task 334.3 規定
     * 「同一除息日多值」時必須<b>先試這兩欄能否確定性判別</b>、比值啟發式只是 fallback。
     */
    public record CashDividendEvent(
            LocalDate exDividendDate, BigDecimal cashDividend, String eventKey, String source) {}

    /** 該標的在該市場全部 ACTIVE 且有除息日與現金股利的事件，依除息日由舊到新。 */
    public List<CashDividendEvent> activeCashDividends(String stockCode, String market) {
        if (stockCode == null || stockCode.isBlank() || market == null) return List.of();
        return jdbc.query("""
                SELECT ex_dividend_date, cash_dividend, event_key, source
                FROM stock_dividend_history
                WHERE stock_code=? AND market=? AND event_status='ACTIVE'
                  AND ex_dividend_date IS NOT NULL AND cash_dividend IS NOT NULL
                ORDER BY ex_dividend_date
                """, (rs, ignored) -> new CashDividendEvent(
                rs.getObject(1, LocalDate.class), rs.getBigDecimal(2),
                rs.getString(3), rs.getString(4)),
                stockCode, market);
    }
}
