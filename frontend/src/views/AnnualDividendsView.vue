<template>
  <div class="annual-dividends-page">
    <header class="page-header">
      <div class="page-heading">
        <h1>年度配息統計</h1>
        <p>依各次除息日前可得的持股資料，呈現已公告現金配息估算。</p>
      </div>

      <div class="filters" aria-label="年度配息統計篩選條件">
        <el-select
          v-model="selectedYear"
          class="year-select"
          aria-label="年度"
          placeholder="年度"
          :disabled="loading && yearOptions.length === 0"
          @change="loadSummary"
        >
          <el-option v-for="year in yearOptions" :key="year" :label="`${year} 年`" :value="year" />
        </el-select>
        <el-select
          v-model="selectedMarket"
          class="market-select"
          aria-label="市場"
          placeholder="全部市場"
          :disabled="loading && marketOptions.length === 0"
          @change="loadSummary"
        >
          <el-option label="全部市場" value="" />
          <el-option v-for="market in marketOptions" :key="market.code" :label="market.displayName" :value="market.code" />
        </el-select>
        <el-button type="primary" :loading="loading" @click="loadSummary">重新載入</el-button>
      </div>
    </header>

    <el-alert class="estimate-note" type="info" :closable="false" show-icon>
      <template #title>估算說明</template>
      <div>
        金額為原幣稅前估算，未扣稅、補充保費或費用，也不換算匯率。歷史項目依除息日前持股資料推估，未來項目依目前持股維持預估。
        交易流水可能不完整，公司行動（例如配股、拆併股）未自動校正；除息日不等於正式資格確認，資料也不代表款項已入帳。
      </div>
    </el-alert>

    <el-skeleton v-if="loading" class="loading-state" :rows="7" animated />

    <el-alert
      v-else-if="loadError"
      type="error"
      :closable="false"
      show-icon
      :title="loadError"
      class="state-alert"
    >
      <template #default>
        <el-button link type="primary" @click="loadSummary">重新載入</el-button>
      </template>
    </el-alert>

    <template v-else-if="summary">
      <div class="result-meta">
        <span>資料基準日：{{ summary.asOfDate || '未提供' }}</span>
        <span>待確認項目：{{ formatCount(summary.unavailableCount) }}</span>
      </div>

      <el-alert
        v-if="hasUnavailableItems"
        class="state-alert"
        type="warning"
        :closable="false"
        show-icon
        :title="`有 ${formatCount(summary.unavailableCount)} 待確認項目；金額小計只包含可計算項目。`"
      />

      <section class="content-section">
        <div class="section-heading">
          <div>
            <h2>{{ summary.selectedYear }} 年原幣估算</h2>
            <p>各幣別分列；未知金額不以 0 計。</p>
          </div>
        </div>

        <el-row v-if="summary.currencyTotals?.length" :gutter="14">
          <el-col v-for="total in summary.currencyTotals" :key="total.currency" :xs="24" :sm="12" :xl="8">
            <el-card class="currency-card" shadow="never">
              <template #header>
                <div class="currency-heading">
                  <strong>{{ currencyLabel(total.currency) }}</strong>
                  <span>可計算項目小計</span>
                </div>
              </template>
              <div class="currency-values">
                <div class="currency-value">
                  <span>歷史持股推估</span>
                  <strong>{{ formatMoney(total.historicalEstimate) }}</strong>
                </div>
                <div class="currency-value">
                  <span>維持目前持股預估</span>
                  <strong>{{ formatMoney(total.futureEstimate) }}</strong>
                </div>
                <div class="currency-value currency-value-total">
                  <span>合計</span>
                  <strong>{{ formatMoney(total.total) }}</strong>
                </div>
              </div>
            </el-card>
          </el-col>
        </el-row>
        <el-empty v-else description="尚無可列示的幣別估算。" :image-size="72" />
      </section>

      <section class="content-section">
        <div class="section-heading">
          <div>
            <h2>逐次配息</h2>
            <p>列出每次配息的計算股數與持股依據。</p>
          </div>
        </div>

        <el-empty
          v-if="!summary.rows?.length"
          :description="summary.gaps?.length ? '此年度沒有可列示的配息事件，請查看下方資料缺口。' : '此年度尚無可顯示的已公告現金配息事件。'"
          :image-size="72"
        />
        <el-table v-else :data="summary.rows" border stripe size="small" class="events-table">
          <el-table-column label="股票" min-width="155" fixed="left">
            <template #default="{ row }">
              <div class="stock-name">{{ row.name || row.code || '—' }}</div>
              <div class="secondary-text">{{ row.code || '—' }} · {{ marketLabel(row.market) }}</div>
            </template>
          </el-table-column>
          <el-table-column label="除息日" width="112">
            <template #default="{ row }">{{ formatDate(row.exDividendDate) }}</template>
          </el-table-column>
          <el-table-column label="發放日" width="112">
            <template #default="{ row }">{{ formatDate(row.cashPaymentDate) }}</template>
          </el-table-column>
          <el-table-column label="除息前估算股數" width="132" align="right">
            <template #default="{ row }">{{ formatQuantity(row.shares) }}</template>
          </el-table-column>
          <el-table-column label="每股現金配息" width="145" align="right">
            <template #default="{ row }">
              <span>{{ formatDecimal(row.cashDividend) }}</span>
              <span v-if="row.currency" class="secondary-text currency-suffix">{{ row.currency }}</span>
            </template>
          </el-table-column>
          <el-table-column label="稅前估算" width="145" align="right">
            <template #default="{ row }">
              <span>{{ formatMoney(row.estimatedCash) }}</span>
              <span v-if="row.currency" class="secondary-text currency-suffix">{{ row.currency }}</span>
            </template>
          </el-table-column>
          <el-table-column label="持股依據與基準" min-width="205">
            <template #default="{ row }">
              <div>{{ holdingBasisLabel(row.holdingBasis) }}</div>
              <div class="secondary-text">基準快照：{{ row.basisSnapshotDate || '—' }}</div>
              <div class="secondary-text">交易紀錄：{{ formatCount(row.transactionCount) }}</div>
            </template>
          </el-table-column>
          <el-table-column label="計算說明" min-width="210" show-overflow-tooltip>
            <template #default="{ row }">{{ row.reason || '—' }}</template>
          </el-table-column>
          <el-table-column label="股利來源" min-width="150" show-overflow-tooltip>
            <template #default="{ row }">{{ row.source || '未標示' }}</template>
          </el-table-column>
        </el-table>
      </section>

      <section v-if="summary.gaps?.length" class="content-section">
        <div class="section-heading">
          <div>
            <h2>資料缺口</h2>
            <p>以下標的或事件缺少可用資料，無法納入完整估算。</p>
          </div>
        </div>
        <el-table :data="summary.gaps" border stripe size="small" class="gaps-table">
          <el-table-column label="股票" min-width="180">
            <template #default="{ row }">
              <div class="stock-name">{{ row.name || row.code || '—' }}</div>
              <div class="secondary-text">{{ row.code || '—' }}</div>
            </template>
          </el-table-column>
          <el-table-column label="市場" width="150">
            <template #default="{ row }">{{ marketLabel(row.market) }}</template>
          </el-table-column>
          <el-table-column label="原因" min-width="280" show-overflow-tooltip>
            <template #default="{ row }">{{ row.reason || '—' }}</template>
          </el-table-column>
        </el-table>
      </section>
    </template>
  </div>
