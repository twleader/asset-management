<template>
  <div v-loading="store.loading || saving">
    <div class="page-header">
      <el-button :icon="ArrowLeft" @click="$router.back()">返回</el-button>
      <el-button type="primary" :icon="Edit"
        @click="$router.push('/snapshots/'+route.params.id+'/edit')">編輯快照</el-button>
      <el-button v-if="isDirty" type="warning" :icon="Check" :loading="saving" @click="saveChanges">
        儲存所有變更
      </el-button>
    </div>

    <template v-if="detail">
      <!-- Summary Cards -->
      <el-row :gutter="16" style="margin-bottom:20px">
        <el-col :span="6" v-for="c in summaryCards" :key="c.label">
          <el-card class="summary-card">
            <div class="s-label">{{ c.label }}</div>
            <div class="s-value">{{ c.value }}</div>
            <div class="s-sub" v-if="c.sub" :class="c.subClass">{{ c.sub }}</div>
          </el-card>
        </el-col>
      </el-row>

      <!-- Deposits -->
      <el-card style="margin-bottom:16px">
        <template #header><span class="section-title">💰 存款明細</span></template>
        <el-table :data="detail.deposits" size="small">
          <el-table-column prop="bankDisplayName" label="銀行" />
          <el-table-column prop="depositDisplayName" label="類型" />
          <el-table-column prop="currency" label="幣別" width="70" />
          <el-table-column label="金額" align="right" :formatter="(r) => fmt(r.amount)" />
          <el-table-column label="原始金額" align="right"
            :formatter="(r) => r.originalAmount ? fmt(r.originalAmount) : '-'" />
          <el-table-column prop="notes" label="備註" />
        </el-table>
        <div class="total-row">存款總計：<strong>{{ fmt(detail.totalDeposit) }}</strong></div>
      </el-card>

      <!-- Funds -->
      <el-card style="margin-bottom:16px">
        <template #header><span class="section-title">📊 信託基金</span></template>
        <el-table :data="detail.funds" size="small">
          <el-table-column prop="fundName" label="基金名稱" min-width="200" />
          <el-table-column prop="fundCode" label="代號" width="80" />
          <el-table-column prop="bank" label="銀行" width="100" />
          <el-table-column label="投資金額" align="right" :formatter="(r) => fmt(r.investmentAmount)" />
          <el-table-column label="現值" align="right" :formatter="(r) => fmt(r.currentValue)" />
          <el-table-column label="損益" align="right">
            <template #default="{ row }">
              <span :class="row.profit >= 0 ? 'profit' : 'loss'">
                {{ fmt(row.profit) }} ({{ pct(row.profitRate) }})
              </span>
            </template>
          </el-table-column>
        </el-table>
        <div class="total-row">
          基金現值：<strong>{{ fmt(detail.totalFundValue) }}</strong>
          　投資成本：{{ fmt(detail.totalFundCost) }}
          　損益：<span :class="detail.totalFundValue - detail.totalFundCost >= 0 ? 'profit' : 'loss'">
            {{ fmt(detail.totalFundValue - detail.totalFundCost) }}
          </span>
        </div>
      </el-card>

      <!-- Stocks -->
      <el-card>
        <template #header>
          <span class="section-title">📈 股票持股</span>
          <el-radio-group v-model="stockFilter" size="small" style="float:right">
            <el-radio-button value="">全部</el-radio-button>
            <el-radio-button value="台股">台股</el-radio-button>
            <el-radio-button value="美股">美股</el-radio-button>
          </el-radio-group>
        </template>

        <el-table
          :data="groupedStocks"
          size="small"
          row-key="groupKey"
        >
          <!-- Expand -->
          <el-table-column type="expand" width="40">
            <template #default="{ row }">
              <div class="broker-expand">
                <!-- Editable broker sub-table -->
                <el-table :data="row.brokerRows" size="small" border
                  :style="row.market === '美股' ? 'width:940px' : 'width:760px'">
                  <!-- 券商 -->
                  <el-table-column label="券商" width="130">
                    <template #default="{ row: br }">
                      <el-select v-model="br.brokerId" size="small" style="width:100%"
                        placeholder="選擇券商" clearable @change="markDirty">
                        <el-option v-for="b in brokerOptions" :key="b.value" :label="b.label" :value="b.value" />
                      </el-select>
                    </template>
                  </el-table-column>
                  <!-- 股數 -->
                  <el-table-column label="股數" width="110" align="right">
                    <template #default="{ row: br }">
                      <el-input
                        :model-value="numFmt(br.shares)"
                        size="small" style="width:100%; text-align:right"
                        @change="(v) => { br.shares = numParseF(v, row.market === '美股' ? 5 : 0); markDirty(); syncFromShares(br, row) }" />
                    </template>
                  </el-table-column>
                  <!-- 幣別（美股才顯示） -->
                  <el-table-column v-if="row.market === '美股'" label="幣別" width="100">
                    <template #default="{ row: br }">
                      <el-select v-model="br.currency" size="small" style="width:100%" @change="markDirty">
                        <el-option value="TWD" label="台幣" />
                        <el-option value="USD" label="原幣(USD)" />
                      </el-select>
                    </template>
                  </el-table-column>
                  <!-- 均價（每股台幣）→ 輸入後自動算總成本 -->
                  <el-table-column label="買入均價" width="110" align="right">
                    <template #default="{ row: br }">
                      <el-input
                        :model-value="numFmt(br.avgCost)"
                        size="small" style="width:100%; text-align:right"
                        @change="(v) => { br.avgCost = numParseF(v, row.market === '美股' ? 4 : 2); markDirty(); syncFromAvg(br) }" />
                    </template>
                  </el-table-column>
                  <!-- 持股成本（總額，台幣）→ 輸入後自動算均價 -->
                  <el-table-column label="持股成本" width="120" align="right">
                    <template #default="{ row: br }">
                      <el-input
                        :model-value="numFmt(br.investmentCost)"
                        size="small" style="width:100%; text-align:right"
                        @change="(v) => { br.investmentCost = numParseF(v, 0); markDirty(); syncFromCost(br) }" />
                    </template>
                  </el-table-column>
                  <!-- 均價(USD)（美股，選原幣時才啟用） -->
                  <el-table-column v-if="row.market === '美股'" label="買入均價(USD)" width="130" align="right">
                    <template #default="{ row: br }">
                      <el-input
                        :model-value="br.currency === 'USD' ? numFmt(br.originalCurrencyValue) : ''"
                        :disabled="br.currency !== 'USD'"
                        :placeholder="br.currency === 'USD' ? '' : '—'"
                        size="small" style="width:100%; text-align:right"
                        @change="(v) => { br.originalCurrencyValue = numParseF(v, 2); markDirty() }" />
                    </template>
                  </el-table-column>
                  <!-- 現值(台幣) — 唯讀，= 股數 × 每股現價 -->
                  <el-table-column label="現值(台幣)" width="120" align="right">
                    <template #default="{ row: br }">
                      <span class="readonly-val">{{ fmt(Math.round(br.shares * row.unitPrice)) }}</span>
                    </template>
                  </el-table-column>
                  <!-- 損益 -->
                  <el-table-column label="損益" align="right" min-width="110">
                    <template #default="{ row: br }">
                      <span :class="brokerProfit(br) >= 0 ? 'profit' : 'loss'">
                        {{ fmt(brokerProfit(br)) }}
                        <small style="display:block;font-weight:400">{{ pct(brokerProfitRate(br)) }}</small>
                      </span>
                    </template>
                  </el-table-column>
                  <!-- 刪除 -->
                  <el-table-column width="44">
                    <template #default="{ $index }">
                      <el-popconfirm title="確定刪除此筆券商持股？" width="240" confirm-button-text="刪除" cancel-button-text="取消" confirm-button-type="danger"
                        @confirm="removeBrokerRow(row, $index)">
                        <template #reference>
                          <el-button type="danger" size="small" :icon="Delete" circle />
                        </template>
                      </el-popconfirm>
                    </template>
                  </el-table-column>
                </el-table>

                <div style="display:flex;gap:8px;margin-top:8px;align-items:center">
                  <el-button size="small" :icon="Plus" @click="addBrokerRow(row)">
                    新增券商持股
                  </el-button>
                  <el-button size="small" type="primary" :icon="Check"
                    :loading="saving" @click="saveChanges">
                    儲存
                  </el-button>
                  <span v-if="isDirty" style="font-size:12px;color:#f59e0b">● 有未儲存的變更</span>
                </div>
              </div>
            </template>
          </el-table-column>

          <!-- 代號 -->
          <el-table-column label="代號" width="90">
            <template #default="{ row }">
              <span class="stock-code">{{ row.stockCode }}</span>
            </template>
          </el-table-column>

          <!-- 名稱 -->
          <el-table-column label="名稱" min-width="130">
            <template #default="{ row }">{{ row.stockName }}</template>
          </el-table-column>

          <!-- 市場 -->
          <el-table-column label="市場" width="70">
            <template #default="{ row }">
              <el-tag :type="row.market === '台股' ? 'primary' : 'warning'" size="small">
                {{ row.market }}
              </el-tag>
            </template>
          </el-table-column>

          <!-- 券商筆數 -->
          <el-table-column label="券商" width="70" align="center">
            <template #default="{ row }">
              <el-tag size="small" type="info">{{ row.brokerRows.length }} 筆</el-tag>
            </template>
          </el-table-column>

          <!-- 總股數 -->
          <el-table-column label="股數" align="right" width="120">
            <template #default="{ row }">
              {{ fmtShares(sumShares(row), row.market) }}
            </template>
          </el-table-column>

          <!-- 股價 -->
          <el-table-column label="股價" align="right" width="100">
            <template #default="{ row }">
              {{ row.unitPrice ? fmtPrice(row.unitPrice) : '-' }}
            </template>
          </el-table-column>

          <!-- 投資成本 -->
          <el-table-column label="投資成本" align="right"
            :formatter="(r) => fmt(sumCost(r))" />

          <!-- 現值 -->
          <el-table-column label="現值" align="right"
            :formatter="(r) => fmt(sumValue(r))" />

          <!-- 損益 -->
          <el-table-column label="損益" align="right" min-width="130">
            <template #default="{ row }">
              <span :class="sumProfit(row) >= 0 ? 'profit' : 'loss'">
                {{ fmt(sumProfit(row)) }}<br>
                <small>{{ pct(sumProfitRate(row)) }}</small>
              </span>
            </template>
          </el-table-column>

          <!-- 預估配息 -->
          <el-table-column label="預估配息" align="right">
            <template #default="{ row }">
              {{ row.dividendRate ? fmt(sumValue(row) * Number(row.dividendRate)) : '-' }}
            </template>
          </el-table-column>

          <!-- 配息率 -->
          <el-table-column label="配息率" align="right" width="80"
            :formatter="(r) => r.dividendRate ? pct(r.dividendRate) : '-'" />
        </el-table>

        <div class="total-row">
          股票現值：<strong>{{ fmt(filteredTotals.value) }}</strong>
          　持股均價：{{ fmt(filteredTotals.avgCostPerShare) }}
          　投資成本：{{ fmt(filteredTotals.cost) }}
          　損益：<span :class="filteredTotals.profit >= 0 ? 'profit' : 'loss'">
            {{ fmt(filteredTotals.profit) }}
          </span>
          　預估配息：{{ fmt(filteredTotals.dividend) }}
        </div>
      </el-card>
    </template>
  </div>
