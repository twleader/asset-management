# Task598 Vanguard 發行人歷史配息來源

VOO與VT的配息歷史原先依Yahoo資料，金額為三位小數且缺發放日。本次加入同發行人官方JSON的歷史來源，保留原生精度、除息／支付日與來源hash；仍不宣稱未來45日公告完整。官方失敗沿既有Nasdaq/Yahoo後備，失敗觀測保留。

來源只限VOO=0968/CUSIP922908363與VT=3141/CUSIP922042742；fund identity／profile的IOV ticker與CUSIP綁定，USD來自封閉商品契約（官方JSON本身沒有currency欄）。HTTP固定同官方host、不跟隨redirect、每response 2MiB上限、15秒deadline。區間內split價基未知則拒絕、不自行反算。來源網址及原文SHA存既有snapshot provenance；真實原生fixture與SHA manifest在external-materials-service/src/test/resources/vanguard-dividends。

官方JSON各40筆自2016/12起；近兩年各8筆。最新VOO=1.8226、2026-09-28除息／09-30支付；VT=0.4084、09-18除息／09-22支付。沒有把範圍外2015事件當撤回，也不把recordDate猜成exDate。

## 驗證

- spec-check BLOCK0/CHECK0；spec round2 PASS 0/0/0（修正既有金額價基常數名稱）。
- targeted tests 70例全過：external46、backend24，詳tests.json。檢查原生fixture精度/日期、錯身份/幣別/schema/傳輸、split拒絕、來源fallback、重複/衝突、整日期組權威與舊歷史保留。
- arch round2 PASS 0/0/0（介面搬至service/port）；兩份最終審查隨附。
- 編譯後client真實連線官方HTTP成功，各回傳8筆PARTIAL且最新amount/ex/pay與官方一致。
- feature兩服務已重建/recreate；主代理獨立9090驗收：VOO/VT近兩年共16筆amount/ex/pay逐筆與官方原文一致，public各43筆且全部舊日期保留，來源含VANGUARD_ISSUER。BFF保持運作。main落地與正式部署仍待核對。

本次沒有修改金融API／DB schema、SRPP規則包與authority、未來公告完整門檻或US價格價基guard，沒有下單或寄信。GOOGL/MSFT未來UNRESOLVED與US股息價基限制仍另行揭露，不能因歷史配息補齊而宣稱解除。
