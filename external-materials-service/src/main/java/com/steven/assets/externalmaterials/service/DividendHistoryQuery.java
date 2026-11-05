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
 * 追加）；不濾就會把已撤回或已取代的配息算進殖利率。backend 的讀取端
 * （{@code JdbcDividendCurrentStateRepository}、{@code StockDividendHistoryRepository}）都有濾。</p>
 */
@Component
@RequiredArgsConstructor
public class DividendHistoryQuery {

    private final JdbcTemplate jdbc;

    /** Canonical nominal cash; priceBasisVerified requires independent price-basis evidence. */
    public record CashDividendEvent(
            LocalDate exDividendDate, BigDecimal cashDividend, String eventKey, String source, boolean priceBasisVerified) {
        public CashDividendEvent(LocalDate date, BigDecimal cash, String key, String source) {
            this(date, cash, key, source, false);
        }
    }

    /** 全部 ACTIVE 日期組；未驗證金額保留 null 缺口，不當作零或靜默排除。 */
    public List<CashDividendEvent> activeCashDividends(String stockCode, String market) {
        if (stockCode == null || stockCode.isBlank() || market == null) return List.of();
        return jdbc.query("""
                SELECT COALESCE(h.canonical_date,h.ex_dividend_date,h.ex_rights_date),
                       CASE WHEN h.resolution_status='RESOLVED' AND h.canonical_date IS NOT NULL
                              AND h.source_snapshot_id IS NOT NULL AND s.normalizer_version=2
                              AND s.amount_basis='NOMINAL_PER_EX_DATE' AND s.date_groups_complete=true
                            THEN h.cash_dividend ELSE NULL END AS cash_dividend,
                       h.event_key, h.source
                FROM stock_dividend_history h
                LEFT JOIN stock_dividend_snapshot s ON s.id=h.source_snapshot_id
                WHERE h.stock_code=? AND h.market=? AND h.event_status='ACTIVE'
                ORDER BY COALESCE(h.canonical_date,h.ex_dividend_date,h.ex_rights_date)
                """, (rs, ignored) -> new CashDividendEvent(
                rs.getObject(1, LocalDate.class), rs.getBigDecimal(2),
                rs.getString(3), rs.getString(4)),
                stockCode, market);
    }
}