</template>

<script setup>
import { ArrowLeft, Edit, Plus, Delete, Check } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useRoute } from 'vue-router'
import { useAssetStore } from '@/stores/assetStore'
import { snapshotApi, marketDataApi, institutionApi } from '@/api'

const route  = useRoute()
const store  = useAssetStore()
const saving = ref(false)
const isDirty = ref(false)
const stockFilter = ref('')

// ── broker options（從 API 動態載入，取代 hardcoded）────────────────────
const brokerOptions = ref([])   // { value: id, label: displayName }

async function loadBrokers() {
  const brokers = await institutionApi.getAllBrokers()
  brokerOptions.value = brokers.filter(b => b.active).map(b => ({ value: b.id, label: b.displayName }))
}

// ── allGroupedStocks: 全量群組（不受 filter 影響，供編輯/儲存使用）
const allGroupedStocks = ref([])

// ── groupedStocks: filter 後的顯示資料
const groupedStocks = computed(() =>
  stockFilter.value
    ? allGroupedStocks.value.filter(g => g.market === stockFilter.value)
    : allGroupedStocks.value
)

onMounted(async () => {
  await Promise.all([
    store.fetchSnapshotDetail(route.params.id),
    loadBrokers()
  ])
  buildGroups()
  fetchMissingDividendRates()
})

