--liquibase formatted sql

--changeset steven:v1.18.0-real-gdp-growth
--comment 加入 IMF Real GDP growth (NGDP_RPCH, annual % change) 欄位；取代前端用人均 GDP 推算之失真年增率。

ALTER TABLE taiwan_gdp_per_capita_history
    ADD COLUMN real_gdp_growth_rate NUMERIC(8,4);

ALTER TABLE korea_gdp_per_capita_history
    ADD COLUMN real_gdp_growth_rate NUMERIC(8,4);
