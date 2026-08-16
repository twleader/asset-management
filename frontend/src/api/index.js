import axios from 'axios'
import { ElMessage } from 'element-plus'

const api = axios.create({
  baseURL: '/api',
  timeout: 30000,
  headers: { 'Content-Type': 'application/json' },
  // Requirement 28：cookie session 需隨請求帶上
  withCredentials: true
})

api.interceptors.response.use(
  // Task 283：呼叫端傳 rawResponse:true 時回完整 response（需讀回應標頭，如 X-Dir-Export）；
  // 與既有的 skipAuthRedirect／skipErrorToast 同型的 per-call 旗標，預設行為完全不變。
  res => (res.config?.rawResponse ? res : res.data),
  err => {
    const status = err.response?.status
    const code = err.response?.data?.code

    // Requirement 28：未登入 → 整頁跳轉 Google 登入（/api/me 等可設 skipAuthRedirect 自行處理）
    if (status === 401 && !err.config?.skipAuthRedirect) {
      window.location.href = '/oauth2/authorization/google'
      return Promise.reject(err)
    }
    // Requirement 28：帳號待核准 / 停用 → 導向等待頁
    if (status === 403 && code === 'ACCOUNT_PENDING') {
      if (window.location.pathname !== '/pending') {
        window.location.href = '/pending'
      }
      return Promise.reject(err)
    }

    // 呼叫端可設 config.skipErrorToast 自行處理錯誤呈現（例：警示存檔改用 dialog 顯示而非頂部 toast）
    if (!err.config?.skipErrorToast) {
      const msg = err.response?.data?.detail || err.response?.data?.message || err.message || '請求失敗'
      ElMessage.error(msg)
    }
    return Promise.reject(err)
  }
)

// ===== 認證 / 使用者管理（Requirement 28） =====
export const authApi = {
  me: () => api.get('/me', { skipAuthRedirect: true, skipErrorToast: true }),
  // /logout 不在 /api 之下，故覆寫 baseURL
  logout: () => api.post('/logout', null, { baseURL: '' }),
  // 代看切換：userId 走 query param（BFF 在 TenantWebFilter 攔截寫 cookie，不進 controller）；
  // 省略 userId（或等於自己）= 清除代看、回到看自己
  impersonate: (userId) => api.post('/impersonate', null, { params: userId == null ? {} : { userId } })
}

export const userManagementApi = {
  list: () => api.get('/bff/user-management'),
  updateStatus: (id, status) => api.patch(`/bff/user-management/${id}/status`, { status }),
  updateRole: (id, role) => api.patch(`/bff/user-management/${id}/role`, { role })
}

/** 從 axios error 取後端訊息（ProblemDetail.detail 優先），供呼叫端自訂呈現。 */
export function apiErrorMessage(err, fallback = '操作失敗') {
  return err?.response?.data?.detail || err?.response?.data?.message || err?.message || fallback
}

// ===== Snapshots（共享 CRUD，給 store 使用；單一頁面的資料請走對應 bffApi.<page>） =====
export const snapshotApi = {
  getAll: () => api.get('/snapshots'),
  create: (data) => api.post('/snapshots', data),
  update: (id, data) => api.put(`/snapshots/${id}`, data),
  getHistory: () => api.get('/snapshots/history')
}