</template>

<script setup>
import { apiErrorMessage, bffApi } from '@/api/index.js'

const selectedYear = ref(null)
const selectedMarket = ref('')
const summary = ref(null)
const yearOptions = ref([])
const marketOptions = ref([])
const loading = ref(false)
const loadError = ref('')

let requestSequence = 0
let activeController = null

const hasUnavailableItems = computed(() => {
  if (!summary.value) return false
  const count = summary.value.unavailableCount
  return count == null || String(count).trim() !== '0'
})

function formatDecimal(value, minimumFractionDigits = 0) {
  if (value == null || value === '') return '—'
  const text = String(value).trim()
  const match = text.match(/^(-?)(\d+)(?:\.(\d+))?$/)
  if (!match) return text

  const [, sign, integer, fraction = ''] = match
  const groupedInteger = integer.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  const visibleFraction = fraction.padEnd(minimumFractionDigits, '0')
  return `${sign}${groupedInteger}${visibleFraction ? `.${visibleFraction}` : ''}`
}

function formatMoney(value) {
  return formatDecimal(value, 2)
}

function formatQuantity(value) {
  return formatDecimal(value)
}

function formatCount(value) {
  return value == null || value === '' ? '—' : `${value} 筆`
}

function formatDate(value) {
  return value == null || value === '' ? '未提供' : value
}

