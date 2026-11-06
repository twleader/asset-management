# [t558] 發布 API 請求核對文字與規則包身分

**對應 Requirements:** Requirements 171／187／193（唯讀 SRPP API、不可變計算與精確 authority 發布）
**前置任務:** t550；t557
**Liquibase changeset:** 無

## 背景

2026-11-06 午盤已讀 Swagger，臨時取數腳本仍用錯雷達／匯率／指數路徑，批次產生 27 個 404。使用者要求修正文字避免誤用。正確 GET 為 /api/public/trading-radar/stock、/api/public/exchange-rate/usd-twd、/api/public/market-index；不是系統缺 API 或 Swagger 漏列。

## 要做什麼

- [x] 558.1 唯一來源為 /private/tmp/srpp-api-route-wording-20261106 的核准文字：共用規格要求 actual method／server／完整 path／參數逐項對照當輪已讀 Swagger，值依同輪資料填，臨時腳本生成後重核所有不同範本才批次展開；404 留收據、對照路由後依原回退及時限處理，不猜變體或把錯路由當無資料。四個臺股入口引用同一共用要求，三條正誤路徑只作當前事故對照，未來依當輪 Swagger。只改文字，不新增執行器、攔截器、全域 L0、API、金融規則、權限或 35／40／60 分鐘時限；D-209／v2.58.0／模型 v3.22.0 保持。
- [x] 558.2 先完成獨立 spec 審查及閘門；以原工具重產 SRPP runtime／manifest並通過 Codex／Claude／model check，凍結最終 bundle後同步 embedded model／manifest與 generator 正式 policy document、兩份 goldens、insert-only registration SQL。model、公式、Swagger 與金融案例結果保持，goldens只容許 bundle 身分改變，獨立 diff-scoped 架構審查及 authority／calculator／registry 回歸。不得為通過 checker 放寬工具或 contract。
- [x] 558.3 等活動報告結束才改 runtime 來源／deployed entries／registry／正式服務。兩repo各feature commit→main no-ff merge→push→精確remote refs readback；同步實際 SRPP 工作區，保留無關 untracked outputs。主agent經 App 更新 Codex 既有早午 prompt且其他設定不變；Claude host skill同步、remote device未證實則如實記。登錄已合併 insert-only SQL、核四欄全文與舊 rows 不變；從乾淨main正式設定僅business build/recreate，保留BFF和其他容器，核effective env／mount及JAR authority bytes。無手動 producer／報告／Gmail／刷新／交易。只在合法台股交易日時窗09:05≤now<13:30做精確hash唯讀業務GET並留原HTTP回應，時窗外NOT_YET_EXECUTED；此診斷不冒充正式日報採用。

## 驗證

```bash
bash scripts/spec-check.sh
python3 -B scripts/check-srpp-report-authority.py --srpp-root /private/tmp/srpp-api-route-wording-20261106 --check
python3 -B scripts/generate-srpp-report-golden.py --srpp-root /private/tmp/srpp-api-route-wording-20261106 --check
python3 -B scripts/generate-srpp-long-short-exit-golden.py --srpp-root /private/tmp/srpp-api-route-wording-20261106 --check
ruby scripts/tests/srpp-policy-register-test.rb
ruby scripts/tests/srpp-report-policy-register-test.rb
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest=DecisionPolicyAuthorityTest,SrppReportModuleCalculatorTest,DailyDecisionCalculatorTest test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -DskipTests package
docker compose -p asset-management build business-services
docker compose -p asset-management up -d --no-deps --force-recreate business-services
```

SRPP 於其隔離工作區執行 daily_runtime_contract.py build/check、policy_bundle.py build/rules 及 daily Codex／Claude、simulation model check。正式部署前保留離線驗收；登錄部署遵循本任務固定發布順序。一般日曆／指數／報價 GET只驗連線；精確hash業務GET、完整正式採用及未來零誤用是不同證據。

## 完成報告

