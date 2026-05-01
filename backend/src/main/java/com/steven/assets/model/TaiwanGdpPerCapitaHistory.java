package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 台灣人均 GDP（USD）年度歷史。
 */
@Entity
@Table(name = "taiwan_gdp_per_capita_history")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaiwanGdpPerCapitaHistory {

    @Id
    @Column(name = "year")
    private Integer year;

    @Column(name = "gdp_usd", nullable = false, precision = 12, scale = 2)
    private BigDecimal gdpUsd;
}
