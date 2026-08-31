<template>
  <div v-loading="loading">
    <div class="page-header">
      <h2>🧮 資產類別歸類</h2>
      <el-input
        v-model="keyword"
        placeholder="搜尋代號 / 名稱"
        clearable
        :prefix-icon="Search"
        style="width: 220px" />
    </div>

    <el-alert type="info" :closable="false" show-icon style="margin-bottom: 16px">
      <template #title>
        預設依規則自動判定。<b>資產類別</b>：台股 <b>00…B</b>、美股債券 ETF → 債券；基金名稱含「債／高收益／收益」→ 債券（「入息／股息」屬高股息股票型，仍歸股票），其餘 → 股票。
        <b>細分</b>：股票依殖利率分成長／收益型；債券依名稱年期分短／中／長期（基金無殖利率，自動為成長型）。資產類別與細分皆可逐檔指定（含基金），設定一次即套用到所有快照與圓餅圖。
      </template>
    </el-alert>

    <el-card style="margin-bottom: 16px">
      <div class="threshold-bar">
        <span>收益型殖利率門檻：</span>
        <el-input-number v-model="thresholdPct" :min="0" :max="20" :step="0.5" :precision="2" size="small" />
        <span>%（殖利率 ≥ 此值 → 收益型）</span>
        <el-button type="primary" size="small" :loading="savingThreshold" @click="saveThreshold">儲存門檻</el-button>
      </div>
    </el-card>

    <el-card>
      <el-table :data="filteredSecurities" size="small" stripe max-height="600">
        <el-table-column prop="market" label="市場" width="80" />
        <el-table-column prop="code" label="代號" width="110" />
        <el-table-column prop="name" label="名稱" min-width="150" show-overflow-tooltip />
        <el-table-column label="資產類別" width="92" align="center">
          <template #default="{ row }">
            <el-tag :color="classColor(row.effectiveAssetClass)" effect="dark" size="small" style="border:none">
              {{ classLabel(row.effectiveAssetClass) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="指定類別" width="148" align="center">
          <template #default="{ row }">
            <el-select
              :model-value="row.assetClass ?? ''"
              size="small"
              style="width: 126px"
              @change="(v) => setClass(row, v)">
              <el-option label="自動（規則）" value="" />
              <el-option
                v-for="c in assignableCategories"
                :key="c.code"
                :label="c.displayName"
                :value="c.code" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="細分" width="92" align="center">
          <template #default="{ row }">
            <el-tag
              v-if="row.effectiveAssetClass === 'STOCK' && row.effectiveStockStyle"
              :color="styleColor(row.effectiveStockStyle)" effect="dark" size="small" style="border:none">
              {{ styleLabel(row.effectiveStockStyle) }}
            </el-tag>
            <el-tag
              v-else-if="row.effectiveAssetClass === 'BOND' && row.effectiveBondTerm"
              :color="termColor(row.effectiveBondTerm)" effect="dark" size="small" style="border:none">
              {{ termLabel(row.effectiveBondTerm) }}
            </el-tag>
            <span v-else style="color:#cbd5e1">—</span>
          </template>
        </el-table-column>
        <el-table-column label="指定細分" width="150" align="center">
          <template #default="{ row }">
            <!-- 股票 / 股票型基金 → 成長/收益型（基金無殖利率，自動 = 成長型） -->
            <el-select
              v-if="row.effectiveAssetClass === 'STOCK'"
              :model-value="row.stockStyle ?? ''"
              size="small" style="width: 128px"
              @change="(v) => setStyle(row, v)">
              <el-option :label="row.market === '基金' ? '自動（成長型）' : '自動（殖利率）'" value="" />
              <el-option v-for="s in stockStyles" :key="s.code" :label="s.displayName" :value="s.code" />
            </el-select>
            <!-- 債券 / 債券型基金 → 短/中/長期 -->
            <el-select
              v-else-if="row.effectiveAssetClass === 'BOND'"
              :model-value="row.bondTerm ?? ''"
              size="small" style="width: 128px"
              @change="(v) => setTerm(row, v)">
              <el-option label="自動（年期）" value="" />
              <el-option v-for="t in bondTerms" :key="t.code" :label="t.displayName" :value="t.code" />
            </el-select>
            <span v-else style="color:#cbd5e1">不適用</span>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="!loading && securities.length === 0" class="empty-note">
        尚無標的資料（建立含股票的快照後即會出現）。
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { Search } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { bffApi } from '@/api'

const loading = ref(false)
const savingThreshold = ref(false)
const securities = ref([])
const categories = ref([])
const stockStyles = ref([])
const bondTerms = ref([])
const keyword = ref('')
const thresholdPct = ref(4)

// 個股可指定的資產類別：排除「現金」（標的不會是現金）
const assignableCategories = computed(() =>
  categories.value.filter(c => c.code !== 'CASH'))

const incomeStyle = computed(() => stockStyles.value.find(s => s.code === 'INCOME'))

const CLASS_COLORS = { CASH: '#3b82f6', BOND: '#14b8a6', STOCK: '#f59e0b' }
const STYLE_COLORS = { GROWTH: '#fcd34d', INCOME: '#d97706' }
const TERM_COLORS  = { SHORT: '#5eead4', MID: '#2dd4bf', LONG: '#0f766e' }
function classColor(code) { return CLASS_COLORS[code] || '#94a3b8' }
function styleColor(code) { return STYLE_COLORS[code] || '#94a3b8' }
function termColor(code)  { return TERM_COLORS[code]  || '#94a3b8' }
function labelOf(list, code) { const x = list.find(i => i.code === code); return x ? x.displayName : (code || '—') }
function classLabel(code) { return labelOf(categories.value, code) }
function styleLabel(code) { return labelOf(stockStyles.value, code) }
function termLabel(code)  { return labelOf(bondTerms.value, code) }

const filteredSecurities = computed(() => {
  // Defensive UI fence for a stale BFF during a rolling deployment.  The
  // authoritative exclusion is InstitutionService#getAllSecurities; this
  // must never delete or reclassify the TAIEX source record itself.
  const classifiable = securities.value.filter(s =>
    !(s?.market === '台股' && String(s?.code) === '0000'))
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return classifiable
  return classifiable.filter(s =>
    (s.code || '').toLowerCase().includes(kw) ||
    (s.name || '').toLowerCase().includes(kw))
})

async function load() {
  loading.value = true
  try {
    const [cats, styles, terms, secs] = await Promise.allSettled([
      bffApi.assetClassSettings.getCategories(),
      bffApi.assetClassSettings.getStockStyles(),
      bffApi.assetClassSettings.getBondTerms(),
      bffApi.assetClassSettings.getSecurities()
    ])
    categories.value = cats.status === 'fulfilled' ? cats.value : []
    stockStyles.value = styles.status === 'fulfilled' ? styles.value : []
    bondTerms.value = terms.status === 'fulfilled' ? terms.value : []
    securities.value = secs.status === 'fulfilled' ? secs.value : []
    const inc = incomeStyle.value
    if (inc && inc.dividendThreshold != null) thresholdPct.value = Number(inc.dividendThreshold) * 100
  } finally {
    loading.value = false
  }
}

async function setClass(row, value) {
  const updated = await bffApi.assetClassSettings.setSecurityAssetClass({
    code: row.code, market: row.market, assetClass: value || null
  })
  Object.assign(row, updated)
  ElMessage.success(value ? `已指定為「${classLabel(updated.effectiveAssetClass)}」` : '已還原為自動')
}

async function setStyle(row, value) {
  const updated = await bffApi.assetClassSettings.setSecurityStockStyle({
    code: row.code, market: row.market, stockStyle: value || null
  })
  Object.assign(row, updated)
  ElMessage.success(value ? `風格已指定為「${styleLabel(updated.effectiveStockStyle)}」` : '風格已還原為自動')
}

async function setTerm(row, value) {
  const updated = await bffApi.assetClassSettings.setSecurityBondTerm({
    code: row.code, market: row.market, bondTerm: value || null
  })
  Object.assign(row, updated)
  ElMessage.success(value ? `期別已指定為「${termLabel(updated.effectiveBondTerm)}」` : '期別已還原為自動')
}

async function saveThreshold() {
  const inc = incomeStyle.value
  if (!inc) { ElMessage.error('尚未載入「收益型」設定，請重試'); return }
  savingThreshold.value = true
  try {
    await bffApi.assetClassSettings.updateStockStyle(inc.id, {
      displayName: inc.displayName,
      sortOrder: inc.sortOrder,
      dividendThreshold: Number((thresholdPct.value / 100).toFixed(4))
    })
    ElMessage.success(`收益型門檻已設為 ${thresholdPct.value}%`)
    await load()
  } finally {
    savingThreshold.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.page-header { display: flex; align-items: center; gap: 12px; margin-bottom: 20px; }
.page-header h2 { margin: 0; flex: 1; }
.threshold-bar { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; color: #475569; }
.empty-note { padding: 24px; text-align: center; color: #94a3b8; }
</style>
