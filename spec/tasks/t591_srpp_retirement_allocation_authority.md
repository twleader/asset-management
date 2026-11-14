# [t591] 退休配置與 VOO 暫時替代規則的 SRPP 權威同步

**對應 Requirements:** Requirement 210（同版退休配置、暫時替代條件、精確authority與診斷/執行能力界線）
**前置任務:** Task 589、590；主代理可提供候選來源供離線準備，發布必須凍結最終SRPP來源。
**Liquibase changeset:** 無

## 背景

使用者將VOO降至20%，分配0050/00881及台股高股息，並允許美元偏貴時以全部尚未投入VOO額度暫配重挫台股。資產端必須與SRPP同版，不能倒退已發布Task589/590之債券稅務、熊市15%首梯與D218止穩。已存在D219熊市15%決策，本次來源使用D220、policy 2.68.0／model 3.24.0、voo_bridge_d220／VOO_BRIDGE_D220。

## 要做什麼

1. 最終來源目標：VOO .20（監測.17–.23）、0050核心.12/上限.13、00881核心.0653/上限.0753；00713 .0433333333、0056/00878各.0325、00919 .0216666667，高股息合計.13/硬上限.15、個別監測帶原值乘1.3；股票.5653、債券.2647、現金.17保持。
2. 保留既有Task589/590，尤其熊市首梯.15、後續.20/.25/.30/.35、share .08/.12/.20/.25/.35、D202/D212門檻.15及互斥分流，不回退至舊.20首梯。候選來源只能在本feature離線同步，最後按主代理凍結hash全部重對齊。
3. VOO bridge須美元偏貴、TWSE及個別0050/00881重挫至少20%，可動用全部未投入VOO目標缺口各半暫配，獨立固定wave與歸屬、另設暫時symbol容量、核心不重標，總股票及資金硬上限保持；美元恢復後也須原台股合格賣出並交割才轉VOO。
4. 精確同步model_inputs.json、policy_manifest.json；用check-srpp-report-authority.py的expected_formal_document產生canonical d201-policy-document.json，用原generator產生report-same-input-golden.json與report-long-short-exit-golden.json與report-state-topup-golden.json（原generator22個state/component測例）、Ruby原stdout產生d201-registration.sql。另以既有SRPP scripts/export_settings_runtime.py同步srpp-simulator runtime/catalog/provenance；執行generate-srpp-formula-v3.py --check，若implementation bytes未變則formula不變，有合法變化才以原generator更新V3三份產物。保存來源hash、版本、artifact差異與產生結果，SQL只準備不執行。
5. state-topup generator以TransitionLedger.__new__建立的隔離fixture須明示無bridge的零bridge_cash/bridge_pending與bridge_enabled=False、voo_fx_wait_proxy=False，補足新earmark讀取所需狀態；不得將缺正式證據當成零bridge。此處是已有完整隔離fixture的合成零部位，只影響離線generator。檢查Java配置常數/validator，若既有model讀取已足夠不另改程式；若必要則只改政策取值，不新增bridge正式API量。D216測試中0050/00881核心target+.005袖額斷言更新為.125/.0703，仍保留個股/債券legacy與invalid policy阻擋。既有API阻擋、UNAVAILABLE與零量不可用Python診斷覆寫，bridge enabled不等於已金融採用。
6. 不新增HTTP/DTO/DB/行情/排程、不跑正式報告/下單/郵件。完成獨立spec審查後才同步，完成測試及獨立架構審查後提交主代理驗收；本階段禁止commit/merge/push、正式registry、部署。最終release另待主代理協調，且需精確hash業務API回讀。

- [ ] 591.1 完整spec與獨立本機Codex CLI審查。
- [ ] 591.2 候選與最終來源精確同步authority artifacts、檢查相關Java讀取。
- [ ] 591.3 權威檢查、golden與必要離線Java回歸，保存差異與能力界線。
- [ ] 591.4 獨立架構審查、提交主代理驗收，待後續發布協調。

## 驗證

執行完整scripts/spec-check.sh，三組golden generator --check、check-srpp-report-authority.py --srpp-root <最終來源> --check，執行既有SrppReportModuleCalculatorTest、SrppReportShortUsCalculatorTest、SrppReportStateTopupCalculatorTest、SrppSettingsFormalGatesTest、DecisionPolicyAuthorityTest及SrppSettingsAuthorityValuesTest相關離線回歸（不得觸發行情/DB刷新）。source policy_bundle check、model/manifest byte相等、canonical文件與SQL bundle hash一致；差異不得包含Task589/590回退。受控export後另在匯出runtime獨立PYTHONPATH匯入settings_worker，驗import dependency closure；若SRPP exporter漏helper，回報主代理修正固定白名單後重export，不手抄繞過manifest。API金融採用本階段未驗，bridge正式API正數能力未實作，不能標通過。完整diff交獨立Codex架構審查。

## 完成報告

待驗收回填。
