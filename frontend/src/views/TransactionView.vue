<template>
  <div>
    <!-- 年度彙總卡 -->
    <el-row :gutter="16" style="margin-bottom:20px">
      <el-col :span="6">
        <el-card class="year-card" :class="{ active: selectedYear === null }" @click="selectedYear = null">
          <div class="year-title">全部年度</div>
          <div class="year-sub">買 {{ totalBuyCount }} 筆　賣 {{ totalSellCount }} 筆</div>
          <div class="year-sub">買入 {{ fmt(totalBuyAmountTwd) }}</div>
          <div class="year-sub">賣出 {{ fmt(totalSellAmountTwd) }}</div>
        </el-card>
      </el-col>
      <el-col :span="6" v-for="s in summaries" :key="s.year">
        <el-card class="year-card" :class="{ active: selectedYear === s.year }" @click="selectedYear = s.year">
          <div class="year-title">{{ s.year }} 年</div>
          <div class="year-sub">買 {{ s.buyCount }} 筆　賣 {{ s.sellCount }} 筆</div>
          <div class="year-sub buy">買入 {{ fmt(s.totalBuyAmountTwd) }}</div>
          <div class="year-sub sell">賣出 {{ fmt(s.totalSellAmountTwd) }}</div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 明細表格 -->
    <el-card>
      <template #header>
        <div style="display:flex;align-items:center;justify-content:space-between">
          <span class="section-title">{{ selectedYear ? selectedYear + ' 年交易紀錄明細' : '全部交易紀錄明細' }}</span>
          <div style="display:flex;gap:8px">
            <el-button size="small" :icon="Download" :loading="exporting" @click="handleExport">匯出 Excel</el-button>
            <el-button type="primary" size="small" :icon="Plus" @click="openCreateDialog">新增</el-button>
          </div>
        </div>
      </template>

      <el-tabs v-model="marketFilter" class="market-tabs">
        <el-tab-pane label="全部" name="" />
        <el-tab-pane label="台股" name="台股" />
        <el-tab-pane label="美股" name="美股" />
        <el-tab-pane label="英股" name="英股" />
      </el-tabs>

      <el-empty v-if="!filteredRecords.length" description="尚無記錄，請點擊「新增」新增第一筆" />

      <el-table v-else :data="filteredRecords" size="small" stripe class="tx-table"
        @row-dblclick="onRowDblClick">
        <el-table-column prop="assetName" label="資產名稱" min-width="170" show-overflow-tooltip />
        <el-table-column prop="assetCode" label="代號" width="80">
          <template #default="{ row }">{{ row.assetCode || '-' }}</template>
        </el-table-column>
        <el-table-column label="交易類型" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.transactionType === '買' ? 'danger' : 'success'" size="small">{{ row.transactionType }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="資產類型" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.assetType === '基金' ? 'warning' : 'primary'" size="small" effect="plain">{{ row.assetType }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="tradeDate" label="交易日期" width="105" />
        <el-table-column label="數量" align="right" width="100">
          <template #default="{ row }">
            <span v-if="row.shares != null">{{ fmtShares(row.shares, row.market) }}</span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="單價" align="right" width="120">
          <template #default="{ row }">
            <span v-if="row.price != null">{{ fmtPrice(row.price) }}</span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="成交金額" align="right" width="115">
          <template #default="{ row }">{{ fmtCurrency(row.amount, row.currency) }}</template>
        </el-table-column>
        <el-table-column label="台幣成交金額" align="right" width="120">
          <template #default="{ row }">
            <el-tooltip
              v-if="row.currency === 'USD' && row.exchangeRate"
              :content="`匯率：${Number(row.exchangeRate).toFixed(4)}（${row.tradeDate}）`"
              placement="top"
              effect="light"
            >
              <span style="cursor:default">{{ fmt(row.amountTwd) }}</span>
            </el-tooltip>
            <span v-else>{{ fmt(row.amountTwd) }}</span>
          </template>
        </el-table-column>
        <!-- Task 268：判 != null 而非 truthy——後者會把「確實免收 0 元」也顯示成「沒記」的 - -->
        <el-table-column label="手續費" align="right" width="90">
          <template #default="{ row }">
            <span v-if="row.fee != null">{{ fmtCurrency(row.fee, row.currency) }}</span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="證交稅" align="right" width="90">
          <template #default="{ row }">
            <span v-if="row.transactionTax != null">{{ fmtCurrency(row.transactionTax, row.currency) }}</span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="市場" width="70" align="center">
          <template #default="{ row }">{{ row.market || '-' }}</template>
        </el-table-column>
        <el-table-column label="幣別" width="60" align="center">
          <template #default="{ row }">
            <el-tag :type="row.currency === 'USD' ? 'success' : 'info'" size="small" effect="plain">{{ row.currency || 'TWD' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="channel" label="券商通路" width="100" show-overflow-tooltip>
          <template #default="{ row }">{{ row.channel || '-' }}</template>
        </el-table-column>
        <el-table-column prop="notes" label="備註" width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.notes || '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="80" fixed="right" align="center">
          <template #default="{ row }">
            <el-button size="small" :icon="Edit" link type="primary" @click="openEditDialog(row)" />
            <el-popconfirm title="確定刪除？" @confirm="handleDelete(row.id)">
              <template #reference>
                <el-button size="small" :icon="Delete" link type="danger" />
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 排程自動匯出設定（Requirement 49 / Task t238；Task 255 起每人可多筆） -->
    <el-card style="margin-top:20px">
      <template #header>
        <div style="display:flex;align-items:center;justify-content:space-between">
          <span class="section-title">⏱️ 排程自動匯出</span>
          <el-button size="small" type="primary" :icon="Plus" :loading="addingSchedule" @click="addSchedule">
            新增排程
          </el-button>
        </div>
      </template>

      <el-empty v-if="!schedules.length" description="尚未建立排程，按右上「新增排程」開始。" :image-size="80" />

      <!-- 每一筆排程一個區塊：時間／資料夾／Drive 設定與執行狀態都各自持有 -->
      <div v-for="(s, idx) in schedules" :key="s.id" class="schedule-row">
        <div class="schedule-row-head">
          <span class="schedule-row-title">{{ s.name || `排程 #${idx + 1}` }}</span>
          <div style="display:flex;gap:8px">
            <el-button size="small" :icon="Download" :loading="s._running" @click="handleRunNow(idx)">
              立即匯出
            </el-button>
            <el-button size="small" type="primary" :loading="s._saving" @click="saveSchedule(idx)">儲存</el-button>
            <el-popconfirm title="確定刪除這筆排程？" width="200" @confirm="handleDeleteSchedule(idx)">
              <template #reference>
                <el-button size="small" type="danger" :icon="Delete" :loading="s._deleting">刪除</el-button>
              </template>
            </el-popconfirm>
          </div>
        </div>
        <el-form :inline="true" label-width="100px" class="schedule-form">
          <el-form-item label="名稱">
            <el-input v-model="s.name" maxlength="20" show-word-limit style="width:190px"
              placeholder="（選填，會進檔名）" />
          </el-form-item>
          <el-form-item label="啟用每日排程">
            <el-switch v-model="s.enabled" />
          </el-form-item>
          <el-form-item label="每日執行時間">
            <el-time-picker v-model="s._time" format="HH:mm" value-format="HH:mm"
              placeholder="時:分" style="width:130px" />
          </el-form-item>
          <el-form-item label="輸出資料夾">
            <el-input v-model="s.outputSubpath" readonly placeholder="（家目錄根）" style="width:240px">
              <template #append>
                <el-button :icon="FolderOpened" @click="openDirPicker(idx)">選擇</el-button>
              </template>
            </el-input>
          </el-form-item>
          <!--
            Google Drive 同步（Requirement 51 / Task 243）：本機一律照寫，這裡只是額外多上傳一份副本。
            僅「主要管理者」可見可設——rclone remote 全機只有一份且綁定某個 Google 帳號，
            若其他使用者能啟用，他的財務報表會被上傳到那個帳號的雲端硬碟。真正的閘門在後端。
          -->
          <el-form-item v-if="auth.isConfiguredAdmin" label="同步 Google Drive">
            <div style="display:flex; flex-direction:column; gap:6px">
              <div style="display:flex; align-items:center; gap:12px">
                <el-switch v-model="s.gdriveEnabled" />
                <el-input
                  v-model="s.gdriveSubpath"
                  readonly
                  placeholder="（尚未選擇 Drive 資料夾）"
                  :disabled="!s.gdriveEnabled"
                  style="width:260px"
                >
                  <template #append>
                    <el-button :disabled="!s.gdriveEnabled" @click="openDirPicker(idx, 'gdrive')">選擇</el-button>
                  </template>
                </el-input>
              </div>
              <div style="font-size:12px; color:var(--el-text-color-secondary); line-height:1.7">
                開啟後除了寫入上面的本機資料夾，會<strong>再上傳一份同樣的檔案</strong>到 Google Drive 的所選資料夾；
                <strong>本機那一份永遠照寫、不受影響</strong>。
                <template v-if="s.gdriveEnabled && s.gdriveSubpath">
                  <br />Drive 落點：<code>{{ gdriveRemote || 'GDriveOutput' }}:{{ s.gdriveSubpath }}</code>
                </template>
                <br />上次上傳：
                <template v-if="s.gdriveLastRunAt">
                  {{ s.gdriveLastRunAt }} — <code>{{ s.gdriveLastStatus || '—' }}</code>
                </template>
                <template v-else>—（尚未執行過）</template>
              </div>
            </div>
          </el-form-item>
        </el-form>
        <div class="schedule-status">
          上次執行：{{ s.lastRunAt || '—' }}　{{ s.lastRunStatus || '' }}
        </div>
      </div>

      <div class="schedule-hint">
        以主機家目錄 <code>{{ baseDir || '/home/steven' }}</code> 為根（對映主機
        <code>/Users/steven</code>）。按「選擇」開啟檔案總管式選擇器挑選子資料夾；例如選 <code>input</code> →
        主機 <code>/Users/steven/input</code>。每筆排程於各自時間匯出交易紀錄為
        <code>交易紀錄_{使用者ID}_YYYYMMDD.xlsx</code> 與 <code>.json</code> <strong>兩份</strong>（主檔名相同）；<strong>填了名稱的排程</strong>檔名為
        <code>交易紀錄_{使用者ID}_{名稱}_YYYYMMDD.xlsx</code>（同樣兩份；內容同上方「匯出 Excel」，涵蓋全部年度）。
        <br />兩筆排程若指到<strong>同一資料夾且同檔名</strong>（都沒填名稱或名稱相同），後執行的會覆寫前一份。
        每一份都是<strong>執行當下</strong>的完整交易紀錄（不會缺年度），但不是同一時點的快照——兩次執行之間新增或修改的交易
        只會出現在後面那一份。要各時段各留一份，請填不同名稱。
        <br />「立即匯出」使用的是<strong>已儲存</strong>的設定；剛改過還沒按「儲存」的值不會生效。
      </div>
    </el-card>

    <!-- 輸出資料夾選擇器（檔案總管式樹狀） -->
    <el-dialog v-model="dirPicker.visible" :title="dirPickerTitle" width="560px">
      <div class="dir-picker-path">
        目前選擇：<code>{{ dirPickerPreview }}</code>
      </div>
      <!-- Drive 端讀取失敗必須顯示原因；空樹會被誤讀為「Drive 裡沒有資料夾」而以為選錯位置 -->
      <el-alert
        v-if="dirPicker.error"
        type="error"
        :closable="false"
        show-icon
        style="margin-bottom:12px"
        :title="dirPicker.error"
      />
      <el-tree
        :key="dirPicker.treeKey"
        lazy
        :load="loadDirNode"
        :props="dirTreeProps"
        node-key="key"
        highlight-current
        :expand-on-click-node="false"
        :default-expanded-keys="['__root__']"
        class="dir-tree"
        @node-click="onDirNodeClick" />
      <div class="dir-new-sub">
        <span class="dns-label">新增子資料夾</span>
        <el-input v-model="dirPicker.newSub" placeholder="（選填）在所選資料夾下新增，寫檔時自動建立"
          style="width:340px" clearable />
      </div>
      <template #footer>
        <el-button @click="dirPicker.visible = false">取消</el-button>
        <el-button type="primary" @click="confirmDirPick">確定</el-button>
      </template>
    </el-dialog>

    <StockAnalysisDialog v-model="analysisVisible" :stock="analysisStock" />

    <!-- 新增/編輯 dialog -->
    <el-dialog v-model="dialogVisible" :title="editingId ? '編輯交易紀錄' : '新增交易紀錄'" width="760px"
      @closed="resetForm">
      <el-form ref="formRef" :model="txForm" :rules="rules" label-width="90px" size="default">
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="交易類型" prop="transactionType">
              <el-select v-model="txForm.transactionType" style="width:100%">
                <el-option v-for="t in TX_TYPES" :key="t" :value="t" :label="t" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="資產類型" prop="assetType">
              <el-select v-model="txForm.assetType" style="width:100%">
                <el-option v-for="t in ASSET_TYPES" :key="t" :value="t" :label="t" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="代號">
              <el-input v-model="txForm.assetCode" @blur="autoFillAssetName" @change="autoFillAssetName"
                placeholder="輸入代號自動帶出名稱（股票）" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="資產名稱" prop="assetName">
              <el-input v-model="txForm.assetName" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="8">
            <el-form-item label="市場" label-width="50px">
              <el-select v-model="txForm.market" clearable placeholder="選擇市場" style="width:100%">
                <el-option v-for="m in marketOptions" :key="m.code" :value="m.code" :label="m.label" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="幣別" label-width="50px">
              <el-select v-model="txForm.currency" style="width:100%">
                <el-option value="TWD" label="TWD" />
                <el-option value="USD" label="USD" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="交易日期" prop="tradeDate" label-width="90px">
              <el-date-picker v-model="txForm.tradeDate" type="date" value-format="YYYY-MM-DD" style="width:100%"
                :clearable="false" @change="onTradeDateChange" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="券商通路">
              <el-select v-model="txForm.channel" filterable allow-create clearable default-first-option
                placeholder="選擇或輸入券商／通路" style="width:100%">
                <el-option v-for="b in brokerOptions" :key="b" :value="b" :label="b" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="數量">
              <el-input v-model="txForm.sharesStr" :input-style="{ textAlign: 'right' }"
                @blur="onBlurField('shares', 5)" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="單價">
              <el-input v-model="txForm.priceStr" :input-style="{ textAlign: 'right' }"
                @blur="onBlurField('price', 6)" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="成交金額" prop="amountStr">
              <el-input v-model="txForm.amountStr" :input-style="{ textAlign: 'right' }"
                @blur="onBlurField('amount', txForm.currency === 'USD' ? 2 : 0)" />
            </el-form-item>
          </el-col>
        </el-row>
        <!--
          Task 268：手續費／證交稅。兩欄皆選填、皆常駐顯示——刻意不依交易類型或市場條件顯示，
          英股印花稅課在「買進」，綁死「賣才有稅」會使英股買進的稅無處可記；台股買進不課證交稅
          由使用者留空表達。留空存 null（＝沒記）、明確填 0 存 0（＝確實免收），兩者語意不同。
        -->
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="手續費">
              <el-input v-model="txForm.feeStr" :input-style="{ textAlign: 'right' }"
                placeholder="選填" @blur="onBlurField('fee', txForm.currency === 'USD' ? 2 : 0)" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="證交稅">
              <el-input v-model="txForm.transactionTaxStr" :input-style="{ textAlign: 'right' }"
                placeholder="選填" @blur="onBlurField('transactionTax', txForm.currency === 'USD' ? 2 : 0)" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="匯率" v-if="txForm.currency === 'USD'">
              <el-input v-model="txForm.exchangeRateStr" :disabled="!fxError"
                :input-style="{ textAlign: 'right' }" @blur="onBlurField('exchangeRate', 4)" />
              <span class="fx-hint fx-hint-warn" v-if="fxError">匯率服務暫時無法連線，可自行輸入或稍後再試</span>
              <span class="fx-hint" v-else-if="fxNotFound">查無 {{ txForm.tradeDate }} 或之前的匯率，可留空儲存</span>
              <span class="fx-hint" v-else-if="fxRateDate && fxRateDate !== txForm.tradeDate">
                採用 {{ fxRateDate }} 匯率（交易日無牌告）
              </span>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="台幣成交金額" label-width="110px">
              <strong>{{ fmt(computedAmountTwd) }}</strong>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="24">
            <el-form-item label="備註">
              <el-input v-model="txForm.notes" type="textarea" :rows="2" maxlength="500" show-word-limit />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submit" :loading="saving">儲存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { Plus, Edit, Delete, Download, FolderOpened } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'
import { bffApi } from '@/api'
import { showGdriveSelfCheckWarning } from '@/utils/gdriveSelfCheck'
import { showDualExportResult } from '@/utils/dualExportMessage'
import { useAuthStore } from '@/stores/authStore'
import { todayLocal } from '@/utils/localDate'
import StockAnalysisDialog from '@/components/StockAnalysisDialog.vue'

const TX_TYPES = ['買', '賣']
const ASSET_TYPES = ['股票', '基金']

const summaries = ref([])
const marketOptions = ref([])   // { code, label }
const brokerOptions = ref([])   // displayName 字串
const selectedYear = ref(null)
const marketFilter = ref('')   // 市場 tab：''＝全部，否則 台股/美股/英股
const dialogVisible = ref(false)
const saving = ref(false)
const exporting = ref(false)
const editingId = ref(null)
const formRef = ref(null)

// 排程自動匯出設定（Task t238；Task 255 起每人可多筆）
// baseDir／gdriveRemote 是後端給的顯示值、全清單共用一份（不入庫、不放進單筆內）
const schedules = ref([])
const baseDir = ref('')
const gdriveRemote = ref('')
const addingSchedule = ref(false)

// 輸出資料夾選擇器（檔案總管式樹狀）
// mode：'local'＝本機家目錄樹、'gdrive'＝Drive remote 樹（回傳形狀相同，共用同一棵 el-tree）
// rowIndex：dialog 只有一個，必須記住這次是為哪一筆排程而開
const dirPicker = reactive({
  visible: false, mode: 'local', rowIndex: -1, baseDir: '', picked: '', newSub: '', treeKey: 0, error: ''
})
const auth = useAuthStore()
const dirTreeProps = { label: 'name', isLeaf: 'leaf' }

const rules = {
  transactionType: [{ required: true, message: '請選擇交易類型', trigger: 'change' }],
  assetType: [{ required: true, message: '請選擇資產類型', trigger: 'change' }],
  assetName: [{ required: true, message: '請輸入資產名稱', trigger: 'blur' }],
  tradeDate: [{ required: true, message: '請選擇交易日期', trigger: 'change' }],
  amountStr: [{ required: true, message: '請輸入成交金額', trigger: 'blur' }]
}

// ===== Form with string fields for free typing =====
const txForm = reactive({
  transactionType: '買', assetType: '股票', assetName: '', assetCode: '',
  market: '台股', currency: 'TWD', channel: '', tradeDate: '', notes: '',
  sharesStr: '', priceStr: '', amountStr: '', exchangeRateStr: '',
  // Task 268：手續費／證交稅（選填）。空字串一律送 null，不得送 0——null＝「沒記」、0＝「確實免收」
  feeStr: '', transactionTaxStr: ''
})

// 市場改變時自動切換預設幣別（判斷式與 resetForm 共用 defaultCurrency，不另立第二套對照）
watch(() => txForm.market, (m) => {
  txForm.currency = defaultCurrency(m)
})

// Parse / format helpers（比照 RealizedGainView）
const parseNum = (s) => {
  const n = parseFloat(String(s || '').replace(/,/g, ''))
  return isNaN(n) ? 0 : n
}
const fmtNum = (v, precision) => {
  if (v === null || v === undefined || v === '') return ''
  const n = Number(v)
  if (isNaN(n)) return ''
  const s = precision > 0 ? n.toFixed(precision) : String(Math.round(n))
  const dot = s.indexOf('.')
  const intPart = dot >= 0 ? s.slice(0, dot) : s
  const decPart = dot >= 0 ? s.slice(dot) : ''
  return intPart.replace(/\B(?=(\d{3})+(?!\d))/g, ',') + decPart
}

const fieldMap = {
  shares: 'sharesStr',
  price: 'priceStr',
  amount: 'amountStr',
  exchangeRate: 'exchangeRateStr',
  fee: 'feeStr',
  transactionTax: 'transactionTaxStr'
}
const onBlurField = (field, precision) => {
  const strKey = fieldMap[field]
  if (!String(txForm[strKey] || '').trim()) { txForm[strKey] = ''; return }
  txForm[strKey] = fmtNum(parseNum(txForm[strKey]), precision)
}

// 輸入代號後自動帶出股名（僅股票類；查 stock 主檔，走同一支 business API，比照 RealizedGainView）
const autoFillAssetName = async () => {
  const code = (txForm.assetCode || '').trim().toUpperCase()
  if (!code) return
  txForm.assetCode = code
  if (txForm.assetType !== '股票') return          // 基金不查主檔
  if (txForm.assetName && txForm.assetName.trim()) return   // 已有名稱不覆寫（使用者手填優先）
  try {
    const res = await bffApi.transaction.lookupName({ code, market: txForm.market })
    const name = res?.stockName || res?.name
    if (name) txForm.assetName = name
  } catch (e) {
    /* 查無或錯誤靜默，由使用者手填 */
  }
}

// ===== Task 251：匯率依交易日期自動帶出（唯讀） =====
const fxRateDate = ref('')      // 實際採用的匯率日期（可能早於交易日期：假日往前退）
const fxNotFound = ref(false)   // 查無（BFF 對 404 回 200 {}）：該日之前沒有任何 USD 列
const fxError = ref(false)      // 查詢失敗（5xx／逾時／連線中斷）：解除唯讀讓使用者手填
const originalFxStr = ref('')   // 編輯時該筆自己已存的匯率，供切換幣別後還原
const fxDateDirty = ref(false)  // 使用者是否「實際改動過」交易日期
let fxSeq = 0                   // 競態序號：只認最後一次

const clearFx = () => {
  txForm.exchangeRateStr = ''
  fxRateDate.value = ''; fxNotFound.value = false; fxError.value = false
}

const refreshExchangeRate = async () => {
  const date = txForm.tradeDate
  const seq = ++fxSeq                        // 必須在 early-return 之前遞增，才能讓 in-flight 的舊查詢作廢
  if (txForm.currency !== 'USD' || !date) { clearFx(); return }
  try {
    const res = await bffApi.transaction.exchangeRate(date)
    if (seq !== fxSeq) return                // 已有更新的查詢，丟棄本次結果
    const rate = res?.midRate
    if (rate != null) {
      txForm.exchangeRateStr = fmtNum(Number(rate), 4)
      fxRateDate.value = res.rateDate || ''
      fxNotFound.value = false; fxError.value = false
    } else {                                 // 查無牌告（BFF 已把 404 降級成 200 {}）
      clearFx(); fxNotFound.value = true
    }
  } catch (e) {                              // 非 4xx：取不到，不是沒有 → 解鎖讓使用者手填
    if (seq !== fxSeq) return
    clearFx(); fxError.value = true
  }
}

const onTradeDateChange = () => { fxDateDirty.value = true; refreshExchangeRate() }

// 幣別切到 USD 時補匯率；切離 USD 時清掉，避免殘值
watch(() => txForm.currency, (c) => {
  if (c !== 'USD') { fxSeq++; clearFx(); return }          // fxSeq++ 使 in-flight 查詢作廢
  if (originalFxStr.value && !fxDateDirty.value) {          // 編輯中且日期未被改動 → 還原原值，不重查
    txForm.exchangeRateStr = originalFxStr.value
    return
  }
  if (!String(txForm.exchangeRateStr || '').trim()) refreshExchangeRate()
})

// 台幣成交金額即時預覽
const computedAmountTwd = computed(() => {
  const amount = parseNum(txForm.amountStr)
  const rate = parseNum(txForm.exchangeRateStr)
  if (txForm.currency === 'USD' && rate > 0) return amount * rate
  return amount
})

// ===== 載入 =====
async function load() {
  const data = await bffApi.transaction.list()
  summaries.value = data.summaries ?? []
  marketOptions.value = (data.markets ?? []).map(m => ({ code: m.code, label: m.displayName || m.code }))
  brokerOptions.value = (data.brokers ?? []).map(b => b.displayName)
}

async function loadSchedules() {
  applySchedules(await bffApi.transaction.listExportSchedules())
}

// 多 panel 並行載入
onMounted(() => {
  Promise.allSettled([load(), loadSchedules()])
})

// ===== 明細（依 selectedYear 過濾 summaries[].records）=====
const filteredRecords = computed(() => {
  const byYear = selectedYear.value === null
    ? summaries.value.flatMap(s => s.records || [])
    : (summaries.value.find(x => x.year === selectedYear.value)?.records || [])
  return marketFilter.value ? byYear.filter(r => r.market === marketFilter.value) : byYear
})

const totalBuyCount = computed(() => summaries.value.reduce((a, s) => a + (s.buyCount || 0), 0))
const totalSellCount = computed(() => summaries.value.reduce((a, s) => a + (s.sellCount || 0), 0))
const totalBuyAmountTwd = computed(() => summaries.value.reduce((a, s) => a + Number(s.totalBuyAmountTwd || 0), 0))
const totalSellAmountTwd = computed(() => summaries.value.reduce((a, s) => a + Number(s.totalSellAmountTwd || 0), 0))

// ===== Display formatters =====
const fmt = (v) => {
  if (v == null) return '-'
  const n = Number(v)
  return `$${n.toLocaleString('zh-TW', { maximumFractionDigits: 0 })}`
}
const fmtCurrency = (v, currency) => {
  if (v == null) return '-'
  const n = Number(v)
  const decimals = currency === 'USD' ? 2 : 0
  return `$${n.toLocaleString('zh-TW', { minimumFractionDigits: decimals, maximumFractionDigits: decimals })}`
}
// 單價固定顯示 6 位小數（Task 239）
const fmtPrice = (v) => {
  if (v == null) return '-'
  return `$${Number(v).toLocaleString('zh-TW', { minimumFractionDigits: 6, maximumFractionDigits: 6 })}`
}
const fmtShares = (v, market) => {
  if (v == null) return '-'
  const n = Number(v)
  if (market === '美股' || market === '英股') {
    return n.toLocaleString('en-US', { minimumFractionDigits: 0, maximumFractionDigits: 5 })
  }
  return n.toLocaleString('zh-TW', { maximumFractionDigits: 0 })
}

// ===== Dialog actions =====
// Task 250：新增表單的市場預設＝當前市場 tab（''＝全部 → 台股）
const defaultMarket = () => marketFilter.value || '台股'
// 幣別必須在 resetForm 內一併算：tab 未變時 market 同值寫回不會 trigger 上面那個 watch
const defaultCurrency = (m) => (m === '美股' || m === '英股') ? 'USD' : 'TWD'

const resetForm = () => {
  editingId.value = null
  const market = defaultMarket()
  Object.assign(txForm, {
    transactionType: '買', assetType: '股票', assetName: '', assetCode: '',
    market, currency: defaultCurrency(market), channel: '', tradeDate: '', notes: '',
    sharesStr: '', priceStr: '', amountStr: '', exchangeRateStr: '',
    feeStr: '', transactionTaxStr: ''
  })
  // Task 251：清掉上一次的匯率狀態，避免殘留到下一次開啟
  originalFxStr.value = ''; fxDateDirty.value = false; clearFx()
  formRef.value?.clearValidate()
}

const openCreateDialog = () => {
  resetForm()
  txForm.tradeDate = todayLocal()
  refreshExchangeRate()          // Task 251：預設日期（今天）的匯率
  dialogVisible.value = true
}

const openEditDialog = (row) => {
  editingId.value = row.id
  txForm.transactionType = row.transactionType || '買'
  txForm.assetType = row.assetType || '股票'
  txForm.assetName = row.assetName || ''
  txForm.assetCode = row.assetCode || ''
  txForm.market = row.market || ''
  txForm.currency = row.currency || ((row.market === '美股' || row.market === '英股') ? 'USD' : 'TWD')
  txForm.channel = row.channel || ''
  txForm.tradeDate = row.tradeDate || ''
  txForm.notes = row.notes || ''
  const isUsd = txForm.currency === 'USD'
  txForm.sharesStr = row.shares != null ? fmtNum(row.shares, (row.market === '美股' || row.market === '英股') ? 5 : 0) : ''
  txForm.priceStr = row.price != null ? fmtNum(row.price, 6) : ''
  txForm.amountStr = row.amount != null ? fmtNum(row.amount, isUsd ? 2 : 0) : ''
  // Task 268：null 帶空字串（不是 0）；使用者存的 0 會帶回 '0'，兩者在表單上仍可區分
  txForm.feeStr = row.fee != null ? fmtNum(row.fee, isUsd ? 2 : 0) : ''
  txForm.transactionTaxStr = row.transactionTax != null ? fmtNum(row.transactionTax, isUsd ? 2 : 0) : ''
  txForm.exchangeRateStr = row.exchangeRate != null ? fmtNum(row.exchangeRate, 4) : ''
  originalFxStr.value = txForm.exchangeRateStr   // Task 251：保留該筆自己的匯率，供切換幣別後還原
  dialogVisible.value = true
}

const submit = async () => {
  const ok = await formRef.value.validate().catch(() => false)
  if (!ok) return
  saving.value = true
  try {
    const shares = String(txForm.sharesStr || '').trim() ? parseNum(txForm.sharesStr) : null
    const price = String(txForm.priceStr || '').trim() ? parseNum(txForm.priceStr) : null
    const exchangeRate = (txForm.currency === 'USD' && String(txForm.exchangeRateStr || '').trim())
      ? parseNum(txForm.exchangeRateStr) : null
    // Task 268：先以 trim() 判空再決定要不要呼叫 parseNum——parseNum 對無法解析的字串回 0，
    // 直接呼叫會把「留空」變成「確實免收 0 元」，兩者語意不同（硬約束 G）
    const fee = String(txForm.feeStr || '').trim() ? parseNum(txForm.feeStr) : null
    const transactionTax = String(txForm.transactionTaxStr || '').trim()
      ? parseNum(txForm.transactionTaxStr) : null
    const payload = {
      transactionType: txForm.transactionType,
      assetType: txForm.assetType,
      assetName: txForm.assetName,
      assetCode: txForm.assetCode || null,
      market: txForm.market || null,
      currency: txForm.currency || null,
      channel: txForm.channel || null,
      tradeDate: txForm.tradeDate,
      shares, price,
      amount: parseNum(txForm.amountStr),
      fee, transactionTax,
      exchangeRate,
      notes: txForm.notes || null
    }
    if (editingId.value) {
      await bffApi.transaction.update(editingId.value, payload)
      ElMessage.success('更新成功')
    } else {
      await bffApi.transaction.create(payload)
      ElMessage.success('新增成功')
      selectedYear.value = txForm.tradeDate ? new Date(txForm.tradeDate).getFullYear() : selectedYear.value
    }
    dialogVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

const handleDelete = async (id) => {
  await bffApi.transaction.remove(id)
  await load()
  ElMessage.success('已刪除')
}

// ===== 雙擊開啟股票走勢分析（僅股票列，Task 307） =====
const analysisVisible = ref(false)
const analysisStock = ref(null)
function onRowDblClick(row) {
  if (row?.assetType !== '股票' || !row?.assetCode || !row?.market) return
  analysisStock.value = { stockCode: row.assetCode, stockName: row.assetName, market: row.market }
  analysisVisible.value = true
}

async function handleExport() {
  exporting.value = true
  try {
    const blob = await bffApi.transaction.exportExcel()
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `交易紀錄_${dayjs().format('YYYYMMDD')}.xlsx`
    document.body.appendChild(a); a.click(); document.body.removeChild(a)
    URL.revokeObjectURL(url)
    ElMessage.success('匯出完成')
  } finally {
    exporting.value = false
  }
}

// ===== 排程自動匯出（每人多筆） =====

/**
 * 把後端回的完整清單寫回本地狀態。所有變更端點都回「變更後的完整清單」，
 * 前端一律整份取代、不做客戶端合併，避免本地狀態與 DB 分歧。
 * `_time`／`_saving` 等底線開頭欄位是純 UI 狀態，送回後端前會被拿掉。
 * Drive 欄位讀取一律不驗證，不合法值也照顯示，供使用者自行修正。
 */
function applySchedules(res) {
  baseDir.value = res.baseDir ?? ''
  gdriveRemote.value = res.gdriveRemote ?? ''
  schedules.value = (res.schedules ?? []).map(s => ({
    id: s.id,
    name: s.name || '',
    enabled: !!s.enabled,
    runHour: s.runHour ?? 8,
    runMinute: s.runMinute ?? 0,
    outputSubpath: s.outputSubpath ?? 'input',
    lastRunAt: s.lastRunAt ?? null,
    lastRunStatus: s.lastRunStatus ?? null,
    gdriveEnabled: !!s.gdriveEnabled,
    gdriveSubpath: s.gdriveSubpath || '',
    gdriveLastRunAt: s.gdriveLastRunAt || null,
    gdriveLastStatus: s.gdriveLastStatus || '',
    _time: `${String(s.runHour ?? 8).padStart(2, '0')}:${String(s.runMinute ?? 0).padStart(2, '0')}`,
    _saving: false, _running: false, _deleting: false
  }))
  // 剛把某筆的 Drive 同步打開時後端會附一則自檢警告；正常時為 null，不顯示（Task 247.3.5）
  showGdriveSelfCheckWarning(res.gdriveSelfCheckWarning)
}

/** 新增：直接建一筆停用的預設排程，使用者再自行設定並儲存（不做前端草稿列，避免 id 為 null 的分支狀態）。 */
async function addSchedule() {
  addingSchedule.value = true
  try {
    applySchedules(await bffApi.transaction.createExportSchedule({
      enabled: false, runHour: 8, runMinute: 0, outputSubpath: 'input'
    }))
    ElMessage.success('已新增一筆排程，請設定時間與資料夾後儲存')
  } catch (e) {
    ElMessage.error(e?.response?.data?.detail || '新增排程失敗，請稍後再試')
  } finally {
    addingSchedule.value = false
  }
}

async function saveSchedule(idx) {
  const s = schedules.value[idx]
  if (!s) return
  // 前後端都擋：開了同步卻沒選資料夾，後端也會回 400
  if (s.gdriveEnabled && !(s.gdriveSubpath || '').trim()) {
    ElMessage.warning('已開啟 Google Drive 同步時，必須選擇 Drive 目標資料夾')
    return
  }
  s._saving = true
  try {
    const [h, m] = (s._time || '08:00').split(':').map(Number)
    applySchedules(await bffApi.transaction.updateExportSchedule(s.id, {
      name: (s.name || '').trim(),
      enabled: s.enabled,
      gdriveEnabled: s.gdriveEnabled,
      gdriveSubpath: (s.gdriveSubpath || '').trim(),
      runHour: h,
      runMinute: m,
      outputSubpath: (s.outputSubpath || 'input').trim()
    }))
    ElMessage.success('排程設定已儲存')
  } catch (e) {
    // 名稱不合法／時分越界等後端會回可讀訊息，直接顯示比「請稍後再試」有用
    ElMessage.error(e?.response?.data?.detail || '儲存失敗，請稍後再試')
    s._saving = false
  }
}

async function handleDeleteSchedule(idx) {
  const s = schedules.value[idx]
  if (!s) return
  s._deleting = true
  try {
    applySchedules(await bffApi.transaction.deleteExportSchedule(s.id))
    ElMessage.success('已刪除')
  } catch (e) {
    ElMessage.error(e?.response?.data?.detail || '刪除失敗，請稍後再試')
    s._deleting = false
  }
}

async function handleRunNow(idx) {
  const s = schedules.value[idx]
  if (!s) return
  s._running = true
  try {
    const r = await bffApi.transaction.runExportNow(s.id)
    // path 依契約一律指 xlsx、jsonPath 指 json（Requirement 55 / Task 282）
    showDualExportResult({ jsonPath: r.jsonPath, xlsxPath: r.path, gdriveStatus: r.gdriveStatus })
  } catch (e) {
    ElMessage.error(e?.response?.data?.detail || '立即匯出失敗，請確認目錄與權限')
  } finally {
    s._running = false
  }
  loadSchedules().catch(() => {}) // 刷新上次執行資訊，失敗不影響匯出結果
}


// 雙模式資料夾選擇器（Task 243，沿用 Task 241 於 CrawlerDataView 的寫法）
const dirPickerTitle = computed(() =>
  dirPicker.mode === 'gdrive' ? '選擇 Google Drive 資料夾' : '選擇輸出資料夾')

// 兩種 mode 的分隔符不同：Drive 基底是 `remote:`（已含冒號，後面直接接子路徑），
// 本機基底是 `/home/steven`（需要 `/` 分隔）。混用會顯示成 `GDriveOutput:/投資理財`——
// 多一個斜線、不是 rclone 的路徑格式，會誤導使用者。
const dirPickerPreview = computed(() => {
  const isGdrive = dirPicker.mode === 'gdrive'
  const base = dirPicker.baseDir
    || (isGdrive ? (gdriveRemote.value || 'GDriveOutput') + ':' : (baseDir.value || '/home/steven'))
  const parts = [dirPicker.picked, (dirPicker.newSub || '').trim()].filter(Boolean)
  const joined = parts.join('/')
  if (!joined) return base
  return isGdrive ? base + joined : base + '/' + joined
})

/** dialog 只有一個，故必須記住這次是為哪一筆排程（rowIndex）而開，確定時才寫得回正確的那一筆。 */
function openDirPicker(rowIndex, mode = 'local') {
  const s = schedules.value[rowIndex]
  dirPicker.rowIndex = rowIndex
  dirPicker.mode = mode
  dirPicker.picked = (mode === 'gdrive' ? s?.gdriveSubpath : s?.outputSubpath) || ''
  dirPicker.newSub = ''
  dirPicker.baseDir = ''         // 兩種 mode 的基底不同，重開時一律重新取
  dirPicker.error = ''
  dirPicker.treeKey++            // 強制 el-tree 重新懶載入 root
  dirPicker.visible = true
}

// el-tree 懶載入：level 0 以家目錄為單一 root；其餘列該節點子目錄
async function loadDirNode(node, resolve) {
  const browse = dirPicker.mode === 'gdrive'
    ? bffApi.transaction.browseGdriveExportDir
    : bffApi.transaction.browseExportDir
  try {
    if (node.level === 0) {
      const res = await browse('')
      dirPicker.baseDir = res.baseDir || ''
      dirPicker.error = ''
      resolve([{ name: res.baseDir || '/', path: '', key: '__root__', leaf: false }])
      return
    }
    const res = await browse(node.data.path || '')
    resolve((res.directories || []).map(d => ({ name: d.name, path: d.path, key: d.path, leaf: false })))
  } catch (e) {
    // Drive 端失敗要顯示原因（remote 未設定／授權失效）；空樹會被誤讀為「Drive 裡沒有資料夾」
    if (dirPicker.mode === 'gdrive') {
      dirPicker.error = e?.response?.data?.detail || e?.message || 'Google Drive 資料夾讀取失敗'
    }
    resolve([])
  }
}

const onDirNodeClick = (data) => { dirPicker.picked = data.path || '' }

function confirmDirPick() {
  let p = dirPicker.picked || ''
  const sub = (dirPicker.newSub || '').trim().replace(/^\/+|\/+$/g, '')
  if (sub) p = p ? `${p}/${sub}` : sub
  const s = schedules.value[dirPicker.rowIndex]
  if (s) {
    if (dirPicker.mode === 'gdrive') s.gdriveSubpath = p
    else s.outputSubpath = p
  }
  dirPicker.visible = false
}
</script>

<style scoped>
.section-title { font-size: 15px; font-weight: 600; }
.year-card { cursor: pointer; transition: all 0.2s; }
.year-card.active { border: 2px solid #3b82f6 !important; }
.year-card:hover { transform: translateY(-2px); box-shadow: 0 4px 16px rgba(0,0,0,0.1) !important; }
.year-card :deep(.el-card__body) { padding: 16px; text-align: center; }
.year-title { font-size: 16px; font-weight: 600; color: #1e293b; margin-bottom: 8px; }
.year-sub { font-size: 12px; color: #64748b; line-height: 1.7; }
.year-sub.buy { color: #16a34a; }
.year-sub.sell { color: #dc2626; }
.tx-table :deep(.el-table__cell) { font-size: 13.5px; }
.market-tabs :deep(.el-tabs__header) { margin-bottom: 8px; }

/* 匯率自動帶入的狀態提示（Task 251） */
.fx-hint { display: block; font-size: 12px; color: #94a3b8; line-height: 1.5; margin-top: 2px; }
.fx-hint-warn { color: var(--el-color-warning); }

/* 排程自動匯出設定（Task t238） */
.schedule-form { margin-bottom: 4px; }
.schedule-row { border: 1px solid #e2e8f0; border-radius: 8px; padding: 12px 16px 4px; margin-bottom: 12px; }
.schedule-row-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
.schedule-row-title { font-size: 14px; font-weight: 600; color: #1e293b; }
.schedule-hint { font-size: 12px; color: #94a3b8; line-height: 1.6; }
.schedule-hint code { background: #f1f5f9; color: #475569; padding: 1px 5px; border-radius: 4px; font-size: 11px; }
.schedule-status { margin-top: 8px; font-size: 12px; color: #64748b; }
.dir-picker-path { font-size: 13px; color: #475569; margin-bottom: 10px; }
.dir-picker-path code { background: #f1f5f9; color: #0f172a; padding: 2px 6px; border-radius: 4px; word-break: break-all; }
.dir-tree { max-height: 340px; overflow: auto; border: 1px solid #e2e8f0; border-radius: 6px; padding: 6px; }
.dir-new-sub { display: flex; align-items: center; gap: 10px; margin-top: 12px; }
.dir-new-sub .dns-label { font-size: 13px; color: #475569; white-space: nowrap; }
</style>
