# [t602] 官方交割證據推導成交費用

**對應 Requirements:** Requirement 214
**前置任務:** Task601
**Liquibase:** v1.169.0

### Task602：以官方交割與訂單證據推導新匯入成交費用

目標：只擴充Task601新成交匯入的fee/tax解析。來源不夠仍可匯入fee/tax NULL並明示原因；不修改既有交易、人工費用或真實id226，不做自動補歷史帳本，不改庫存/現金、下單或重跑同步。沒有新API權限、token、Gmail/OAuth、排程或同步呼叫；preview/apply只讀DB。公式不得從manual fee、可編輯notes或在途顯示金額反推。此修訂取代Task601一律fee/tax NULL，限以下完整條件。

1. 新增單一不可變fubon_settlement_receipt表（v1.169.0），儲存owner、sequence/id、status VERIFIED/UNAVAILABLE、observedAt、queryDate、accountFingerprint、canonical payload與hash、安全reason。成功payload為既有SDK SettlementBatch正規化完整資料（非完整銀行帳號、無憑證），有buyValue/buyFee/buyTax/buySettlement/sell*/total*及交易/交割日。此是可稽核原始來源snapshot具名正規化例外。只由既有非dry-run settlement sync在owner row lock重驗configured ACTIVE owner後append；成功讀取與既有在途write同transaction提交，失敗只append UNAVAILABLE安全結果，不改在途。沿現有owner scope；沒owner/config時無法capture不擴權。dryRun零receipt寫入。最新attempt失敗不可挑舊成功冒充最新。表immutable triggers、ownerFK、date/sequence index，migration安全冪等；部署後從runtime重產schema。
2. 新pure resolver經store讀該owner最新settlement attempt與該交易日最新既有FubonSrppSourceStore observation。兩者必VERIFIED、hash及schema合法、owner/日期一致、fingerprint精確相等，settlement明確accountBindingExplicit=true。不以登入owner單獨認定郵件帳戶，不以masked/From認證。官方order以日期/order_no/code/Buy及完整累計filled_qty/shares、filled_money/gross與已聲明真實報單精確唯一比對才橋接官方帳戶。filled_qty原wire是股數，unit只標交易單位不得再乘1000；order_no絕不是fill id。
3. 僅解析一筆NEW現買報單列，且當日官方orders恰一筆有成交，該筆status=50、active=false、filled_qty=after_qty=quantity>0、filled_money>0，所有其他orders均無成交亦非active（不接受部分成交/取消減量後完成或多筆成交order）。報單qty、gross精確等於官方累計qty、money，故不把同order部分回報當全部；相同order多列/多筆batch/重複報單不推導。沒有claim單封郵件完整，唯一性來自官方訂單與交割方向總額交叉核對。缺失/未知order欄位、最新UNAVAILABLE、fingerprint不同、ambiguous均NULL。
4. settlement有且僅有一個AVAILABLE day與tradeDate匹配；TWD、交割日在交易日之後；buyValue等於報單gross且為正，sellValue/sellFee/sellTax/sellSettlement皆明確0，buyTax與totalTax皆明確0、buyFee=totalFee>=0、buySettlement=totalSettlementAmount=-(buyValue+buyFee)，totalBsValue=buyValue。fee=abs(buySettlement)-gross，必與官方buyFee一致，tax=0。任何稅null、非零sell/未知其他調整/等式不符均NULL，不將差額全部假稱手續費。SDK_RANGE_3D_RETURNED_ROWS只代表返回日列，不當所有日或成交完整覆蓋；本日方向總額與完整官方order明細共同證明唯一匹配。
5. 時序：order capture不早於報單日期/time；settlement observedAt不早於order capture；兩者不能未來、來源日期一致。它們是歷史as-of evidence，不冒充現時行情或最終結算；UI標示『依官方交割與訂單資料推導（截至時間），非原始郵件提供』。若後來attempt/官方orders更新，未apply者必重預覽；不將日期內單筆ledger當完整性證據。沒有合法matching來源時具名ORDER_IDENTITY_UNVERIFIED/SETTLEMENT_UNAVAILABLE/SETTLEMENT_AMBIGUOUS/FEE_COMPONENTS_UNVERIFIED（或閉合等價碼），不假顯0。
6. preview result row新增feeProvenance={status:DERIVED|UNAVAILABLE|RECORDED,reason,source:'OFFICIAL_SETTLEMENT_ORDER_MATCH'|null,settlementReceiptId:null|id,orderSnapshotId:null|id,evidenceHash:null|string,asOf:null|instant}，fee與tax沿既有數值欄位。BFF仍只提供展示投影，前端render provenance與費稅、不做推導。unknown原因非交易CONFLICT的reason，不能因不可推導而禁止原有NULL匯入。
7. previewHash新增綁fee結果、resolver version、receipt/order identity/hash。apply在既有owner lock內重讀來源與重新解析、比對previewHash後才寫ledger與immutable provenance。settlement append和既有FubonSrppSourceWriter均使用相同owner lock，因此來源更新/apply互斥，不可在鎖外讀完後用舊結果提交。跨source防重等既有規則原樣。
8. 新增成交保存fee/tax與notes標示推導來源；在既有immutable broker_trade_report_import canonicalFacts保存版本化envelope（原MIME row＋feeProvenance/fee/tax），不修改歷史row。replay分別兼容Task601舊純row及新版envelope；先核原始MIME row、保存ledgerHash與當前ledger，成功則使用已保存fee/provenance，不能因目前receipt失敗/改變重新推導、改值或影響原ALREADY_IMPORTED。人工改費仍LEDGER_CHANGED；不覆寫、更正或回填。舊版無fee證據顯示RECORDED/unknown，不補造。
9. 測試全部合成：純resolver成功、零費、fee/tax null、sell非零、全部等式與日期/owner/fingerprint不符、部分成交/多order/多row/duplicate、量股單位、官方總額不等、來源latest失敗；preview零write/no vendor I/O，apply來源變化409及owner鎖競態；保存provenance/replay穩定、舊evidence兼容、manual改費衝突、既有id不回填、dryrun不寫、capture rollback/失敗attempt/immutable和migration重套。前端/BFF新wire顯示與214既有回歸。
10. 現在真實009826缺官方order receipt，應UNAVAILABLE且不修改已人工費用的既有交易。既有來源正常時才可啟用；驗收不得為製造成功重跑broker同步或新增權限。完整SDD/CLI spec與arch gate、測試、部署/merge由協調者依原流程完成。

## 驗證
- [ ] spec/check獨立審查
- [ ] resolver/capture/import/UI與合成測試
- [ ] arch/部署/收尾（協調者）