// 配息率由後端 getSnapshotDetail 自動補齊並存回 DB，前端不需另行查詢
async function fetchMissingDividendRates() {
  // no-op：後端已在回傳 snapshot detail 時自動補齊 dividendRate 並存回 PostgreSQL
}

const detail = computed(() => store.currentSnapshot)

watch(() => store.currentSnapshot, () => {
  buildGroups()
  isDirty.value = false
})

function buildGroups() {
  const stocks = store.currentSnapshot?.stocks || []
  const map = new Map()
  for (const s of stocks) {
    const key = `${s.market}_${s.stockCode}`
    if (!map.has(key)) {
      map.set(key, {
        groupKey:     key,
        stockCode:    s.stockCode,
        stockName:    s.stockName,
        market:       s.market,
        dividendRate: s.dividendRate,
        unitPrice:    0,   // 每股現價，下方計算
        brokerRows:   []
      })
    }
    const sh = Number(s.shares || 0)
    const ic = Number(s.investmentCost || 0)
    map.get(key).brokerRows.push({
      brokerId:              s.brokerId || null,
      shares:                sh,
      currency:              s.currency || 'TWD',
      investmentCost:        ic,
      avgCost:               sh > 0 ? Number((ic / sh).toFixed(4)) : 0,
      originalCurrencyValue: s.originalCurrencyValue ? Number(s.originalCurrencyValue) : null,
      storedCurrentValue:    Number(s.currentValue || 0)  // 儲存原始值備用
    })
  }
  // 計算每股現價 = 各券商 currentValue 總和 ÷ 總股數，並回填每筆 currentValue
  for (const g of map.values()) {
    const totalVal = g.brokerRows.reduce((a, b) => a + b.storedCurrentValue, 0)
    const totalSh  = g.brokerRows.reduce((a, b) => a + b.shares, 0)
    g.unitPrice = totalSh > 0 ? totalVal / totalSh : 0
    for (const br of g.brokerRows) {
      br.currentValue = Math.round(br.shares * g.unitPrice)
    }
  }
  allGroupedStocks.value = [...map.values()]
}

