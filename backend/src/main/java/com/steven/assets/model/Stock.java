package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "stock")
@IdClass(StockId.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Stock {

    @Id
    @Column(nullable = false, length = 20)
    private String code;

    @Id
    @Column(nullable = false, length = 20)
    private String market;

    @Column(nullable = false, length = 100)
    private String name;
}
