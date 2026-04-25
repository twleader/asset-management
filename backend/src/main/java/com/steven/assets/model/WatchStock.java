package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 觀察股票清單（Watchlist）
 * 與持股、警示獨立；可手動新增/刪除/排序。
 * 同 (stockCode, market) 僅允許一筆。
 */
@Entity
@Table(name = "watch_stock", uniqueConstraints = {
        @UniqueConstraint(name = "uk_watch_stock_code_market", columnNames = {"stock_code", "market"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WatchStock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stock_code", nullable = false, length = 20)
    private String stockCode;

    @Column(nullable = false, length = 20)
    private String market;

    @Column(name = "display_order", nullable = false)
    @Builder.Default
    private Integer displayOrder = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
