package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
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
 * （business-services 不直連外部行情 API），本 entity 只負責讀取與十年清理。
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
}
