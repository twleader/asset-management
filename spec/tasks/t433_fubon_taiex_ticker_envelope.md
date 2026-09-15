# [t433] 富邦 TAIEX tickers envelope 驗證修復

**對應 Requirements:** Requirement 151（只接受白名單 SDK catalog envelope，保留精確 TAIEX identity）
**前置任務:** 既有 token-protected TAIEX stream 與 DB-first ingestion path；本任務自足，不採用未驗證的 `IX0001` 設定或任何 scheduler 改動。
**Liquibase changeset:** 無；不得修改 schema、migration、DB/Redis writer 程式或金融資料。

## 背景

富邦官方 Indices WebSocket 文件以 `IR0001`、`INDEX`、`TWSE` 作為台股指數 subscription identity。2026-09-14 對 catalog 的受控單次唯讀檢查發現 SDK 回應是 `{exchange: "TWSE", type: "INDEX", data: [...]}` mapping：有 `data` list 185 rows、沒有 `is_success`，而官方 Intraday Tickers 文件的 row 只列 `symbol`／`name`。現有 verifier 將 exchange/type 錯誤要求在每列，因而把官方 envelope fail closed 成 `TAIEX_INDEX_SYMBOL_UNVERIFIED`；沒有進入 WebSocket 或任何帳務／交易方法。

## 要做什麼

- [x] **433.1 嚴格 envelope selector。** 只在 `SdkGateway.verify_taiex_index_symbol` 的 rows 選取邏輯加入三種明確 shape：top-level list、mapping 的 present `is_success=True` data list、mapping 的 absent `is_success` data list。accepted mapping 必有 exact outer `exchange="TWSE"` 與 `type="INDEX"`；presence-aware 判斷必區分缺欄與 present `None`／`0`／`1`／字串；`is_success=False` 保持 `INDEX_TICKERS_REJECTED`，其餘 present 值、缺／錯 outer metadata、absent data、非-list data、任何 attribute object（即使有 `data` list）與未知 shape 保持 `INDEX_TICKERS_INVALID`。禁止 recursive lookup、任意 mapping iteration 或寬鬆 fallback。
- [x] **433.2 exact identity 不變。** accepted mapping 的 data row 只驗證 deployment-selected `symbol`，不得從 row 讀取或補猜 outer `exchange`／`type`；legacy top-level list 才要求每列精確 `(symbol, exchange, type)`。絕不硬編 symbol；官方 `IR0001` 僅為 deployment configuration 範例。空 list、錯／缺 row symbol、legacy 錯／缺三元 identity 或 mapping 錯／缺 outer metadata皆 fail closed。不改 token、stream lifecycle、normalizer、其他 SDK path、routes、schema 或任何 DB/Redis／financial writer 程式；既有合格 stream event 的 DB-first／Redis projection 保持原樣。不得新增或呼叫券商交易 API。
- [x] **433.3 focused tests。** 在現有 gateway tests 加入只有 `symbol`／`name` row 的 no-is_success official mapping envelope success、legacy list 與 explicit success envelope regression，並測 false、present-invalid/absent `is_success`、缺／錯 outer metadata、attribute object（含 data list）拒絕、malformed envelopes、wrong symbol。stream tests 必證明 verification typed failure 不會建立 WebSocket。fixture 只可含最小 synthetic row，不得有真人帳號、token、真實擷取 catalog 或行情 payload。
- [x] **433.4 本機安全驗收。** 僅做無實帳戶的 adapter health 與 feature-disabled safety validation；不得呼叫 SDK、internal POST、SSE、manual sync 或任何券商 API，不得啟用 TAIEX stream、修改 active main flags 或重建 external-materials service。不得輸出 token、account、raw catalog、price 或 stream message。dividend、technical、stock push、realized gain、settlement flags 不變；catalog／typed failure 不得猜測其他 symbol 或產生 writer side effect。

## 驗證

```bash
bash scripts/spec-check.sh
pytest -q fubon-broker-service/tests/test_sdk_gateway.py \
  fubon-broker-service/tests/test_taiex_index_stream.py \
  fubon-broker-service/tests/test_app_routes.py
```

驗收不重建 Docker 服務、不開啟 SDK／SSE 或 internal route；只接受 focused tests 與 feature-disabled safety evidence。既有合格 stream event 仍走不變的 DB-first／Redis projection；不得觸發任何 order/accounting operation。

## 完成報告

（實作者回填：white-list shape tests、attribute object rejection、feature-disabled safety evidence，以及未變更的 flags／schema／writer。）
