# t589／t590 部署驗收紀錄（2026-11-14）

已發布 D-219：債券觀察選項補為目前五檔、薪資情境改用年度累進稅、正式熊市五批與月頻情境同步自回檔 15% 起。LOCAL 與 AI 兩種模式保留，AI 只解釋相同不可變試算結果。

## 實際行為

- 可選 00679B、00697B、00719B、00751B、00865B。原正式預設仍是 00679B／00751B／00697B；新增選項不會自動正式採用。實機草稿可保存五檔，新兩檔純讀明細皆 HTTP 200；非交易日資料不足時明示 WAITING_DATA／WAIT。
- V3 將全年薪資與獎金扣除適用免稅額、扣除額及勞退自提後逐級計稅；20% 為可能的邊際級距，不以整筆薪資乘 20%。薪資預扣僅是預繳，次年五月補退差額與期末未付稅負分開處理。結果可展開年度、各級距及扣除明細。範圍是本人薪資、單身居民規劃，並非完整全戶／投資所得申報；未公告未來年度沿 115 年度作規劃代理。[財政部 115 年度公告](https://www.mof.gov.tw/singlehtml/384fb3077bb349ea973e7fc6f13b6974?cntId=34b463dc8f1b49f29d440d92a6fd5139)。
- 正式熊市五梯為 15／20／25／30／35%，份額維持 8／12／20／25／35%。D202／D212 與月頻呼叫端使用同版 15% 來源；ReserveStrategy 真正套用來源梯表。原 D212 修正窗口與熊市窗口維持互斥分流，不新增可相加預算。資金、風險、止穩、五交易日冷卻、順序與各項上限仍生效。

## 可覆核結果

| 驗證 | 結果 |
| --- | --- |
| Backend／BFF／Python／Frontend | 307／9／117／186，共 619 項通過 |
| 前端正式編譯、規格機械檢查 | 通過，BLOCK 0／CHECK 0 |
| 獨立規格／架構審查 | 通過，critical／major／minor 均 0 |
| 原正式年度稅 64 個 parity 案例 | 精確一致 |
| 原 V1／V2、V3 金額／日期／結果 hash | 回歸通過；V3 HTTP 含 23 位金額精度 |
| 編譯後 Docker 前端瀏覽器驗收 | 五檔、五梯、稅表／明細、舊結果、行動版通過 |
| 純 worker 合成試算 | 1,000 路徑、seed 7；隔離重跑一致，正式部署結果亦精確一致 |
| 正式 owner 安全草稿／純讀 API | 19 項通過，非法代號、版本衝突及權限拒絕正常 |
| Registry | insert-only 新增 1 列，舊 54 列不變，四欄精確回讀 |
| 正式四服務部署 | 乾淨 main 原 .env 重建 image／recreate，JAR 六份來源一致，原其餘六容器不變 |
| 公開 HTTPS／身分邊界 | 憑證信任通過；未登入／偽造身分 401、9090 無私人設定路徑 |
| SRPP 實機 9090 | 日曆／指數／報價內容驗證通過；policy／runtime source check 通過 |

瀏覽器圖使用合成資料及攔截的登入／API 回應，搭配實際 BFF 投影與編譯前端；不是 Google OIDC 完整登入驗收。正式 API 驗收另使用 owner 範圍的安全草稿與既有純讀明細。驗收草稿 ce71f7bd-1bf9-4815-800d-347decbb38b6 未正式套用，既有草稿未修改。

## 發布身份

- Asset code main：`5c19bf5dc0d7b03ba40031fd8917f1a55d0825b7`
- SRPP main：`9c0a199e9cfdc656533c239cd8ebab7ecde2088d`
- D-219，policy 2.67.0／model 3.23.0／engine SRPP_SCENARIO_CASHFLOW_V3。
- Bundle：`94239e74779f360502f358772bdd85b374dc10113c6443e370d2c8fb2146c4c4`
- Model SHA：`48c87397b59b5594f623e993363083b5585ba9c2eebfa100551815f068468484`
- Swagger 1.28.2 SHA：`6e2ada03c01a5e3bcbde24be7c6fc7df04e30bf76dd76766d5215b1839c820c0`

兩個專案皆已 feature commit → main no-ff merge → push，遠端精確回讀後才執行登錄與部署。實際排程 SRPP 工作區亦安全同步至同版，既有未追蹤檔案保留。本紀錄及任務勾選屬後續純文件收尾，不改已測試、部署的程式或規則身份，無須因文件提交重建 image。

## 尚未執行及適用範圍

今天為非交易日，指定精確新 bundle 呼叫 daily-context 回覆 **409 NON_TRADING_DAY**。因此正式報表自然採用記為 **NOT_YET_EXECUTED**，`businessPackageAccepted=false`；登錄、健康檢查、連通性不能替代正式金融計算驗收。未呼叫報表產生器、下單或寄信。

正式使用者資產的 LOCAL 入口可能經既有資產刷新而觸發外部來源，本次未呼叫；改驗實際部署 worker 的合成資產試算。未進行真實 AI API 呼叫或 Google OIDC 全流程；模式與權限回歸測試保留。月頻 aggregate 仍是情境近似，不宣稱等同逐日逐檔正式 60%／70% 股票上限回測。

## 證據索引

`release.json` 為總結。`final-*-suite.json`、`frontend-final-*.log` 為測試／編譯；`codex-*-review-*.md` 為獨立審查；`ui-proof.json` 與兩張 synthetic 圖為介面驗收；`git-landing-proof.json`、`registry-release-proof.json`、`formal-deployment-proof.json`、`live-*.json`、`9090-*.json` 為發布及實機證據。`SHA256SUMS` 列出本目錄證據檔案（不含自身）以便核對。原始環境值、憑證及私人資產未收入。
