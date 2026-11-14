# SRPP 情境模擬服務

僅供 Compose 私有網路的純計算服務，不對 host 公布 port。來源由 SRPP 的受控
`export_settings_runtime.py` 逐位元匯出，啟動時核對來源 manifest 與 bundle 身分。
不掛載主機 SRPP 目錄、個人歷史、秘密、Git 或 Docker socket。

- `GET /internal/health`：服務與已載入來源健康狀態。
- `GET /internal/srpp/catalog`：有限欄位規約與當前正式來源值。
- `POST /internal/srpp/validate`：閉合型別、範圍、跨欄與內容雜湊驗證。
- `POST /internal/srpp/simulate`：凍結資產下的 baseline／scenario 月頻 Monte Carlo。

`SRPP_SCENARIO_CASHFLOW_V3` 使用正式同版的前瞻均值、共用 Student-t／薪資／年金／分齡
支出純函式。股票 18% 與債券 8% 年波動率是可調的示範規劃假設，不是本日觀測或正式模型校準。
輸出列出未建模範圍；不等同完整正式模型重跑，不呼叫 AI／行情／券商，不寫正式輸出。
資產分位數為排除緊急預備金後的2026-08-31實質可支用元；給付與薪資時程另外明示名目金額，結果money採兩位小數字串。

目錄V2共76欄：生日、勞保勞退日期／實收覆寫、退休前月薪／調薪／獎金、可增刪的大額支出、緊急備用金與生活cash準備、額外牛熊配置戰術，以及16個短線策略草稿。短線參數標示NOT_MODELED，不改退休模型數值或正式雷達；不是即時進出場計算器。

緊急額及生活費準備皆來自原有資產，不另增新錢；月末現金回補、部位再平衡、MonthlySignals與選填五批ladder保留有界稽核；同版D-219熊市五批為15／20／25／30／35%，原8／12／20／25／35%窗口預算，戰術與生活分支明示讀來源15%訊號。月頻均線／合成總報酬與交易時段映射只是規劃代理，不含逐檔VOO:0050容量、售債排名、真雷達採用閘門、交易成本或交割。保費與未公告級距為既有代理；V3新增單身薪資／獎金的年度累進稅、預扣抵減與次年5月補退，未含完整申報戶投資所得／股利／AMT結算；過往年金CPI缺口以固定基準通膨代理並明示來源。退休當日為假設下可支用資產預估，末月未發薪資另列應收，自提不提前加回。

正式發布要求由業務服務保存；host operator 以 SRPP 的 `settings_release.py validate/apply`
處理有限來源變更，沿原雙專案測試、Git、registry、部署與精確身份回讀流程。
`prepare-receipt` 只準備可驗證的部署收據，不使待發布要求變成 ACTIVE。

主機維護工具請使用獨立 Python 環境，不假設系統 Python 已安裝依賴：

```sh
python3 -m venv /tmp/srpp-release-env
/tmp/srpp-release-env/bin/python -m pip install -r /absolute/SRPP/requirements.txt
/tmp/srpp-release-env/bin/python /absolute/SRPP/scripts/settings_release.py --help
```

將 `/absolute/SRPP` 換成核對過的 managed feature worktree。正式重跑與發布都應使用這份環境的 interpreter；不把測試虛擬環境或市場／個人資料納入 Git。
