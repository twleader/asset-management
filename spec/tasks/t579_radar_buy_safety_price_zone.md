# [t579] 交易雷達試單安全一致與溢價低賣修正

**對應 Requirements:** Requirement 203（一般買進與試單共用防追高、ETF溢價不當價格高檔）
**前置任務:** t575、t576、t577、t578
**Liquibase changeset:** 無

## 背景

main V25 實測：完成日KD15/14前期12/16、各軌結構止穩時，即使盤中KD86/80、完成日漲8%或FX95百分位，仍三軌TRIAL_BUY，同時risks卻寫不宜進場。52週位置0.20、KD40/45前期50/45、季線乖離-5%、完成日-0.5%、OSC負、MA20 BELOW、三軌結構轉弱時，僅將ETF溢價由缺值改3%，就由HOLD_CAUTION變三軌REDUCE_CANDIDATE。原因為試單優先返回，買方硬閘門在後；而highZone把溢價形成的OVERBOUGHT誤當價格高檔。

舊radar-v23-t575的完成K結構已由V25吸收；只沿用共用安全思路，不移植舊建構式、低檔EXIT、近期曾高檔取代當下高檔或理由字串搬移。使用者授權評估並整合有效部分，無價值部分刪除。先保存完整差異再清理；因host該目錄與Git metadata不可寫，本輪使用可寫的獨立main clone準備修正，不冒稱原worktree已刪。

## 要做什麼

- [ ] 579.1 在純規則引擎抽取共用買方安全判斷，傳入該軌timing與production/candidate追漲及正乖離門檻。否決：KD過熱、FX>=既有FX_EXPENSIVE_PCT、ETF premium>=ETF_PREMIUM_EXPENSIVE、完成日漲跌缺值/負值/>=追漲門檻、既有超買規則、基本面惡化。production完成日漲幅>=8%及正乖離>=15%封鎖；candidate各用本次傳入的對應門檻並以>=封鎖。使用BigDecimal比較邊界，測門檻前一個可表達值、等值與後一個可表達值；保留KD窄幅例外與EXTREME_OVERSOLD對中層正乖離的既有例外。score與權重不變。
- [ ] 579.2 actionFor兩種試單形態資格成立後套共用安全（完成日止跌／反彈收紅仍屬形態資格：長線允許0、反彈要求>0；缺值／負值維持不成立，不放寬止穩）；不通過立即HOLD_CAUTION/WAIT，risks列實際阻擋原因，不落入其他買進或賣出。共用安全也供一般buyGate，正常買進仍另須MA20/60 ABOVE及市場允許。試單RISK_OFF/均線例外、equity stale否決、V25完成K結構、後置evidence/SHORT分鐘/官方SMA20維持。
- [ ] 579.3 highZone不再直接信任OVERBOUGHT，改為week52>=0.75 OR kdHeatOf==OVERHEATED OR ma60Bias>=BIAS_HIGH。只改溢價不可從非高檔放行賣方。既有EXTREME_OVERSOLD先行優先權必須保留；測試當高季線乖離仍按既有時序判為EXTREME_OVERSOLD時，持有者仍為HOLD_CAUTION、未持有者仍為WAIT，不得由新highZone產生減碼或出場。既有profitTaking、高分續抱、未持有AVOID策略不改。
- [ ] 579.4 原完成日5%提醒改為明示計分風險，與買方硬否決門檻區分；不改計分公式。
- [ ] 579.5 RULE_VERSION與Fubon相容identity及所有現行範例/測試升TW_RULES_V26；OpenAPI 1.27.3->1.27.4，同步 scripts/tests/docker-external-api-openapi-test.rb 兩處版號斷言及其錯誤訊息、核對 unified renderer 測試，重產asset兩份Swagger mirror並check。SRPP身分須在正式部署前經受控發布鏈同步新hash、authority/registry；未完成記BLOCKED，不製造假hash。不改API路由/DB/排程/DTO/資料來源，不下單、不重跑報告、不寄信。
- [ ] 579.6 新增獨立安全回歸測試：兩條試單、持有/未持有、三軌、KD熱度/FX/ETFpremium/完成日漲幅/正乖離邊界，受阻不得升級普通買進或轉賣。candidate門檻確實沿用；production `8%`／`15%` 及 candidate 各自傳入的漲幅／乖離門檻都測前一個可表達值、等值、後一個可表達值。允許安全試單/RISK_OFF/窄幅例外，stale拒絕；價格低檔僅改溢價對照與價格高檔合法減碼不變，並驗證 EXTREME_OVERSOLD 對高正乖離的既有優先保護。保留既有V25測試。更動既有期望須逐案解釋，不為綠燈放寬安全。

## 驗證

```sh
bash scripts/spec-check.sh main
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true -Dtest='*Radar*,*Backtest*,*RuleParameters*,*OpenApi*,*TreasuryYield*,*Assembler*,*Structure*' test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true -Dtest='*TradingRadar*' test
ruby scripts/render-9090-openapi-docs.rb --check
ruby scripts/tests/docker-external-api-openapi-test.rb
ruby scripts/tests/unified-9090-openapi-renderer-test.rb
```

本輪修改 Swagger 身分，正式順序以 CLAUDE Task504／542 為準，取代一般先 runtime 驗收再 merge 的順序：發布前完成獨立 spec／arch 審查、測試與離線 build；在乾淨 managed SRPP feature worktree 依 renderer → sync → renderer check → sync check 更新 mirror／consumer metadata／runtime contract／manifest，核 bundle／runtime／authority 並產生 insert-only SQL；雙 repo feature commit、main no-ff merge、push、remote-ref readback；其後才執行 registry SQL 並精確回讀；最後依 run-stack 從乾淨 main 及原部署設定僅 build／force-recreate business-services，驗實際雷達版本與行為、JAR authority／Swagger 與 registry 同身分。不得在發布前替換正式容器，不以 healthy 代替功能驗收。合法交易日時窗的 SRPP report-calculations readback 尚未到時維持 NOT_YET_EXECUTED，不手動重跑報告、寄信或交易。權限受阻如實回報，不偽造發布完成。


## 完成報告

尚未實作。
