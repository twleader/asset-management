package com.steven.assets.model;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * {@link YearMonth} ↔ DB {@code DATE} 轉換器：year-month 以「該月 1 號」存 DATE，讀回還原成 YearMonth。
 * 用於 {@link InvestmentProfile#getRetirementDate()}（預計退休年月）等只需年月的欄位。
 */
@Converter
public class YearMonthDateConverter implements AttributeConverter<YearMonth, Date> {

    @Override
    public Date convertToDatabaseColumn(YearMonth attribute) {
        return attribute == null ? null : Date.valueOf(attribute.atDay(1));
    }

    @Override
    public YearMonth convertToEntityAttribute(Date dbData) {
        if (dbData == null) {
            return null;
        }
        LocalDate d = dbData.toLocalDate();
        return YearMonth.of(d.getYear(), d.getMonth());
    }
}
