package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 台股大盤（TAIEX）年末（12/31 或當年最後交易日）收盤點位。
 */
@Entity
@Table(name = "twse_index_year_end_history")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TwseIndexYearEndHistory {

    @Id
    @Column(name = "year")
    private Integer year;

    @Column(name = "close_point", nullable = false, precision = 12, scale = 2)
    private BigDecimal closePoint;
}
