package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 原物料（油價／金價）每日收盤價（Requirement 40）。
 *
 * {@code commodityCode}：{@code WTI}（西德州原油 USD/桶）／{@code BRENT}（布蘭特原油 USD/桶）／
 * {@code GOLD}（COMEX 黃金 USD/盎司）。
 *
 * 全域公開行情，比照 {@link ExchangeRateHistory}／{@code stock_price_history} 不帶 {@code owner_user_id}、
 * 不套 {@code @Filter(ownerFilter)}，所有使用者共用同一份。
 *
 * 僅存原始收盤價：漲跌、漲跌幅、區間最高／最低／平均皆為衍生值，由前端與匯出時即時計算，
 * 不入庫（CLAUDE.md「禁止存入可從其他欄位計算得出的衍生值」）。
 *
 * 資料由 external-materials-service 的 {@code CommodityFetchClient}／{@code CommodityPricePoller} 寫入
 * （business-services 不直連外部行情 API）；新抓列另存 provider、source URL 與實際可得時間，
 * 舊列維持 null 以便 decision-time resolver 明確套用保守重建邊界。
 */
@Entity
@Table(name = "commodity_price_history", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"commodityCode", "priceDate"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CommodityPriceHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** WTI / BRENT / GOLD */
    @Column(nullable = false, length = 20)
    private String commodityCode;

    @Column(nullable = false)
    private LocalDate priceDate;

    /** 當日收盤價（原油 USD/桶、黃金 USD/盎司） */
    @Column(nullable = false, precision = 12, scale = 4)
    private BigDecimal closePrice;

    /** 新抓資料的來源名稱；舊列為 null，resolver 不得自行猜測來源。 */
    @Column(length = 64)
    private String provider;

    /** 可直接稽核的原始查詢 URL；舊列為 null。 */
    @Column(columnDefinition = "text")
    private String sourceUrl;

    /** 來源資料可被本系統得知的最早時間；舊列為 null 並套保守重建邊界。 */
    private Instant sourceAvailableAt;

    /** 本列最近一次向 provider 抓取的時間。 */
    private Instant fetchedAt;
}
