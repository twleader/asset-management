package com.steven.assets.integration.fubon;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.type.LogicalType;

import java.time.LocalDate;

/** A separate decoder so stricter trade input cannot alter any other broker read contract. */
final class FubonTradeJson {
    private FubonTradeJson() {}

    static ObjectMapper mapper() {
        ObjectMapper mapper = FubonAccountingJson.mapper()
                .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES);
        mapper.coercionConfigFor(LogicalType.Boolean)
                .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail);
        SimpleModule datesAndShares = new SimpleModule();
        datesAndShares.addDeserializer(LocalDate.class, new FubonAccountingJson.LocalDateDeserializer());
        datesAndShares.addDeserializer(Long.TYPE, new ExactSharesDeserializer());
        datesAndShares.addDeserializer(Long.class, new ExactSharesDeserializer());
        mapper.registerModule(datesAndShares);
        return mapper;
    }
}
