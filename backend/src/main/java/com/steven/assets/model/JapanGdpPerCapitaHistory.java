package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 日本人均 GDP（USD）年度歷史。DGBAS 無日本資料，故純走 IMF（比照韓國）。
 */
@Entity
@Table(name = "japan_gdp_per_capita_history")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class JapanGdpPerCapitaHistory {

    @Id
    @Column(name = "year")
    private Integer year;

    @Column(name = "gdp_usd", nullable = false, precision = 12, scale = 2)
    private BigDecimal gdpUsd;

    /** IMF NGDP_RPCH：Real GDP growth, annual % change */
    @Column(name = "real_gdp_growth_rate", precision = 8, scale = 4)
    private BigDecimal realGdpGrowthRate;
}
