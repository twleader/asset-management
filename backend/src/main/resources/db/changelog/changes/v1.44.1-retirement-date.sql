--liquibase formatted sql

--changeset steven:v1.44.1-investment-profile-retirement-date
--comment 資產配置建議（Requirement 32 / Task 152）：新增預計退休年月欄位 retirement_date。退休前為累積期（每月投入有效）、退休後薪水停止每月投入歸零；以該月一號存 DATE（YearMonth↔DATE converter）。累積年數／退休後年數為衍生值不入庫，由 service 依此欄位與今天現算。獨立 v1.44.1 addColumn（非改 v1.44.0 CREATE）以免既跑過的 DB checksum 撞牆。
ALTER TABLE investment_profile ADD COLUMN retirement_date DATE;
