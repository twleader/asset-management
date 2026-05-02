package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 信託基金主檔 (Requirement 19)。
 *
 * 銀行內部代號（華南 4 碼如 02A8、元大用 fund class code 如 93100953A）→ FundClear 三段代碼，
 * 供 external-materials-service 抓取最新 NAV。
 */
@Entity
@Table(name = "fund_master")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FundMaster {

    /** 銀行內部代號，作為 PK 與 FundHolding.fundCode 弱關聯 */
    @Id
    @Column(name = "fund_code", length = 20)
    private String fundCode;

    @Column(name = "fund_name", nullable = false, length = 150)
    private String fundName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bank_id")
    private Bank bank;

    /** 計價幣別 (USD / ZAR / TWD / EUR…)；TWD 表示 NAV 已是台幣，無需 FX 換算 */
    @Column(nullable = false, length = 3)
    private String currency;

    /** offshore (境外) 或 onshore (境內)，決定走 FundClear 哪組 API */
    @Column(nullable = false, length = 10)
    private String site;

    /** FundClear 機構代碼：offshore 3 碼如 043；onshore 5 碼如 A0005 */
    @Column(name = "fundclear_org_code", nullable = false, length = 20)
    private String fundclearOrgCode;

    /** FundClear 基金代碼：offshore 10 碼如 A003800030；onshore 8 碼如 93100953 */
    @Column(name = "fundclear_fund_code", nullable = false, length = 20)
    private String fundclearFundCode;

    /** FundClear 級別代碼：offshore 多為 ISIN（LU0937949237），少數投信內部 code（GSBAMU、ABGHYATUSD）；onshore 為 8 碼+級別字母（93100953A） */
    @Column(name = "fundclear_class_code", nullable = false, length = 20)
    private String fundclearClassCode;

    @Column(nullable = false)
    private Boolean active;
}