// ============================================================
// BFF：每個前端頁面對應一個獨立的 namespace
// 前端 view 一律走 bffApi.<page>.<method>，不直接呼叫共享 *Api
// ============================================================
export const bffApi = {
  // Dashboard
  dashboard: {
    summary: () => api.get('/bff/dashboard/summary'),
    snapshot: (id) => api.get(`/bff/dashboard/snapshot/${id}`),
    realtime: () => api.get('/bff/dashboard/realtime'),
    enrichDividendRates: () => api.post('/bff/dashboard/enrich-dividend-rates'),
    updateStockOrder: (snapshotId, orders) =>
      api.patch(`/bff/dashboard/snapshot/${snapshotId}/stock-order`, orders),
    twStockLookthrough: (snapshotId) =>
      api.get(`/bff/dashboard/tw-stock-lookthrough/${snapshotId}`),
    usStockLookthrough: (snapshotId) =>
      api.get(`/bff/dashboard/us-stock-lookthrough/${snapshotId}`),
    holdingsClassified: (snapshotId) =>
      api.get(`/bff/dashboard/holdings-classified/${snapshotId}`)
  },

  // SnapshotDetail
  snapshotDetail: {
    get: (id) => api.get(`/bff/snapshot-detail/${id}`),
    getBrokers: () => api.get('/bff/snapshot-detail/brokers'),
    update: (id, payload) => api.put(`/bff/snapshot-detail/${id}`, payload)
  },

  // SnapshotForm
  snapshotForm: {
    get: (id) => api.get(`/bff/snapshot-form/${id}`),
    prices: (date, stocks) =>
      api.post('/bff/snapshot-form/prices', stocks, { params: { date } }),
    realtime: () => api.get('/bff/snapshot-form/realtime'),
    exchangeRate: (date) =>
      api.get('/bff/snapshot-form/exchange-rate', { params: { date } }),
    getLookups: () => api.get('/bff/snapshot-form/lookups'),
    getFunds: (date) => api.get('/bff/snapshot-form/funds', { params: date ? { date } : {} }),
    refreshFundNav: () => api.post('/bff/snapshot-form/fund-nav/refresh')
  },

  // StockAnalysisDialog（跨 view 共用元件）
  stockAnalysis: {
    // Task 261：走勢圖上下兩個 pane 的完整資料。BFF 已並行取得股價與技術指標序列並以
    // tradingDate 聯集對齊，前端零計算、零 join（指標與觀察清單表格同源於 TechnicalIndicatorService）。
    getChartSeries: (code, market, start, end) =>
      api.get('/bff/stock-analysis/chart-series', { params: { code, market, start, end } }),
    getStockHistory: (code, market, start, end) =>
      api.get('/bff/stock-analysis/history/stock', { params: { code, market, start, end } }),
    getDividendHistory: (code, market, years = 10) =>
      api.get('/bff/stock-analysis/dividends', { params: { code, market, years } }),
    getEtfHoldings: (code, market) =>
      api.get('/bff/stock-analysis/etf-holdings', { params: { code, market } }),
    getIntradayTicks: (code, market, date) =>
      api.get('/bff/stock-analysis/intraday-ticks', { params: { code, market, ...(date && { date }) } }),
    // Task 136：走勢圖無歷史時即時觸發單檔 10 年回補（since 省略→後端預設 now−10y）。只補 stock_price_history，不入主檔。
    backfillStock: (code, market) =>
      api.post('/bff/stock-analysis/backfill-stock', null, { params: { code, market } })
  },

  // AssetHistory
  assetHistory: {
    getHistory:        () => api.get('/bff/asset-history'),
    recalcDividends:   () => api.post('/bff/asset-history/recalc-dividends'),
    deleteSnapshot:    (id) => api.delete(`/bff/asset-history/${id}`),
    exportExcel:       () => api.get('/bff/asset-history/export', { responseType: 'blob' }),
    getExportSchedule:    () => api.get('/bff/asset-history/export-schedule', { skipErrorToast: true }),
    updateExportSchedule: (data) => api.put('/bff/asset-history/export-schedule', data, { skipErrorToast: true }),
    runExportNow:         () => api.post('/bff/asset-history/export-schedule/run-now', null, { timeout: 60000, skipErrorToast: true }),
    browseExportDir:      (subpath = '') => api.get('/bff/asset-history/export-schedule/browse', { params: { subpath }, skipErrorToast: true }),
    // Google Drive 資料夾樹懶載入（Requirement 51 / Task 243）
    browseGdriveExportDir: (subpath = '') => api.get('/bff/asset-history/export-schedule/browse-gdrive', { params: { subpath }, skipErrorToast: true })
  },

  // RealizedGain
  realizedGain: {
    getAll:      () => api.get('/bff/realized-gain'),
    create:      (data) => api.post('/bff/realized-gain', data),
    update:      (id, data) => api.put(`/bff/realized-gain/${id}`, data),
    delete:      (id) => api.delete(`/bff/realized-gain/${id}`),
    lookupName:  (params) => api.get('/bff/realized-gain/lookup-name', { params }),  // 走本頁 BFF，轉呼同一支 business /api/stock-alerts/lookup-name
    exportExcel: () => api.get('/bff/realized-gain/export', { responseType: 'blob' }),
    getExportSchedule:    () => api.get('/bff/realized-gain/export/schedule', { skipErrorToast: true }),
    updateExportSchedule: (data) => api.put('/bff/realized-gain/export/schedule', data, { skipErrorToast: true }),
    runExportNow:         () => api.post('/bff/realized-gain/export/run-now', null, { timeout: 60000, skipErrorToast: true }),
    browseExportDir:      (subpath = '') => api.get('/bff/realized-gain/export/browse', { params: { subpath }, skipErrorToast: true }),
    // Google Drive 資料夾樹懶載入（Requirement 51 / Task 243）
    browseGdriveExportDir: (subpath = '') => api.get('/bff/realized-gain/export/browse-gdrive', { params: { subpath }, skipErrorToast: true })
  },

  // Transaction（交易紀錄：手動買賣流水帳，Requirement 49 / Task t237、t238）
  transaction: {
    list:        () => api.get('/bff/transaction'),
    lookupName:  (params) => api.get('/bff/transaction/lookup-name', { params, skipErrorToast: true }),  // 輸入代號自動帶股名，轉呼同一支 business /api/stock-alerts/lookup-name
    // Task 251：依交易日期查匯率（轉呼同一支 business /api/market-data/exchange-rate/on-date）
    // 查無牌告時 BFF 回 200 {}；5xx／連線失敗才會走到呼叫端的 catch（頁面自己有欄位內提示，故抑制全域 toast）
    exchangeRate: (date) => api.get('/bff/transaction/exchange-rate', { params: { date }, skipErrorToast: true }),
    create:      (data) => api.post('/bff/transaction', data),
    update:      (id, data) => api.put(`/bff/transaction/${id}`, data),
    remove:      (id) => api.delete(`/bff/transaction/${id}`),
    exportExcel: () => api.get('/bff/transaction/export', { responseType: 'blob', params: { _t: Date.now() } }),  // _t cache-bust：避免瀏覽器回舊匯出檔
    // 排程自動匯出（Task 255：每人可多筆；變更端點一律回「變更後的完整清單」，前端不做客戶端合併）
    listExportSchedules:  () => api.get('/bff/transaction/export/schedules', { skipErrorToast: true }),
    createExportSchedule: (data) => api.post('/bff/transaction/export/schedules', data, { skipErrorToast: true }),
    updateExportSchedule: (id, data) => api.put(`/bff/transaction/export/schedules/${id}`, data, { skipErrorToast: true }),
    deleteExportSchedule: (id) => api.delete(`/bff/transaction/export/schedules/${id}`, { skipErrorToast: true }),
    runExportNow:         (id) => api.post(`/bff/transaction/export/schedules/${id}/run-now`, null, { timeout: 60000, skipErrorToast: true }),
    browseExportDir:      (subpath = '') => api.get('/bff/transaction/export/browse', { params: { subpath }, skipErrorToast: true }),
    // Google Drive 資料夾樹懶載入（Requirement 51 / Task 243）
    browseGdriveExportDir: (subpath = '') => api.get('/bff/transaction/export/browse-gdrive', { params: { subpath }, skipErrorToast: true })
  },

  // CommodityPrice（公開資訊 → 油價金價）
  commodityPrice: {
    // 開頁載入：BFF 先 refresh 再回近十年三序列，故 timeout 放寬
    getHistory: () => api.get('/bff/commodity-price', { timeout: 120000 }),
    refresh: () => api.post('/bff/commodity-price/refresh', null, { timeout: 120000 }),
    // 盤中即時報價（Requirement 77 / Task 337）：每分鐘輪詢，失敗不得洗版錯誤 toast
    getLive: () => api.get('/bff/commodity-price/live', { skipErrorToast: true }),
    exportExcel: (start, end) =>
      api.get('/bff/commodity-price/export', {
        params: { ...(start && { start }), ...(end && { end }) },
        responseType: 'blob',
        timeout: 120000
      }),
    getExportSchedule:    () => api.get('/bff/commodity-price/export/schedule', { skipErrorToast: true }),
    updateExportSchedule: (data) => api.put('/bff/commodity-price/export/schedule', data, { skipErrorToast: true }),
    runExportNow:         () => api.post('/bff/commodity-price/export/run-now', null, { timeout: 60000, skipErrorToast: true }),
    browseExportDir:      (subpath = '') => api.get('/bff/commodity-price/export/browse', { params: { subpath }, skipErrorToast: true }),
    // Google Drive 資料夾樹懶載入（Requirement 51 / Task 243）
    browseGdriveExportDir: (subpath = '') => api.get('/bff/commodity-price/export/browse-gdrive', { params: { subpath }, skipErrorToast: true })
  },

  // ExchangeRate（公開資訊 → 台幣兌美元）
  exchangeRate: {
    getHistory: (currency = 'USD') =>
      api.get('/bff/exchange-rate', { params: { currency } }),
    backfill:   (currency = 'USD', since) =>
      api.post('/bff/exchange-rate/backfill', null, { params: { currency, ...(since && { since }) } }),
    exportExcel: (start, end, currency = 'USD') =>
      api.get('/bff/exchange-rate/export', {
        params: { currency, ...(start && { start }), ...(end && { end }) },
        responseType: 'blob',
        timeout: 120000
      }),
    getExportSchedule:    () => api.get('/bff/exchange-rate/export/schedule', { skipErrorToast: true }),
    updateExportSchedule: (data) => api.put('/bff/exchange-rate/export/schedule', data, { skipErrorToast: true }),
    runExportNow:         () => api.post('/bff/exchange-rate/export/run-now', null, { timeout: 60000, skipErrorToast: true }),
    browseExportDir:      (subpath = '') => api.get('/bff/exchange-rate/export/browse', { params: { subpath }, skipErrorToast: true }),
    // Google Drive 資料夾樹懶載入（Requirement 51 / Task 243）
    browseGdriveExportDir: (subpath = '') => api.get('/bff/exchange-rate/export/browse-gdrive', { params: { subpath }, skipErrorToast: true })
  },

  // GdpTwse (GDP + 台股大盤年度走勢)
  gdpTwse: {
    get: (years = 40) => api.get('/bff/gdp-twse', { params: { years } }),
    refresh: (years = 40) =>
      api.post('/bff/gdp-twse/refresh', null, { params: { years }, timeout: 240000 }),
    getIndexDaily: (market = 'TWSE', years = 10) =>
      api.get('/bff/gdp-twse/index-daily', { params: { market, years } }),
    refreshIndexDaily: (market = 'TWSE', years = 10) =>
      // 台股逐月 120 次序列呼叫，實測近 10 年約 250 秒以上；nginx 另有專屬 location 放寬 proxy_read_timeout（Task 288）
      api.post('/bff/gdp-twse/refresh-index-daily', null, { params: { market, years }, timeout: 360000 }),
    getIndexIntraday: (market = 'TWSE') =>
      api.get('/bff/gdp-twse/index-intraday', { params: { market } }),
    // 指數日線匯出與排程自動匯出（Requirement 45 / Task 216）
    exportExcel: (market = 'TWSE', start, end) =>
      api.get('/bff/gdp-twse/export', {
        params: { market, ...(start && { start }), ...(end && { end }) },
        responseType: 'blob',
        timeout: 120000
      }),
    getExportSchedule:    () => api.get('/bff/gdp-twse/export/schedule', { skipErrorToast: true }),
    updateExportSchedule: (data) => api.put('/bff/gdp-twse/export/schedule', data, { skipErrorToast: true }),
    runExportNow:         () => api.post('/bff/gdp-twse/export/run-now', null, { timeout: 60000, skipErrorToast: true }),
    browseExportDir:      (subpath = '') =>
      api.get('/bff/gdp-twse/export/browse', { params: { subpath }, skipErrorToast: true }),
    // Google Drive 資料夾樹懶載入（Requirement 51 / Task 243）
    browseGdriveExportDir: (subpath = '') =>
      api.get('/bff/gdp-twse/export/browse-gdrive', { params: { subpath }, skipErrorToast: true })
  },

  // PerformanceComparison（績效比較，Requirement 33）
  performanceComparison: {
    // 我的股票下拉清單（owner-scoped）：{ code, market, name }
    myStocks: () => api.get('/bff/performance-comparison/my-stocks'),
    // 報酬率疊圖：keys=['2330:台股',...]（≤3）、codes=['TWSE','SPX',...]（白名單）、range∈{3m,6m,1y,2y,5y}
    // dividend：報酬口徑，true=含息報酬（股利再投入）／false=純價格報酬，預設 true
    // 前端 join、後端 split，避開 axios 陣列序列化成 stocks[]= 讓 Spring @RequestParam String 收不到
    compare: (keys = [], codes = [], range = '1y', dividend = true) =>
      api.get('/bff/performance-comparison/compare', {
        params: { stocks: keys.join(','), benchmarks: codes.join(','), range, dividend: String(dividend) }
      })
  },

  // TodayMarketAnalysis（今日股市分析，Requirement 31）
  todayMarketAnalysis: {
    get: (historyLimit = 30) =>
      api.get('/bff/today-market-analysis', { params: { historyLimit } }),
    // 手動重新分析（限管理者）；thinking + 批次送出可能耗數十秒
    generate: () =>
      api.post('/bff/today-market-analysis/generate', null, { timeout: 200000 }),
    // 更新分析設定（模型／思考深度 effort／停用，限管理者），下次分析生效。payload 例：{ model } 或 { effort } 或 { enabled }
    updateSettings: (payload) =>
      api.put('/bff/today-market-analysis/settings', payload),
    // 分析結果寄送對象（Task 151）：沿用通知收件人，切換 per-recipient「接收每日股市分析」訂閱
    getRecipients: () => api.get('/bff/today-market-analysis/recipients'),
    toggleMarketAnalysis: (id) =>
      api.patch(`/bff/today-market-analysis/recipients/${id}/market-analysis`),
    // 分析寄送時間（Task 191）：管理者可增／刪／切換啟用；清單由聚合 GET 之 settings.sendTimes 帶回，mutation 回更新後清單
    addSendTime: (time) =>
      api.post('/bff/today-market-analysis/send-times', { time }),
    deleteSendTime: (id) =>
      api.delete(`/bff/today-market-analysis/send-times/${id}`),
    toggleSendTime: (id) =>
      api.patch(`/bff/today-market-analysis/send-times/${id}/active`)
  },

  // TradingRadar（今日交易雷達，Requirement 43）：純本地規則，零 AI API
  tradingRadar: {
    // 純讀重算；SSE 盤中自動更新走這支（不得改成下方的 refresh，否則會自我餵食迴圈）
    get: () => api.get('/bff/trading-radar'),
    // Task 249：手動「重新整理」＝先同步回補台股行情再重算。
    // 外部抓取需時，全域 timeout 30s 不夠用，必須 per-call 覆寫（鏈路上界為 nginx /api/ 的 60s）
    refresh: () => api.post('/bff/trading-radar/refresh', null, { timeout: 45000 }),
    // Task 283：改 POST（新增落檔／Drive 副作用）；rawResponse 供讀 X-Dir-Export 標頭。
    // params 必須放第三個引數——axios post 簽章是 (url, data, config)，放第二個會變成 body 而 query 消失。
    exportExcel: (from, to) =>
      api.post('/bff/trading-radar/export', null,
        { params: { from, to }, responseType: 'blob', rawResponse: true }),
    // 排程自動匯出到伺服器目錄（Requirement 48 追加 / Task 231）
    getExportTimes: () => api.get('/bff/trading-radar/export-schedule/times'),
    saveExportTimes: (times) => api.put('/bff/trading-radar/export-schedule/times', { times }),
    getExportSetting: () => api.get('/bff/trading-radar/export-schedule/setting'),
    // 收整包而非單一字串：漏送 gdrive* 會讓 Drive 設定存不進去（後端把 null 視為「不變更」）
    // payload: { outputSubpath, gdriveEnabled, gdriveSubpath }
    saveExportSetting: (payload) =>
      api.put('/bff/trading-radar/export-schedule/setting', payload),
    runExportNow: () => api.post('/bff/trading-radar/export-schedule/run-now'),
    browseExportDir: (subpath = '') =>
      api.get('/bff/trading-radar/export/browse', { params: { subpath }, skipErrorToast: true }),
    // Google Drive 資料夾樹懶載入（Requirement 51 / Task 243）
    browseGdriveExportDir: (subpath = '') =>
      api.get('/bff/trading-radar/export/browse-gdrive', { params: { subpath }, skipErrorToast: true }),
    getNotification: (stockCode, market) =>
      api.get(`/bff/trading-radar/notifications/${encodeURIComponent(stockCode)}`, { params: { market } }),
    updateNotification: (stockCode, market, payload) =>
      api.put(`/bff/trading-radar/notifications/${encodeURIComponent(stockCode)}`, payload, { params: { market } })
  },

  // AssetAllocationAdvice（資產配置建議，Requirement 32）
  portfolioAdvice: {
    // 一次聚合：{ latest, history, profile, settings, currentAllocation, projection }
    get: (historyLimit = 20) =>
      api.get('/bff/portfolio-advice', { params: { historyLimit } }),
    // 儲存理財條件（免重填）；payload：{ birthDate, preRetirementAnnualSalary, preRetirementAnnualExpense, retirementDate, retirementAnnualExpense, longTermCareAnnualExpense, longTermCareStartAge, 報酬率/通膨/勞保勞退/大筆花費, goals[], riskTolerance, expectedAnnualReturn }
    saveProfile: (payload) =>
      api.put('/bff/portfolio-advice/profile', payload),
    // 產生建議：local 檔位同步回終態（OK/FAILED/NOT_CONFIGURED）；hybrid/llm 立即回 PROCESSING，背景跑 Claude，前端輪詢至完成；payload 同 saveProfile，會一併儲存為 profile
    generate: (payload) =>
      api.post('/bff/portfolio-advice/generate', payload, { timeout: 60000 }),
    // 更新成本控管設定（模型／思考深度／web 搜尋，限管理者）；payload 例：{ model } 或 { effort } 或 { webSearchMaxUses }
    updateSettings: (payload) =>
      api.put('/bff/portfolio-advice/settings', payload)
  },

  // TradingCalendar
  tradingCalendar: {
    get:           (year) => api.get('/bff/trading-calendar', { params: year ? { year } : {} }),
    marketStatus: () => api.get('/bff/trading-calendar/market-status'),
    // 交易日曆匯出到指定路徑（Requirement 37）
    exportToDir:     (year, subpath) => api.post('/bff/trading-calendar/export', null, { params: { year, subpath }, timeout: 60000, skipErrorToast: true }),
    browseExportDir: (subpath = '') => api.get('/bff/trading-calendar/export/browse', { params: { subpath }, skipErrorToast: true }),
    // Google Drive 資料夾樹懶載入（Requirement 51 / Task 243）
    browseGdriveExportDir: (subpath = '') => api.get('/bff/trading-calendar/export/browse-gdrive', { params: { subpath }, skipErrorToast: true }),
    // 每日排程自動匯出（Task 185）
    getExportSchedule:    () => api.get('/bff/trading-calendar/export/schedule', { skipErrorToast: true }),
    updateExportSchedule: (data) => api.put('/bff/trading-calendar/export/schedule', data, { skipErrorToast: true })
  },

  // ScheduleList（排程列表，「公開資訊」分組，Requirement 36）
  scheduleList: {
    get: () => api.get('/bff/schedule-list')
  },

  // CrawlerData（爬蟲資訊查詢，「公開資訊」分組，Requirement 38）
  crawlerData: {
    // 查指定日期爬回的 news_headline；dateField: 'fetched'（爬取時間）｜'published'（資料日期）；category 選填
    query: (date, dateField = 'fetched', category) =>
      api.get('/bff/crawler-data', { params: { date, dateField, ...(category ? { category } : {}) } }),
    // 讀 NewsPoller 執行時間點清單 [{hour,minute,enabled}]
    getSchedule: () => api.get('/bff/crawler-data/schedule'),
    // 整批覆寫執行時間點（限管理者）
    saveSchedule: (times) => api.put('/bff/crawler-data/schedule', times),
    // 讀公開資訊 JSON 輸出路徑設定（Task 212；Task 241 起含 gdrive* 欄位與上次上傳狀態）
    // {crawlerKey,outputSubpath,baseDir,absolutePath,updatedAt,
    //  gdriveEnabled,gdriveSubpath,gdriveRemote,gdriveLastRunAt,gdriveLastStatus}
    getExportPath: () => api.get('/bff/crawler-data/export-path'),
    // 更新輸出子路徑＋Drive 設定（限管理者；不合法值回 400）
    // payload: { outputSubpath, gdriveEnabled, gdriveSubpath }
    // 收整包而非單一字串：漏送 gdrive* 會讓 Drive 設定存不進去（後端把 null 視為「不變更」）
    saveExportPath: (payload) => api.put('/bff/crawler-data/export-path', payload),
    // 本機資料夾樹懶載入（passthrough 至既有 /api/export-schedule/browse）
    browseExportDir: (subpath = '') =>
      api.get('/bff/crawler-data/export-path/browse', { params: { subpath } }),
    // Google Drive 資料夾樹懶載入（Task 241）。skipErrorToast：錯誤要顯示在 dialog 內，
    // 不與全域 toast 打架——remote 未設定時使用者需要看到具體原因，而非空樹
    browseGdriveExportDir: (subpath = '') =>
      api.get('/bff/crawler-data/export-path/browse-gdrive', { params: { subpath }, skipErrorToast: true }),
    // 手動匯出兩支（限管理者，Requirement 63 / Task 280）。
    // runExportNow＝只重產檔案（不抓取）；fetchAndRunExportNow＝完整跑一輪。
    // timeout 70000 而非其他頁 run-now 慣用的 60000：nginx /api/ 的 proxy_read_timeout 為 60s、
    // business 端 50s 就會回 RUNNING，把 axios 設在 nginx 之後才不會兩邊同時到期而分不清是誰斷的。
    // skipErrorToast：BUSY／RUNNING 都不是「失敗」，訊息由本頁自己呈現。
    runExportNow: () =>
      api.post('/bff/crawler-data/export/run-now', null, { timeout: 70000, skipErrorToast: true }),
    fetchAndRunExportNow: () =>
      api.post('/bff/crawler-data/export/fetch-and-run-now', null, { timeout: 70000, skipErrorToast: true })
  },

  // SnapshotList
  snapshotList: {
    getAll:      () => api.get('/bff/snapshot-list'),
    delete:      (id) => api.delete(`/bff/snapshot-list/${id}`),
    exportExcel: () => api.get('/bff/snapshot-list/export', { responseType: 'blob' })
  },

  // StockAlert — Task 253 起清單為「獨立條件（SINGLE）+ 複合條件群組（GROUP）」混合，
  // 群組另有 /groups 系列端點；全部走同一支萬用 passthrough BFF（/api/bff/stock-alert/** → /api/stock-alerts/**）
  stockAlert: {
    getAll:    () => api.get('/bff/stock-alert'),
    getRecipients: () => api.get('/bff/stock-alert/recipients'),   // 警示對話框「通知對象」可挑選清單（Task 125）
    // Task 253：payload 由 id 陣列改為 [{kind,id}] —— 獨立條件與群組的 id 分屬 stock_alert / stock_alert_group
    // 兩張表、值必然相撞，只送 id 後端無從判斷該把新的 displayOrder 寫進哪一張表
    reorder:   (orderItems) => api.put('/bff/stock-alert/reorder', orderItems),
    lookupName:(params) => api.get('/bff/stock-alert/lookup-name', { params }),
    lookupCode:(params) => api.get('/bff/stock-alert/lookup-code', { params }),
    // skipErrorToast：存檔錯誤（如重複條件）改由 StockAlertView.save() 以 dialog 呈現，不走頂部 toast
    create:    (data) => api.post('/bff/stock-alert', data, { skipErrorToast: true }),
    update:    (id, data) => api.put(`/bff/stock-alert/${id}`, data, { skipErrorToast: true }),
    toggleActive: (id) => api.patch(`/bff/stock-alert/${id}/active`),
    delete:    (id) => api.delete(`/bff/stock-alert/${id}`),
    // 複合條件群組（Task 253）。create/update 同樣帶 skipErrorToast：
    // 驗證錯誤（條件數不足 / 組內重複 / 與既有群組重複）要顯示在 dialog 內，讓使用者能就地修正
    createGroup: (data) => api.post('/bff/stock-alert/groups', data, { skipErrorToast: true }),
    updateGroup: (id, data) => api.put(`/bff/stock-alert/groups/${id}`, data, { skipErrorToast: true }),
    toggleGroupActive: (id) => api.patch(`/bff/stock-alert/groups/${id}/active`),
    deleteGroup: (id) => api.delete(`/bff/stock-alert/groups/${id}`),
    // 觸發即時匯出（Requirement 54 / Task 254）。事件驅動、非排程，故沒有執行時間欄位。
    // browse / browse-gdrive 由 StockAlertBffController 接走（不是萬用 route），最終指向
    // business 既有唯一那支 /api/export-schedule/browse{,-gdrive}
    getExportSetting:      () => api.get('/bff/stock-alert/export-setting', { skipErrorToast: true }),
    // skipErrorToast：Drive 開關被 403 擋下時，訊息要顯示在設定卡內讓使用者就地理解，不走頂部 toast
    saveExportSetting:     (data) => api.put('/bff/stock-alert/export-setting', data, { skipErrorToast: true }),
    runNowExport:          () => api.post('/bff/stock-alert/export-setting/run-now', {}, { skipErrorToast: true }),
    browseExportDir:       (subpath = '') => api.get('/bff/stock-alert/export-setting/browse', { params: { subpath }, skipErrorToast: true }),
    browseGdriveExportDir: (subpath = '') => api.get('/bff/stock-alert/export-setting/browse-gdrive', { params: { subpath }, skipErrorToast: true })
  },

  // WatchStock — v1.22 起改為 stock_alert 衍生 view，操作以 (stockCode, market) tuple
  // 移除觀察一律在「警示條件」頁刪掉該股票最後一筆 alert，不再有觀察清單級的 delete
  watchStock: {
    getAll:  () => api.get('/bff/watch-stock'),
    reorder: (orderedKeys) => api.put('/bff/watch-stock/order', orderedKeys),
    resendDigest: (market) => api.post('/bff/watch-stock/resend-digest', null, { params: { market } })
  },

  // BackupRestore
  backupRestore: {
    list:           () => api.get('/bff/backup-restore'),
    create:         () => api.post('/bff/backup-restore', null, { timeout: 120000 }),
    restore:        ({ folder, filename, confirmation }) =>
      api.post('/bff/backup-restore/restore', { folder, filename, confirmation }, { timeout: 180000 }),
    sync:           () => api.post('/bff/backup-restore/sync', null, { timeout: 60000 }),
    getSettings:    () => api.get('/bff/backup-restore/settings'),
    updateSettings: (data) => api.put('/bff/backup-restore/settings', data)
  },

  // Settings 頁面（CRUD 走各自 BFF）
  bankSettings: {
    getAll:    () => api.get('/bff/bank-settings'),
    create:    (data) => api.post('/bff/bank-settings', data),
    update:    (id, data) => api.put(`/bff/bank-settings/${id}`, data),
    setActive: (id, active) => api.patch(`/bff/bank-settings/${id}/active`, { active })
  },
  brokerSettings: {
    getAll:    () => api.get('/bff/broker-settings'),
    create:    (data) => api.post('/bff/broker-settings', data),
    update:    (id, data) => api.put(`/bff/broker-settings/${id}`, data),
    setActive: (id, active) => api.patch(`/bff/broker-settings/${id}/active`, { active })
  },
  depositTypeSettings: {
    getAll:    () => api.get('/bff/deposit-type-settings'),
    create:    (data) => api.post('/bff/deposit-type-settings', data),
    update:    (id, data) => api.put(`/bff/deposit-type-settings/${id}`, data),
    setActive: (id, active) => api.patch(`/bff/deposit-type-settings/${id}/active`, { active })
  },
  marketTypeSettings: {
    getAll:    () => api.get('/bff/market-type-settings'),
    create:    (data) => api.post('/bff/market-type-settings', data),
    update:    (id, data) => api.put(`/bff/market-type-settings/${id}`, data),
    setActive: (id, active) => api.patch(`/bff/market-type-settings/${id}/active`, { active })
  },
  transitFundTypeSettings: {
    getAll:    () => api.get('/bff/transit-fund-type-settings'),
    create:    (data) => api.post('/bff/transit-fund-type-settings', data),
    update:    (id, data) => api.put(`/bff/transit-fund-type-settings/${id}`, data),
    setActive: (id, active) => api.patch(`/bff/transit-fund-type-settings/${id}/active`, { active })
  },
  // 資產類別歸類（Requirement 25/26/27）— 三分類 + 股票風格 + 債券期別下拉 + stock 主檔逐檔歸類
  assetClassSettings: {
    getCategories:         () => api.get('/bff/asset-class-settings/categories'),
    getStockStyles:        () => api.get('/bff/asset-class-settings/stock-styles'),
    updateStockStyle:      (id, data) => api.put(`/bff/asset-class-settings/stock-styles/${id}`, data),
    getBondTerms:          () => api.get('/bff/asset-class-settings/bond-terms'),
    getSecurities:         () => api.get('/bff/asset-class-settings/securities'),
    setSecurityAssetClass: (data) => api.put('/bff/asset-class-settings/securities/asset-class', data),
    setSecurityStockStyle: (data) => api.put('/bff/asset-class-settings/securities/stock-style', data),
    setSecurityBondTerm:   (data) => api.put('/bff/asset-class-settings/securities/bond-term', data)
  },
  // 信託基金主檔（Requirement 19）— 走 FundBffRoutes 既有的 /api/funds/** passthrough
  fundSettings: {
    getAll:    () => api.get('/funds'),
    create:    (data) => api.post('/funds', data),
    update:    (fundCode, data) => api.put(`/funds/${fundCode}`, data),
    setActive: (fundCode, active) => api.patch(`/funds/${fundCode}/active`, { active }),
    // 銷售銀行下拉：走 fund-settings 自己的 BFF，與 snapshot-form 同源（business /api/settings/banks）
    getBankOptions: () => api.get('/bff/fund-settings/bank-options')
  },

  // 代繳設定（Requirement 22）— 分類 + 代繳記錄走同一支 BFF
  paymentAccountSettings: {
    getCategories:     () => api.get('/bff/payment-account-settings/categories'),
    createCategory:    (data) => api.post('/bff/payment-account-settings/categories', data),
    updateCategory:    (id, data) => api.put(`/bff/payment-account-settings/categories/${id}`, data),
    setCategoryActive: (id, active) => api.patch(`/bff/payment-account-settings/categories/${id}/active`, { active }),
    getAccounts:       () => api.get('/bff/payment-account-settings/accounts'),
    createAccount:     (data) => api.post('/bff/payment-account-settings/accounts', data),
    updateAccount:     (id, data) => api.put(`/bff/payment-account-settings/accounts/${id}`, data),
    deleteAccount:     (id) => api.delete(`/bff/payment-account-settings/accounts/${id}`)
  },

  // 警示觸發 Email 通知收件人（Requirement 23）
  notificationSettings: {
    getRecipients:    () => api.get('/bff/notification-settings/recipients'),
    createRecipient:  (data) => api.post('/bff/notification-settings/recipients', data),
    updateRecipient:  (id, data) => api.put(`/bff/notification-settings/recipients/${id}`, data),
    toggleActive:     (id) => api.patch(`/bff/notification-settings/recipients/${id}/active`),
    toggleCalendar:   (id) => api.patch(`/bff/notification-settings/recipients/${id}/calendar`),
    deleteRecipient:  (id) => api.delete(`/bff/notification-settings/recipients/${id}`)
  }
}

export default api