// ── cost / avgCost sync helpers ─────────────────────────────────────────
// 輸入「持股成本」→ 重算均價
const syncFromCost = (br) => {
  if (br.shares > 0) br.avgCost = Number((br.investmentCost / br.shares).toFixed(4))
}
// 輸入「均價」→ 重算總成本
const syncFromAvg = (br) => {
  br.investmentCost = Math.round((br.avgCost || 0) * (br.shares || 0))
}
// 修改「股數」→ 保持均價不變，重算總成本；同時更新 currentValue
const syncFromShares = (br, group) => {
  br.investmentCost = Math.round((br.avgCost || 0) * (br.shares || 0))
  br.currentValue   = Math.round((br.shares || 0) * (group?.unitPrice || 0))
}

// ── broker row mutations ────────────────────────────────────────────────
const markDirty = () => { isDirty.value = true }

const addBrokerRow = (stockRow) => {
  stockRow.brokerRows.push({
    brokerId: null, shares: 0,
    currency: 'TWD',
    investmentCost: 0, avgCost: 0,
    originalCurrencyValue: null,
    currentValue: 0, estimatedDividend: 0
  })
  isDirty.value = true
}

const removeBrokerRow = (stockRow, idx) => {
  if (stockRow.brokerRows.length <= 1) {
    ElMessage.warning('每支股票至少需保留一筆')
    return
  }
  stockRow.brokerRows.splice(idx, 1)
  isDirty.value = true
}

// ── save ────────────────────────────────────────────────────────────────
const saveChanges = async () => {
  const d = detail.value
  if (!d) return
  saving.value = true
  try {
    // Flatten groupedStocks back to StockRequest[]
    const stocks = allGroupedStocks.value.flatMap(g =>
      g.brokerRows.map(br => ({
        stockCode:             g.stockCode,
        stockName:             g.stockName,
        market:                g.market,
        brokerId:              br.brokerId || null,
        shares:                br.shares,
        investmentCost:        br.investmentCost || 0,
        currentValue:          Math.round(br.shares * g.unitPrice),
        estimatedDividend:     Math.round(br.shares * g.unitPrice * Number(g.dividendRate || 0)),
        dividendRate:          g.dividendRate,
        currency:              br.currency || 'TWD',
        originalCurrencyValue: br.currency === 'USD' ? br.originalCurrencyValue : null
      }))
    )

    const payload = {
      snapshotDate:    d.snapshotDate,
      usdExchangeRate: d.usdExchangeRate,
      notes:           d.notes,
      deposits: d.deposits.map(dep => ({
        bankId: dep.bankId || null, depositType: dep.depositType,
        currency: dep.currency, amount: dep.amount,
        originalAmount: dep.originalAmount, notes: dep.notes
      })),
      funds: d.funds.map(f => ({
        fundName: f.fundName, fundCode: f.fundCode, bankId: f.bankId || null,
        investmentAmount: f.investmentAmount, currentValue: f.currentValue
      })),
      stocks
    }

    await snapshotApi.update(route.params.id, payload)
    await store.fetchSnapshotDetail(route.params.id)
    isDirty.value = false
    ElMessage.success('券商資料已儲存')
  } catch {
    // interceptor shows error
  } finally {
    saving.value = false
  }
}

