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

// ===== Snapshots =====
export const snapshotApi = {
  getAll: () => api.get('/snapshots'),
  getDetail: (id) => api.get(`/snapshots/${id}`),
  create: (data) => api.post('/snapshots', data),
  update: (id, data) => api.put(`/snapshots/${id}`, data),
  delete: (id) => api.delete(`/snapshots/${id}`),
  getHistory: () => api.get('/snapshots/history'),
  importExcel: (file) => {
    const form = new FormData()
    form.append('file', file)
    return api.post('/snapshots/import', form, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
  },
  updateDividendRates: (id, rates) => api.patch(`/snapshots/${id}/dividend-rates`, rates),
  updateStockOrder: (id, orders) => api.patch(`/snapshots/${id}/stock-order`, orders),
  enrichAllDividendRates: () => api.post('/snapshots/enrich-all-dividend-rates'),
  recalcDividends: () => api.post('/snapshots/recalc-dividends')
}

// ===== Institution Settings (Banks, Brokers, DepositTypes, MarketTypes) =====
export const institutionApi = {
  // Banks
  getAllBanks:    () => api.get('/settings/banks'),
  createBank:    (data) => api.post('/settings/banks', data),
  updateBank:    (id, data) => api.put(`/settings/banks/${id}`, data),
  setBankActive: (id, active) => api.patch(`/settings/banks/${id}/active`, { active }),
  // Brokers
  getAllBrokers:    () => api.get('/settings/brokers'),
  createBroker:    (data) => api.post('/settings/brokers', data),
  updateBroker:    (id, data) => api.put(`/settings/brokers/${id}`, data),
  setBrokerActive: (id, active) => api.patch(`/settings/brokers/${id}/active`, { active }),
  // Deposit Types
  getAllDepositTypes:    () => api.get('/settings/deposit-types'),
  createDepositType:    (data) => api.post('/settings/deposit-types', data),
  updateDepositType:    (id, data) => api.put(`/settings/deposit-types/${id}`, data),
  setDepositTypeActive: (id, active) => api.patch(`/settings/deposit-types/${id}/active`, { active }),
  // Market Types
  getAllMarketTypes:    () => api.get('/settings/market-types'),
  createMarketType:    (data) => api.post('/settings/market-types', data),
  updateMarketType:    (id, data) => api.put(`/settings/market-types/${id}`, data),
  setMarketTypeActive: (id, active) => api.patch(`/settings/market-types/${id}/active`, { active }),
  // Transit Fund Types
  getAllTransitFundTypes:    () => api.get('/settings/transit-fund-types'),
  getActiveTransitFundTypes: () => api.get('/settings/transit-fund-types/active'),
  createTransitFundType:    (data) => api.post('/settings/transit-fund-types', data),
  updateTransitFundType:    (id, data) => api.put(`/settings/transit-fund-types/${id}`, data),
  setTransitFundTypeActive: (id, active) => api.patch(`/settings/transit-fund-types/${id}/active`, { active })
}

// ===== Realized Gains =====
export const gainApi = {
  getAll: () => api.get('/realized-gains'),
  create: (data) => api.post('/realized-gains', data),
  update: (id, data) => api.put(`/realized-gains/${id}`, data),
  delete: (id) => api.delete(`/realized-gains/${id}`),
  importExcel: (file) => {
    const form = new FormData()
    form.append('file', file)
    return api.post('/realized-gains/import', form, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
  }
}

// ===== Market Data =====
export const marketDataApi = {
  getDividendRate: (code, market) =>
    api.get('/market-data/dividend-rate', { params: { code, market } }),
  getPrice: (code, market) =>
    api.get('/market-data/price', { params: { code, market } }),
  getAllPrices: () => api.get('/market-data/prices'),
  getMarketStatus: () => api.get('/market-data/market-status'),
  getHolidays: (year) => api.get('/market-data/holidays', { params: { year } }),
  refreshPrices: () => api.post('/market-data/prices/refresh'),
  backfillHistory: () => api.post('/market-data/history/backfill'),
  backfillSingleStock: (code, market, since, until) => {
    const params = new URLSearchParams({ code, market })
    if (since) params.append('since', since)
    if (until) params.append('until', until)
    return api.post(`/market-data/history/backfill-stock?${params.toString()}`)
  },
  getStockHistory: (code, market, start, end) =>
    api.get('/market-data/history/stock', { params: { code, market, start, end } }),
  getEtfHoldings: (code, market) =>
    api.get('/market-data/etf-holdings', { params: { code, market } }),
  getDividendHistory: (code, market, years = 10) =>
    api.get('/market-data/dividends', { params: { code, market, years } }),
  getExchangeRate: (currency, start, end) =>
    api.get('/market-data/exchange-rate', { params: { currency, start, end } }),
  refreshExchangeRate: (currency = 'USD') =>
    api.post('/market-data/exchange-rate/refresh', null, { params: { currency } }),
  backfillExchangeRateHistory: (currency = 'USD', since) =>
    api.post('/market-data/exchange-rate/backfill-history', null, { params: { currency, ...(since && { since }) } }),
  getPricesOnDate: (date, stocks) =>
    api.post('/market-data/history/prices-on-date', stocks, { params: { date } }),
  getLiveAssets: () => api.get('/market-data/live-assets'),
  getExchangeRateOnDate: (currency, date) =>
    api.get('/market-data/exchange-rate/on-date', { params: { currency, date } })
}

// ===== BFF Aggregated Endpoints =====
export const bffApi = {
  // Dashboard page: single call that aggregates snapshots, history, prices, and market status
  getDashboardSummary: () => api.get('/bff/dashboard/summary')
}

export default api
