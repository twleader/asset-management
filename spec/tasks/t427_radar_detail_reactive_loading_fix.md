# [t427] 交易雷達單檔明細 reactive loading 狀態修正

**對應 Requirements:** Requirement 148（交易雷達 BFF 列表／明細分流）
**前置任務:** t426
**Liquibase changeset:** 無

## 背景

交易雷達已在表格展開時才向 authenticated BFF 請求單一 `(market, stockCode)` 明細。畫面於 2026-09-12 23:20 的 2330 與 00713 請求均已回 HTTP 200，但截圖建立後面板仍停在「正在載入此股票的明細」。

根因在 Vue reactive map 的 identity：`detailStates[key]` 寫入 raw object 後，讀回會是 reactive proxy。既有程式把 assignment expression 回傳的 raw object 存成 `state`，再以 `detailStates[key] === state` 守門，嚴格比較永遠失敗。因此成功 response 被當作 stale 而丟棄，finally 也不會清除 map 中的 loading flag。

正確行為是保留 generation、exact pair、row 與 expanded state 的 stale-response 防護，同時讓 current request 的 stored reactive state 能通過 identity guard；成功 response 必 render 明細而不是永久 loading。

## 要做什麼

- [ ] **427.1 Vue state identity。** 在 `TradingRadarView.vue` 先將 `{ loading: true, loaded: false, error: '', generation }` 寫入 `detailStates[key]`，再從 `detailStates[key]` 讀回並保存為 request-local `state`。不得將 assignment expression 的 raw object 作為 guard identity，也不得用 `toRaw`、移除 generation guard 或放寬 stale-response 判定。
- [ ] **427.2 completion 行為。** current request 成功且 `response.stock` 存在時，僅 merge 該 row、設 stored `state.loaded = true`，並在 finally 將同一 stored state 的 `loading` 清為 false。錯誤回應仍呈現既有 error；list replacement、折疊或新 request 導致 state 不再 current 時，舊回應不可 merge 新 row。
- [ ] **427.3 回歸測試。** 在既有 frontend Node tests 加入 Vue reactive identity fixture，證明 stored map value 與 request-local state 可相等、成功 path 從 loading 轉為 loaded，並以 source assertion 防止重回 raw assignment-expression pattern。既有 Task 426 的 list-first、single-detail、SSE row-only patch、generation race 契約均不得變更。

## 驗證

```bash
cd frontend && npm ci && npm test -- --run
docker compose -p asset-management build frontend
docker compose -p asset-management up -d --no-deps --force-recreate frontend
curl -sI http://localhost/ | head -1
docker exec asset-frontend sh -lc 'grep -l "正在載入此股票的明細" /usr/share/nginx/html/assets/TradingRadarView-*.js'
```

容器驗證必由 run-stack 流程執行。feature 尚未 merge 時，先逐項比對 feature worktree 與 main worktree 的 gitignored `.env`，確認直接影響 frontend TLS／OAuth 的變數一致後，才從 feature worktree build/recreate `frontend`。完成驗收、commit、`--no-ff` merge 與 push 後，必在乾淨且已追平 `origin/main` 的 main worktree 再 rebuild/recreate 同一 frontend service，確認 running container 的 Compose working directory 與 image 均由 main 產生；不得以 feature worktree 的 image 當成 main 已收斂的證據。

在既有登入瀏覽器中開啟「今日交易雷達」，展開一檔台股；預期只送出該檔 `/api/bff/trading-radar/stock`，HTTP 200 後 loading 提示消失並呈現該檔明細。再次展開其他列不得刷新 root list；SSE 只更新允許的價格相關欄位。

## 完成報告

（實作者完成後回填實際修改檔案、測試與 Docker 驗證結果。）
