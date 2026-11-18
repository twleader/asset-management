# [t606] D-224 退休配置與熊市資金權威同步

**對應 Requirements:** Requirement 210（退休配置權威同步）
**前置任務:** t603、t604、t605
**Liquibase changeset:** 無

## 背景

D-224 調整長期配置與 D-222 的熊市資金。舊報告從合計定存推算的容量不能證明 D-224 專用額度。

## 要做什麼

- [ ] 同步 SRPP 核准的完整 model、manifest、正式配置、公式產物、測試資料與模擬服務執行來源。V2 文件維持既有封閉格式，熊市欄位取自同一精確 model。
- [ ] VOO 21.97%、0050 13%、00881 6.53% 為準備金市場選檔前基準；00719B 17%、0056／00878／00919 各 3%、00713 4.5%、009826 2%。維持 D-222 通膨分帳與市場選檔。
- [ ] 熊市後非緊急債券 ETF 至少占總資產 5%，緊急債券 ETF 不計入分子。經歸屬核對，緊急 ETF 可累計動用最多 40 萬元，生活定存可累計動用一個通膨調整後的年度額度。實際動用額另扣先前已確認動用和有效承諾；緊急定存、另一年生活定存和活存保留。
- [ ] 現行報告凍結來源未提供完整、已歸屬的動用及承諾帳本。D-224 熊市資金與梯次正容量標為具名未評估。不得從合計定存推定可動用，不得將來源缺口改為已驗證的 0 容量；歷史政策、其他模式與 D-223 門檻保持適用。
- [ ] 既有來源可支撐時報告揭露 5% 底線與非緊急債券 ETF 容量；缺分帳時維持未知。沿用既有封閉 API；無交易權限、手動 producer 或報告重跑。

驗證涵蓋新政策缺帳本時無正熊市額度、歷史政策相容、來源同步與發布後唯讀 API。

- [ ] 以同一模型的 `execution_policy.dynamic_allocation_policy_d141.aggregate_equity_hard_cap_enabled=false` 移除報告高股息補足、熊市梯次、輪替資金部署的全股票獨立硬上限。保留個別與群組上限、現金與債券底線、具名方案額度、風險和交割。歷史缺旗標採原值，型別不正確不放寬。測試核對跨舊上限仍受其他餘額限制。

## 驗證

凍結的 SRPP 來源為 `/tmp/srpp-d224`。同步 `backend/src/main/resources/srpp/decision/{model_inputs,policy_manifest}.json`、`backend/src/main/resources/srpp/report/d201-*`、V3 公式清單及鏡像、相關 test goldens、`srpp-simulator/runtime`。模型的 D-224 cumulative 計算扣除專用分帳中已動用與承諾，資產端 應將缺少這些資料的資金欄位列為未知。

```bash
bash scripts/spec-check.sh
python3 scripts/check-srpp-report-authority.py --srpp-root /tmp/srpp-d224 --check
python3 scripts/generate-srpp-formula-v3.py --srpp-root /tmp/srpp-d224 --check
python3 -B /tmp/srpp-d224/scripts/export_settings_runtime.py --destination "$PWD/srpp-simulator/runtime" --check
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest=SrppReportModuleCalculatorTest,SrppReportStateTopupCalculatorTest,SrppReserveAllocationTest,SrppPolicyDocumentTest test
```

發布操作由協調者接手，兩專案 main 遠端核對後，由已合併資產 main 插入 registry 並精確回讀完整政策與公式內容；立即以精確規則及 Swagger hash 呼叫 9090 業務 GET 並留收據。再從正式 asset-management main 以其現有設定執行：

```bash
docker compose -p asset-management build business-services bff srpp-simulator
docker compose -p asset-management up -d --no-deps --force-recreate business-services bff srpp-simulator
curl --fail --silent 'http://127.0.0.1:9090/api/public/market-index?market=TWSE&range=1m'
```

逐項核對容器健康、JAR 規則原文與 registry，重做精確 hash 9090 業務 GET，保存原始回應。合法日報採用未經驗證時標示尚待確認。

## 完成報告

待實作與驗證後回填。