隔離來源與離線驗收已完成。bundle `e932a1bcd39ee3a44623bb4498b2fdb134705a57b6654df19a75a241b024757a`；D-209／v2.58.0／model v3.22.0 不變。SRPP runtime/shared/Swagger 59 tests、asset Java 37 tests、Ruby 13 tests／307 assertions 均通過，73 個同輸入及15個long-short goldens除bundle身分外深度相等；model／Swagger／formula bytes保持，authority gate與離線package／5份JAR authority bytes核對成功。獨立spec第二輪與arch均零finding；第一輪指出同文件404分類矛盾已修正，沒有新增攔截器。spec-check BLOCK0／CHECK0，差異檢查乾淨。

準備時原generator因隔離worktree缺ignored交易日曆而如實失敗；只從既有runtime複製完整同bytes日曆供隔離離線fixture，保存source/hash，未當live adoption或外抓。首次unittest模組呼叫用錯名稱/import路徑後已改用既有腳本直接執行，實際59測試全綠，未改測試或來源以掩蓋錯誤。

正式文字與authority發布已完成，精確hash業務API接受尚未完成，分開記錄如下。

- 兩repo feature commit／main no-ff merge／push與remote-ref精確讀回完成：SRPP feature `98d69f3f695bda81fb3a1e2097564c7f15a25dce`、main `0c462da6666ad944c316e2c5738e82178d028cf6`；asset feature `6d7a302cda8032793a27e61eccd8bb8dbc39b191`、正式部署程式碼main `1efaeb8cfb8b110f41aa5c323c866bf77526f0ed`。活動Codex午盤於13:02:18完成後才同步實際SRPP工作區、host入口／registry／容器；無關untracked完整保留。沒有重跑或改動既有報告。
- Codex早午兩prompt由App automation_update更新；精確全文讀回一致，僅prompt／updated_at變，其餘設定保持。Claude兩host技能與merged來源逐位元相同。verify_runtime_policy的host_policy_status=PASS，四entry與兩watchdog均PASS；Claude remote device仍REMOTE_SCHEDULE_UNVERIFIED，未冒稱遠端已更新或午盤完成。
- 新bundle從已合併SQL insert-only登錄，registry33→34，四欄全文與generator一致，原33列完整維持。登錄後立即精確hashGET留409 POLICY_UNSUPPORTED（舊JAR尚未切換），未當作接受成功。
- run-stack工具無gpt-5.6-terra可選，依規範繼承主模型。從乾淨asset main正式設定只build/recreate business-services，13:03:50啟動、13:04:01healthy；image `sha256:3b8b01dbb6364bb8c7a8223e8ca5689527d6f7cb60ecf308cc340a5040283c9b`、container `9546e5c213c7ca7756387b1dbc7ec4e277438304da09540e0c0354ed8a9e92b7`，JAR SHA `2058d31d92360fd784b2486ac23f532794e1d39e5e8728ceb17a776e6b970e34`。5份authority bytes與merged來源精確相同；47項effective env及3個bind mounts保持，其他8容器／BFF未重啟。後續只回填本Task不變runtime bytes，不為文件自身SHA重建。
- SRPP實機日曆／TWSE／台股報價三GET均200且內容有效，僅證明連線。合法時窗內，新hash部署後首次report-calculations GET回503 CONTEXT_NOT_READY；原回應保存。55秒有界唯讀觀察及下一自然分鐘單次DB查證未見新report package後停止等待，沒有盲重試GET或人工觸發producer。**business acceptance=NOT_COMPLETED**；不得以registry／health／一般GET冒充。完整正式日報採用仍NOT_YET_EXECUTED，未保證未來零誤用。

本機收據：`/Users/steven/Project/SRPP/data/output/diagnostics/2026-11-06-api-route-wording-release/`，含Git refs、離線驗證、registry前後／四欄比對、部署設定相等判定、JAR bytes、App prompt讀回來源註記、host／remote區分、一般GET及精確hash的原錯誤回應。未保存secret原值，沒有手動producer、報告、Gmail、刷新或券商動作。

本段僅回填既有審查及已發生驗收事實；依CLAUDE明文完成報告例外，不修改功能契約、sources、resources或規則hash，不把未完成業務接受改稱成功。
