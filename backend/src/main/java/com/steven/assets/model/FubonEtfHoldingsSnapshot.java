package com.steven.assets.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * 富邦 ETF 成分股持股明細抓取結果（Requirement 123 / Task 389）。
 *
 * <p>只保存「目前最新一次抓取結果」的同 key 覆寫快照（比照 external-materials-service 的
 * {@code fubon_taiex_index_latest}——PK 不含日期欄位，不是時間序列表）。
 * payload 僅含 adapter allowlist 的版本 1 正規化市場資料，不保存 proprietary SDK raw。
 * 此表不透過任何 API／DTO 直接對外暴露。
 */
@Entity
@Table(name = "fubon_etf_holdings_snapshot")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FubonEtfHoldingsSnapshot {

    @Id
    @Column(name = "etf_stock_code", length = 20)
    private String etfStockCode;

    @Column(name = "market", length = 10, nullable = false)
    private String market;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    @Column(name = "success", nullable = false)
    private Boolean success;

    @Column(name = "reason", length = 50)
    private String reason;

    /** 歷史欄名；JSONB 保存版本 1 正規化市場物件語意，不承諾鍵順序或空白。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_response_json", columnDefinition = "jsonb")
    private String rawResponseJson;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
