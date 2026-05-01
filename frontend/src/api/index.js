import axios from 'axios'
import { ElMessage } from 'element-plus'

const api = axios.create({
  baseURL: '/api',
  timeout: 30000,
  headers: { 'Content-Type': 'application/json' }
})

api.interceptors.response.use(
  res => res.data,
  err => {
    const msg = err.response?.data?.detail || err.response?.data?.message || err.message || '請求失敗'
    ElMessage.error(msg)
    return Promise.reject(err)
  }
)

// ===== Snapshots（共享 CRUD，給 store 使用；單一頁面的資料請走對應 bffApi.<page>） =====
export const snapshotApi = {
  getAll: () => api.get('/snapshots'),
  getDetail: (id) => api.get(`/snapshots/${id}`),
  create: (data) => api.post('/snapshots', data),
  update: (id, data) => api.put(`/snapshots/${id}`, data),
  delete: (id) => api.delete(`/snapshots/${id}`),
  getHistory: () => api.get('/snapshots/history'),
  updateDividendRates: (id, rates) => api.patch(`/snapshots/${id}/dividend-rates`, rates),
  updateStockOrder: (id, orders) => api.patch(`/snapshots/${id}/stock-order`, orders),
  enrichAllDividendRates: () => api.post('/snapshots/enrich-all-dividend-rates'),
  recalcDividends: () => api.post('/snapshots/recalc-dividends'),
  exportExcel: () => api.get('/snapshots/export', { responseType: 'blob' })
}

// ===== Institution Settings（共享 lookups，下拉用；CRUD 請走 bffApi.<xxx>Settings） =====
export const institutionApi = {
  getAllBanks:    () => api.get('/settings/banks'),
  getAllBrokers:    () => api.get('/settings/brokers'),
  getAllDepositTypes:    () => api.get('/settings/deposit-types'),
  getAllMarketTypes:    () => api.get('/settings/market-types'),
  getAllTransitFundTypes:    () => api.get('/settings/transit-fund-types'),
  getActiveTransitFundTypes: () => api.get('/settings/transit-fund-types/active')
}

// ===== Market Data (共享 lookups for components like StockAnalysisDialog) =====
export const marketDataApi = {
  getStockHistory: (code, market, start, end) =>
    api.get('/market-data/history/stock', { params: { code, market, start, end } }),
  getDividendHistory: (code, market, years = 10) =>
    api.get('/market-data/dividends', { params: { code, market, years } }),
  getEtfHoldings: (code, market) =>
    api.get('/market-data/etf-holdings', { params: { code, market } })
}

// ===== Realized Gains（store 用） =====
export const gainApi = {
  getAll: () => api.get('/realized-gains'),
  create: (data) => api.post('/realized-gains', data),
  update: (id, data) => api.put(`/realized-gains/${id}`, data),
  delete: (id) => api.delete(`/realized-gains/${id}`),
  exportExcel: () => api.get('/realized-gains/export', { responseType: 'blob' })
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
      api.patch(`/bff/dashboard/snapshot/${snapshotId}/stock-order`, orders)
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
    prices: (date, stocks, historicalOnly = false) =>
      api.post('/bff/snapshot-form/prices', stocks, {
        params: historicalOnly ? { date, historicalOnly: true } : { date }
      }),
    realtime: () => api.get('/bff/snapshot-form/realtime'),
    exchangeRate: (date) =>
      api.get('/bff/snapshot-form/exchange-rate', { params: { date } }),
    getLookups: () => api.get('/bff/snapshot-form/lookups')
  },

  // StockAnalysisDialog（跨 view 共用元件）
  stockAnalysis: {
    getStockHistory: (code, market, start, end) =>
      api.get('/bff/stock-analysis/history/stock', { params: { code, market, start, end } }),
    getDividendHistory: (code, market, years = 10) =>
      api.get('/bff/stock-analysis/dividends', { params: { code, market, years } }),
    getEtfHoldings: (code, market) =>
      api.get('/bff/stock-analysis/etf-holdings', { params: { code, market } })
  },

  // AssetHistory
  assetHistory: {
    getHistory:        () => api.get('/bff/asset-history'),
    recalcDividends:   () => api.post('/bff/asset-history/recalc-dividends'),
    deleteSnapshot:    (id) => api.delete(`/bff/asset-history/${id}`),
    exportExcel:       () => api.get('/bff/asset-history/export', { responseType: 'blob' })
  },

  // RealizedGain
  realizedGain: {
    getAll:      () => api.get('/bff/realized-gain'),
    create:      (data) => api.post('/bff/realized-gain', data),
    update:      (id, data) => api.put(`/bff/realized-gain/${id}`, data),
    delete:      (id) => api.delete(`/bff/realized-gain/${id}`),
    exportExcel: () => api.get('/bff/realized-gain/export', { responseType: 'blob' })
  },

  // ExchangeRate
  exchangeRate: {
    getHistory: (currency = 'USD') =>
      api.get('/bff/exchange-rate', { params: { currency } }),
    backfill:   (currency = 'USD', since) =>
      api.post('/bff/exchange-rate/backfill', null, { params: { currency, ...(since && { since }) } })
  },

  // GdpTwse (GDP + 台股大盤年度走勢)
  gdpTwse: {
    get: (years = 30) => api.get('/bff/gdp-twse', { params: { years } })
  },

  // TradingCalendar
  tradingCalendar: {
    get:           (year) => api.get('/bff/trading-calendar', { params: year ? { year } : {} }),
    marketStatus: () => api.get('/bff/trading-calendar/market-status')
  },

  // SnapshotList
  snapshotList: {
    getAll:      () => api.get('/bff/snapshot-list'),
    delete:      (id) => api.delete(`/bff/snapshot-list/${id}`),
    exportExcel: () => api.get('/bff/snapshot-list/export', { responseType: 'blob' })
  },

  // StockAlert
  stockAlert: {
    getAll:    () => api.get('/bff/stock-alert'),
    reorder:   (ids) => api.put('/bff/stock-alert/reorder', ids),
    lookupName:(params) => api.get('/bff/stock-alert/lookup-name', { params }),
    create:    (data) => api.post('/bff/stock-alert', data),
    update:    (id, data) => api.put(`/bff/stock-alert/${id}`, data),
    toggleActive: (id) => api.patch(`/bff/stock-alert/${id}/active`),
    delete:    (id) => api.delete(`/bff/stock-alert/${id}`)
  },

  // WatchStock
  watchStock: {
    getAll:  () => api.get('/bff/watch-stock'),
    create:  (data) => api.post('/bff/watch-stock', data),
    delete:  (id) => api.delete(`/bff/watch-stock/${id}`),
    reorder: (orderedIds) => api.put('/bff/watch-stock/reorder', orderedIds)
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
  }
}

export default api
