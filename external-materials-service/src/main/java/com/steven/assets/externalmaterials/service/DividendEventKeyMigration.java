package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.DividendFetchClient;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Date;
import java.util.ArrayList;
import java.util.List;

/**
 * Task 357／357.3a-0：{@code DividendSnapshotStore.canonicalEvent()} 新增
 * {@code exRightsDate} 後，雜湊公式改變會讓全部既有
 * {@code stock_dividend_snapshot_event.event_key} 漂移——即使一筆事件的實際內容完全
 * 沒變（例如純現金事件，exRightsDate 修正前後皆為 null），單純多納入一個欄位進雜湊
 * 字串就會讓 SHA-256 輸出不同。backend {@code DividendCurrentStateProjectionService}
 * 以 {@code "key:" + eventKey} 建立 identity，若不同步重算既有列的 event_key，會把
 * 全部既有事件誤判為「新 snapshot 未含舊 event」而 CANCELLED＋重插。
 *
 * <p><b>為何用 {@code @PostConstruct} 而非 {@code @EventListener(ApplicationReadyEvent.class)}：
 * </b>{@code @PostConstruct} 發生在 Spring 容器初始化階段的 singleton 實例化過程中，
 * 早於 {@code ScheduledAnnotationBeanPostProcessor} 於 {@code afterSingletonsInstantiated}
 * 才註冊 {@code @Scheduled} 排程（{@link DividendPersister#scheduledSyncAll()}），也早於
 * {@code ApplicationReadyEvent}（{@link DividendPersister#warmupOnStartup()} 監聽此事件並
 * 另起背景執行緒抓資料）。因此本遷移必定先於 syncOne／scheduledSyncAll／warmupOnStartup
 * 在新版 canonicalEvent() 公式下寫入任何新的 event_key 之前完成，不存在新舊雜湊公式
 * 混雜的窗口期。</p>
 *
 * <p><b>冪等：</b>對每一列以「目前儲存欄位」（含現有的 ex_rights_date，遷移前一律為
 * null，因該欄位是本任務新增）重算新公式的 event_key；若與目前值相同則略過，重跑
 * 不會有任何變化——這正是本任務要求的「一次性對映或重算機制」，不依賴人工先後部署
 * 順序，也不需要維護額外的遷移狀態表。</p>
 *
 * <p><b>此遷移只處理 {@code stock_dividend_snapshot_event}。</b>backend 端
 * {@code stock_dividend_history.event_key} 與 {@code DividendCurrentStateProjectionService}
 * 的 identity 比對函式（{@code relaxedIdentity}／{@code valueIdentity}／
 * {@code withinScope}）須同步改用 anchorDate 語意並提供對應遷移，屬於 357.3a-0b／
 * 357.3d-0／357.3d-0c 的範圍，由 backend 模組實作。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DividendEventKeyMigration {

    private final JdbcTemplate jdbc;

    @PostConstruct
    public void migrateEventKeys() {
        try {
            runMigration();
        } catch (RuntimeException e) {
            // 遷移失敗不得讓應用啟動整個炸掉（例如測試環境未建表、DB 尚未就緒）；
            // 失敗時 event_key 停留舊公式，下次啟動會重試，且新公式尚未被任何
            // syncOne 使用（尚未達到「新舊雜湊混雜」的風險窗口）。
            log.warn("event_key 遷移失敗，將於下次啟動重試: {}", e.getMessage());
        }
    }

    private void runMigration() {
        List<Row> rows = jdbc.query(
                "SELECT id, event_key, year, ex_dividend_date, ex_rights_date, cash_dividend, "
                        + "stock_dividend, cash_payment_date, stock_payment_date "
                        + "FROM stock_dividend_snapshot_event",
                (rs, rowNum) -> new Row(
                        rs.getLong("id"),
                        rs.getString("event_key"),
                        (Integer) rs.getObject("year"),
                        toIso(rs.getDate("ex_dividend_date")),
                        toIso(rs.getDate("ex_rights_date")),
                        rs.getBigDecimal("cash_dividend"),
                        rs.getBigDecimal("stock_dividend"),
                        toIso(rs.getDate("cash_payment_date")),
                        toIso(rs.getDate("stock_payment_date"))));
        if (rows.isEmpty()) {
            log.info("event_key 遷移：stock_dividend_snapshot_event 無既有列，略過");
            return;
        }
        List<Object[]> updates = new ArrayList<>();
        for (Row row : rows) {
            DividendFetchClient.DividendEvent event = new DividendFetchClient.DividendEvent(
                    row.year(), row.cashDividend(), row.stockDividend(),
                    row.exDividendDate(), row.exRightsDate(),
                    row.cashPaymentDate(), row.stockPaymentDate());
            String newKey = DividendSnapshotStore.canonicalEventHash(event);
            if (!newKey.equals(row.eventKey())) {
                updates.add(new Object[]{newKey, row.id()});
            }
        }
        if (updates.isEmpty()) {
            log.info("event_key 遷移：{} 列既有 event_key 已與新公式一致，無需更新", rows.size());
            return;
        }
        jdbc.batchUpdate("UPDATE stock_dividend_snapshot_event SET event_key=? WHERE id=?", updates);
        log.info("event_key 遷移完成：新公式（納入 ex_rights_date）重算 {} / {} 列",
                updates.size(), rows.size());
    }

    private static String toIso(Date date) {
        return date == null ? null : date.toLocalDate().toString();
    }

    private record Row(long id, String eventKey, Integer year, String exDividendDate,
                        String exRightsDate, BigDecimal cashDividend, BigDecimal stockDividend,
                        String cashPaymentDate, String stockPaymentDate) {}
}
