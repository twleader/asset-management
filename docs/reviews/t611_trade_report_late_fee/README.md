# Task611 成交未知費與後到證據

本次保留既有手動 .eml 匯入：官方 orders 不可用與費用未知不阻擋已通過來源/帳戶聲明及去重的新交易，費稅 NULL 不等於零。沒有自動掃信、券商下單或重新同步。

新增明確手動「重新核對待補費」preview/apply，只用既有 Task602 官方 orders＋settlement 唯一對帳；完整多筆回報不縮成單筆，不分攤日總費用。官方 FilledTrade 缺 fee/tax/orderNo，未新增逐筆費用來源。新 schema3 匯入且原費稅皆 NULL、完整帳本不變、無人工接管 marker 才可後補；legacy1/2因無人工歷史拒絕後補。Task602 原同交易日及來源時序/唯一條件維持，並非任何後來日彙總均可使用。

事件 append-only 保存 before/after ledgerHash及來源，原始匯入 evidence 不變。人工PUT包含0或清空會永久接管；owner鎖與單一雙NULL條件UPDATE防競態。相同成功apply重試以完整request binding及afterHash驗證，來源消失不抹掉已保存費用。

## 審查與測試

- 獨立 Codex CLI spec第2輪通過；使用者新增部署授權後第3輪再次 PASS，0 findings。
- Arch第1輪1 major（repository非負判斷），已移service；第2輪 PASS，0 critical/major，1 minor為部署後schema.sql待pg_dump同步。
- Backend clean 183/183；arch修正後affected34/34，包含新增service值域測試。
- BFF17/17；frontend221/221及Vite build通過（既有大chunk警告）。
- 合成Postgres覆蓋：3檔未知費匯入、真SQL來源失敗隔離仍可commit、late唯一對帳、manual與補費兩種先後、double apply、immutable/owner/FK/unique、legacy保護。
- 本輪測試沒有使用真實交易MIME/帳戶，不執行正式成交匯入、補費、交易或寄信。部署與Git收尾由release程序另記錄。

## 正式部署驗收

2026-11-19 feature驗收已完成：Liquibase1.170.0、128表schema-only pg_dump與drift檢查通過；business/BFF healthy、frontend HTTP200、9090安全GET與JAR/bundle檢查通過。BFF-only origin恢復已核准ASUS域，business保持空；本機auto-load override排除Git，正式env/secret/TLS掛載保留。沒有正式匯入或補費。原arch唯一schema minor已解決；後續main部署依相同規範。
