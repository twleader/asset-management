# [t598] Vanguard VOO／VT 發行人歷史配息精度與發放日

**對應 Requirements:** Requirement 212（保留發行人原生配息金額、除息與發放日期）
**前置任務:** 無
**Liquibase changeset:** 無

## 背景

2026-11-17使用者要求補齊七檔配息。既有sync已補來源但VOO/VT歷史走Yahoo僅3位小數且缺pay；官方真實JSON各40筆自2016/12起，近兩年各8筆，VOO最新1.8226、2026-09-28除息09-30支付，VT0.4084、09-18除息09-22支付。舊2015事件不得因官方窗口缺席而撤回。

## 要做什麼

1. 新增external專用VanguardDividendSource，僅美股VOO=0968/CUSIP922908363/VOO.IV、VT=3141/CUSIP922042742/VT.IV封閉USD商品。實際官方base https://advisors.vanguard.com/investments/products/api/funds/；依序取 `{id}`身份、`{id}/profile`、`{id}/pricing/distributions?hasDistributionYield=false`。核對portId、fundIdentifier、isETF與CUSIP、IOV，不要求官方未提供的currency欄，若任一顯式currency非USD拒絕。HTTPS固定host、禁止redirect、2MiB body上限、timeout。
2. identity/profile失敗或任何分配schema／非正金額／日期錯誤／未知type拒絕整個issuer觀測，依舊Nasdaq/Yahoo fallback並保留issuer失敗理由。INC/CGST/CGLT/ROC按type/date元件識別、完全相同重複去重、衝突拒絕；BigDecimal不捨入、不用recordDate或reinvestPrice代替，payableDate必填且>=exDate。官方profile區間內split無價基證明時拒絕，不自行套split。只收from..to且不晚於NY today的已除息事件；空集只PARTIAL不當無配息證明。
3. 歷史結果provider VANGUARD_ISSUER，normalizerVersion2、NOMINAL_PER_EX_DATE（沿用實際既有常數）、PARTIAL、dateGroupsComplete=true、complete=false；URL包含各原文SHA256 fragment，以既有snapshot append/hash去重。未來upcoming、完整45日門檻、US_PRICE_BASIS guard、金融API與SRPP規則hash都不動。business resolver只增issuer來源順位；無撤回或刪除窗口外較早歷史。
4. 實作原生兩檔JSON fixture測8近兩年事件及full40日期組、精度、支付日、錯symbol/port/CUSIP/IOV/幣別/日期/type/transport、split拒絕、元件去重/衝突及fallback；既有canonical測權威與範圍外保留，未來不升完整。
5. targeted Maven測試、CLI架構審查，重建external與business，再實際sync/preview/apply/9090讀回比較官方8筆；依規範commit/no-ff merge/push後乾淨main確認正式部署，不改SRPP authority、無交易／寄信。

- [x] 598.1 specs機械檢查與獨立CLI spec review
- [x] 598.2 adapter與來源順位、fixtures和targeted tests
- [x] 598.3 架構審查、部署與真實readback驗收
- [ ] 598.4 main merge/push與正式部署核對

## 驗證

執行spec-check；新增adapter fixture/integration與canonical resolver測試、相關既有Dividend測試；CLI spec/arch審查。實機以官方最新與近兩年八筆逐amount/ex/pay對照9090 dividendHistory，確認舊事件保留、source為issuer、future未解封、純GET不外抓。

## 完成報告

spec與架構round2均PASS 0/0/0；70例targeted測試通過。feature兩服務已重建，實際sync與canonical採用成功；主代理獨立9090比對VOO/VT近兩年共16筆amount/ex/pay完全等於官方原文，public各43筆且所有舊日期保留。main落地與正式部署待收尾核對。
