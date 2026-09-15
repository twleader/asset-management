package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Immutable ledger-to-holding cost projection evidence. */
@Entity
@Table(name = "broker_filled_trade_cost_projection", uniqueConstraints = {
        @UniqueConstraint(name = "uq_broker_fill_cost_projection", columnNames = {"owner_user_id", "broker_id", "broker_filled_no"}),
        @UniqueConstraint(name = "uq_broker_fill_cost_projection_transaction", columnNames = "asset_transaction_id")})
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class BrokerFilledTradeCostProjection {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "owner_user_id", nullable = false) private Long ownerUserId;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "broker_id", nullable = false) private BrokerEntity broker;
    @Column(name = "broker_filled_no", nullable = false, length = 50) private String brokerFilledNo;
    @Column(name = "asset_transaction_id", nullable = false) private Long assetTransactionId;
    @Column(name = "stock_code", nullable = false, length = 20) private String stockCode;
    @Column(nullable = false, length = 20) private String market;
    @Column(nullable = false, length = 10) private String currency;
    @Column(name = "transaction_type", nullable = false, length = 10) private String transactionType;
    @Column(name = "trade_date", nullable = false) private LocalDate tradeDate;
    @Column(nullable = false, precision = 15, scale = 5) private BigDecimal shares;
    @Column(name = "buy_cost", nullable = false, precision = 20, scale = 2) private BigDecimal buyCost;
    @Column(nullable = false, length = 40) private String status;
}