function currencyLabel(currency) {
  if (currency === 'TWD') return 'TWD · 新臺幣'
  if (currency === 'USD') return 'USD · 美元'
  return currency || '幣別未提供'
}

function marketLabel(code) {
  if (code == null || code === '') return '—'
  return marketOptions.value.find(market => market.code === code)?.displayName || code
}

function holdingBasisLabel(value) {
  if (value === 'HISTORICAL_ESTIMATE') return '歷史持股推估'
  if (value === 'FUTURE_ESTIMATE') return '維持目前持股預估'
  return '—'
}

async function loadSummary() {
  const currentRequest = ++requestSequence
  activeController?.abort()
  const controller = new AbortController()
  activeController = controller

  loading.value = true
  loadError.value = ''
  summary.value = null

  try {
    const result = await bffApi.annualDividends.summary({
      year: selectedYear.value,
      market: selectedMarket.value,
      signal: controller.signal
    })
    if (currentRequest !== requestSequence) return

    summary.value = result
    yearOptions.value = result?.availableYears || []
    marketOptions.value = result?.markets || []
    if (selectedYear.value == null && result?.selectedYear != null) {
      selectedYear.value = result.selectedYear
    }
  } catch (error) {
    if (currentRequest !== requestSequence || controller.signal.aborted) return
    summary.value = null
    loadError.value = apiErrorMessage(error, '年度配息資料載入失敗，請稍後重試。')
  } finally {
    if (currentRequest === requestSequence) {
      loading.value = false
      activeController = null
    }
  }
}

onMounted(loadSummary)

onUnmounted(() => {
  requestSequence += 1
  activeController?.abort()
  activeController = null
})
</script>

<style scoped>
.annual-dividends-page {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 18px;
  flex-wrap: wrap;
}

.page-heading h1,
.section-heading h2 {
  color: #1e293b;
  font-weight: 700;
}

.page-heading h1 {
  font-size: 22px;
  margin: 0 0 6px;
}

.page-heading p,
.section-heading p {
  margin: 0;
  color: #64748b;
  font-size: 13px;
  line-height: 1.6;
}

.filters {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  flex-wrap: wrap;
  min-width: 0;
  max-width: 100%;
}

.year-select {
  width: 130px;
  min-width: 0;
  max-width: 100%;
}

.market-select {
  width: 170px;
  min-width: 0;
  max-width: 100%;
}

.estimate-note,
.state-alert {
  margin-bottom: 0;
}

.loading-state {
  padding: 14px 4px;
}

.result-meta {
  display: flex;
  align-items: center;
  gap: 8px 20px;
  flex-wrap: wrap;
  color: #475569;
  font-size: 13px;
}

.content-section {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.section-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.section-heading h2 {
  margin: 0 0 4px;
  font-size: 17px;
}

.currency-card {
  height: 100%;
  margin-bottom: 14px;
  border-color: #e2e8f0;
}

.currency-heading {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 10px;
}

.currency-heading strong {
  color: #0f172a;
  font-size: 16px;
}

.currency-heading span {
  color: #64748b;
  font-size: 12px;
}

.currency-values {
  display: grid;
  gap: 12px;
}

.currency-value {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  color: #475569;
  font-size: 13px;
}

.currency-value strong {
  color: #334155;
  font-size: 15px;
  font-variant-numeric: tabular-nums;
  text-align: right;
}

.currency-value-total {
  padding-top: 10px;
  border-top: 1px solid #e2e8f0;
  color: #0f172a;
  font-weight: 700;
}

.currency-value-total strong {
  color: #1d4ed8;
  font-size: 17px;
}

.events-table,
.gaps-table {
  width: 100%;
}

.stock-name {
  color: #1e293b;
  font-weight: 600;
}

.secondary-text {
  color: #64748b;
  font-size: 12px;
  line-height: 1.5;
}

.currency-suffix {
  margin-left: 5px;
  white-space: nowrap;
}

@media (max-width: 720px) {
  .page-header {
    align-items: stretch;
  }

  .filters {
    justify-content: flex-start;
  }

  .year-select {
    flex: 1 1 110px;
  }

  .market-select {
    flex: 1 1 145px;
  }

  .filters :deep(.el-button) {
    flex: 1 0 100%;
  }
}
</style>
