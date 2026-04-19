package com.steven.assets.model;

import java.io.Serializable;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StockId implements Serializable {
    private String code;
    private String market;
}
