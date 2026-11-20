# Task 613：早報共同缺源 502 修復

2026-11-20 09:05 的 D-226 包已由背景排程產生；多個 D215/D226 gate 共缺 `topup-state`，產生重複 `blockingReasons`，BFF 原 unique 契約因此拒絕整包。修復限定 publisher wire adapter：新 shared base 與 consumer envelope 在 canonical/hash/insert 前深拷貝並依首次出現順序去重完整原因字串。每 gate receipts 與 BLOCKED/UNAVAILABLE、數量／限價原樣保留。calculator、formula manifest、政策 hash、Swagger 與既有 immutable 產物均未修改。

## 規格審查

第 1 輪僅發現規格驗證段引用尚未建立的計畫測試類名，已移除該精確類名並保留新增案例要求。第 2 輪獨立 Codex CLI 規格審查 PASS，critical/major/minor 均 0；實作完整 diff 的第 1 輪架構審查亦 PASS，critical/major/minor 均 0。前後機械檢查最終 BLOCK 0／CHECK 0。

## 驗證結果

- `SrppReportWireNormalizerTest`：2 passed，驗證 stable order、逐腿獨立、原 tree 不變、其他欄位／所有 gate receipts 不變、拒絕錯誤型別與空原因。
- `SrppReportRepositoryPostgresTest`：21 passed、1 skipped（既有 opt-in 真來源 size probe）。新增共同 topup-state 缺源案例使用真 calculator，證實原 D215／D226 BUY／SELL 均有重複原因、每 gate 仍 UNAVAILABLE；真 PostgreSQL publisher 兩 consumer 新包逐腿 unique、BLOCKED/零量不變、收據完全保留，重播冪等；新 build 產新包但舊 bytes 可由原 ID 讀回。
- `SrppReportMediumDipCalculatorTest`：18 passed。
- `SrppNamedRadarSellCalculatorTest`：20 passed。
- `SrppReportResponseValidatorTest`：26 passed，指定 backend `target/blocking-reason-wire` 為外部測試輸入目錄；原 BFF validator 接受兩 consumer 已發布包，重加相同原因並重簽 hash 仍拒絕。
- `git diff --check`：PASS。
- `check-srpp-report-authority.py --srpp-root /Users/steven/Project/SRPP --check`：PASS，原 bundle `15ca8f63f3adcfe8ce912c684d4d46d5924f98eb6d657cc605522118a0408092`。

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest=SrppReportWireNormalizerTest,SrppReportRepositoryPostgresTest,SrppReportMediumDipCalculatorTest,SrppNamedRadarSellCalculatorTest test
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml -Dtest=SrppReportResponseValidatorTest -Dsrpp.blockingReasonWire.dir=<absolute-repo>/backend/target/blocking-reason-wire test
```

正式 main 落地、registry parity、容器重建與原精確 hash API 回讀由發布工作接續；以上測試不代表今日漏報已補產、寄信或交易。