// ── row helpers ─────────────────────────────────────────────────────────
const sumShares      = (r) => r.brokerRows.reduce((a, b) => a + Number(b.shares || 0), 0)
const sumCost        = (r) => r.brokerRows.reduce((a, b) => a + Number(b.investmentCost || 0), 0)
const sumValue       = (r) => r.brokerRows.reduce((a, b) => a + Number(b.currentValue || 0), 0)
const sumProfit      = (r) => sumValue(r) - sumCost(r)
const sumProfitRate  = (r) => { const c = sumCost(r); return c > 0 ? sumProfit(r) / c : 0 }

// 隨篩選變動的合計（台股/美股/全部）
const filteredTotals = computed(() => {
  const rows = groupedStocks.value
  const value    = rows.reduce((a, r) => a + sumValue(r), 0)
  const cost     = rows.reduce((a, r) => a + sumCost(r), 0)
  const shares   = rows.reduce((a, r) => a + sumShares(r), 0)
  const profit   = value - cost
  const avgCostPerShare = shares > 0 ? Math.round(cost / shares) : 0
  const dividend = rows.reduce((a, r) => {
    const rate = Number(r.dividendRate || 0)
    return a + (rate ? sumValue(r) * rate : 0)
  }, 0)
  return { value, cost, shares, avgCostPerShare, profit, dividend }
})

const brokerTotalCost  = (br) => Number(br.investmentCost || 0)
const brokerProfit     = (br) => Number(br.currentValue || 0) - brokerTotalCost(br)
const brokerProfitRate = (br) => brokerTotalCost(br) > 0
  ? brokerProfit(br) / brokerTotalCost(br) : 0

// ── 數字顯示：加千位逗號，保留小數部分 ────────────────────────────────
const numFmt = (v) => {
  if (v === null || v === undefined || v === '') return ''
  const s = String(v).replace(/,/g, '')
  const dot = s.indexOf('.')
  const intPart = dot >= 0 ? s.slice(0, dot) : s
  const decPart = dot >= 0 ? s.slice(dot) : ''
  return intPart.replace(/\B(?=(\d{3})+(?!\d))/g, ',') + decPart
}
// ── 字串解析為數字，precision=0 → 整數，>0 → 保留小數 ──────────────
const numParseF = (v, precision = 2) => {
  const raw = String(v || '').replace(/,/g, '')
  const n = parseFloat(raw)
  if (isNaN(n)) return 0
  return precision === 0 ? Math.round(n) : parseFloat(n.toFixed(precision))
}

// ── formatters ──────────────────────────────────────────────────────────
const fmt = (v) => {
  if (v == null) return '-'
  return `$${Number(v).toLocaleString('zh-TW', { maximumFractionDigits: 0 })}`
}
const pct = (v) => v != null ? `${(Number(v) * 100).toFixed(2)}%` : '-'
const fmtShares = (v, market) => {
  if (v == null) return '-'
  const n = Number(v)
  if (market === '美股') return n.toLocaleString('en-US', { minimumFractionDigits: 5, maximumFractionDigits: 5 })
  return n.toLocaleString('zh-TW', { maximumFractionDigits: 0 })
}
const fmtPrice = (v) => {
  if (v == null) return '-'
  return Number(v).toLocaleString('zh-TW', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

const summaryCards = computed(() => {
  const d = detail.value
  if (!d) return []
  return [
    { label: '資產總計', value: fmt(d.totalAssets) },
    { label: '存款', value: fmt(d.totalDeposit) },
    {
      label: '股票損益',
      value: fmt(Number(d.totalStockValue) - Number(d.totalStockCost)),
      sub: `現值 ${fmt(d.totalStockValue)}`,
      subClass: Number(d.totalStockValue) - Number(d.totalStockCost) >= 0 ? 'profit' : 'loss'
    },
    { label: '預估年配息', value: fmt(d.estimatedAnnualDividend) }
  ]
})
</script>

<style scoped>
.page-header { display: flex; gap: 10px; margin-bottom: 20px; align-items: center; }
.section-title { font-size: 15px; font-weight: 600; }
.summary-card :deep(.el-card__body) { padding: 16px; }
.s-label { font-size: 13px; color: #64748b; margin-bottom: 6px; }
.s-value { font-size: 20px; font-weight: 700; color: #1e293b; }
.s-sub { font-size: 12px; margin-top: 4px; }
.total-row {
  padding: 10px 0 0;
  font-size: 13px;
  color: #475569;
  border-top: 1px solid #f1f5f9;
  margin-top: 8px;
}
.profit { color: #16a34a; font-weight: 600; }
.loss   { color: #dc2626; font-weight: 600; }
.stock-code { font-family: monospace; font-weight: 600; }

.broker-expand {
  padding: 10px 16px 12px 56px;
  background: #f8fafc;
}
</style>
