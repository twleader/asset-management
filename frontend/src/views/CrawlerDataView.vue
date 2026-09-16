<template>
  <div>
    <!-- 查詢區 -->
    <el-card style="margin-bottom:20px">
      <template #header>
        <div class="toolbar">
          <span class="section-title">爬蟲資訊查詢</span>
          <span class="hint">查指定日期公開資訊爬蟲（NewsPoller）爬回的 news_headline 資料</span>
        </div>
      </template>

      <div class="query-bar">
        <div class="field">
          <span class="field-label">日期</span>
          <el-date-picker
            v-model="date"
            type="date"
            value-format="YYYY-MM-DD"
            :clearable="false"
            placeholder="選擇日期"
            style="width:160px"
          />
        </div>
        <div class="field">
          <span class="field-label">日期依據</span>
          <el-radio-group v-model="dateField" size="small">
            <el-radio-button label="fetched">爬取時間</el-radio-button>
            <el-radio-button label="published">資料日期</el-radio-button>
          </el-radio-group>
        </div>
        <div class="field">
          <span class="field-label">類別</span>
          <el-select v-model="category" size="small" clearable placeholder="全部" style="width:150px">
            <el-option v-for="c in CATEGORIES" :key="c.value" :label="c.label" :value="c.value" />
          </el-select>
        </div>
        <el-button type="primary" :loading="loading" @click="fetchData">
          <el-icon style="margin-right:4px"><Search /></el-icon>查詢
        </el-button>
        <span class="count" v-if="!loading">共 {{ rows.length }} 筆</span>
      </div>

      <el-alert
        type="info"
        :closable="false"
        show-icon
        style="margin:12px 0"
        title="「爬取時間」＝那天爬蟲實際抓回入庫（fetched_at）；「資料日期」＝新聞發布日／交易日（published_at）。區間以台北時間該日 00:00–翌日 00:00 計。"
      />

      <el-table :data="rows" v-loading="loading" stripe size="small" style="width:100%"
                empty-text="該日期查無爬回資料">
        <el-table-column label="資料日期" width="150" prop="publishedAt">
          <template #default="{ row }">{{ fmt(row.publishedAt) }}</template>
        </el-table-column>
        <el-table-column label="爬取時間" width="150" prop="fetchedAt">
          <template #default="{ row }">{{ fmt(row.fetchedAt) }}</template>
        </el-table-column>
        <el-table-column label="類別" width="120" prop="category">
          <template #default="{ row }">
            <el-tag :type="catType(row.category)" effect="light" size="small">{{ catLabel(row.category) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="來源" width="100" prop="source" />
        <el-table-column label="地區" width="70" prop="region">
          <template #default="{ row }">{{ row.region || '—' }}</template>
        </el-table-column>
        <el-table-column label="標題" min-width="280" prop="title">
          <template #default="{ row }">
            <a v-if="row.url" :href="row.url" target="_blank" rel="noopener" class="title-link">{{ row.title }}</a>
            <span v-else>{{ row.title }}</span>
          </template>
        </el-table-column>
        <el-table-column label="摘要" min-width="240" prop="summary" show-overflow-tooltip>
          <template #default="{ row }">{{ row.summary || '—' }}</template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 爬蟲執行時間設定 -->
    <el-card>
      <template #header>
        <div class="toolbar">
          <span class="section-title">爬蟲執行時間設定</span>
          <span class="hint">公開資訊新聞爬蟲（NewsPoller）每天執行的時間點，可設定多個</span>
        </div>
      </template>

      <el-alert
        v-if="!auth.isAdmin"
        type="warning"
        :closable="false"
        show-icon
        style="margin-bottom:12px"
        title="僅管理者可修改爬蟲執行時間，以下為唯讀顯示。"
      />
      <el-alert
        v-else
        type="info"
        :closable="false"
        show-icon
        style="margin-bottom:12px"
        title="設定後即時生效（免重啟），下一分鐘起依新時間執行。清空全部時間點＝該爬蟲不再自動執行。"
      />

      <div v-loading="scheduleLoading">
        <div v-for="(item, idx) in schedule" :key="idx" class="time-row">
          <el-time-picker
            v-model="item.time"
            value-format="HH:mm"
            format="HH:mm"
            placeholder="時間"
            :clearable="false"
            :disabled="!auth.isAdmin"
            style="width:130px"
          />
          <el-switch
            v-model="item.enabled"
            active-text="啟用"
            inactive-text="停用"
            :disabled="!auth.isAdmin"
            style="margin:0 12px"
          />
          <el-button
            v-if="auth.isAdmin"
            type="danger"
            plain
            size="small"
            @click="removeRow(idx)"
          >移除</el-button>
        </div>

        <div v-if="!schedule.length" class="empty-schedule">目前未設定任何執行時間點。</div>

        <div v-if="auth.isAdmin" class="schedule-actions">
          <el-button plain @click="addRow">
            <el-icon style="margin-right:4px"><Plus /></el-icon>新增時間點
          </el-button>
          <el-button type="primary" :loading="saving" @click="saveSchedule">儲存設定</el-button>
        </div>
      </div>
    </el-card>

    <!-- 爬蟲輸出檔案設定 -->
    <el-card style="margin-top:20px">
      <template #header>
        <div class="toolbar">
          <span class="section-title">爬蟲輸出檔案設定</span>
          <span class="hint">每輪抓取產生的公開資訊 JSON 要寫到哪個資料夾</span>
        </div>
      </template>

      <el-alert
        v-if="!auth.isAdmin"
        type="warning"
        :closable="false"
        show-icon
        style="margin-bottom:12px"
        title="僅管理者可修改爬蟲輸出路徑，以下為唯讀顯示。"
      />
      <el-alert
        v-else
        type="info"
        :closable="false"
        show-icon
        style="margin-bottom:12px"
        title="設定後即時生效（免重啟），下一輪抓取起寫入新資料夾。目錄不存在時會自動建立。"
      />

      <div>已儲存：{{ exportPath.outputSubpath || '家目錄根' }}；Drive {{ exportPath.gdriveEnabled ? '啟用' : '停用' }}</div>
      <el-button type="primary" :disabled="savingExportPath || !exportPathLoaded || !auth.isAdmin" @click="openExportDialog">編輯設定</el-button>
      <el-dialog v-model="outputSettingDialog.visible" title="編輯匯出設定" width="680px" :close-on-click-modal="!savingExportPath" :close-on-press-escape="!savingExportPath" :show-close="!savingExportPath" :before-close="closeExportDialog">
      <div v-if="outputSettingDialog.draft">
      <div v-loading="exportPathLoading">
        <div class="path-row">
          <span class="field-label">輸出資料夾</span>
          <el-input :disabled="savingExportPath"
            v-model="outputSettingDialog.draft.outputSubpath"
            readonly
            placeholder="（家目錄根）"
            style="width:340px"
          >
            <template #append>
              <el-button :disabled="savingExportPath || (!auth.isAdmin)" @click="openDirPicker('local')">選擇</el-button>
            </template>
          </el-input>

        </div>

        <!-- Google Drive 同步（Task 241）：本機照寫不變，這裡只是額外多上傳一份副本 -->
        <div class="path-row gdrive-row">
          <span class="field-label">同步 Google Drive</span>
          <el-switch v-model="outputSettingDialog.draft.gdriveEnabled" :disabled="savingExportPath || (!auth.isAdmin)" />
          <el-input
            v-model="outputSettingDialog.draft.gdriveSubpath"
            readonly
            placeholder="（尚未選擇 Drive 資料夾）"
            :disabled="savingExportPath || (!outputSettingDialog.draft.gdriveEnabled)"
            style="width:340px"
          >
            <template #append>
              <el-button
                :disabled="savingExportPath || (!auth.isAdmin || !outputSettingDialog.draft.gdriveEnabled)"
                @click="openDirPicker('gdrive')"
              >選擇</el-button>
            </template>
          </el-input>
        </div>

        <div class="path-hint">
          開啟後，每輪除了寫入上面的本機資料夾，會<strong>再上傳一份同樣的檔案</strong>到
          Google Drive 的所選資料夾。<strong>本機那一份永遠照寫、不受影響</strong>（SRPP 退休規劃專案讀的是本機檔）。
          <template v-if="outputSettingDialog.draft.gdriveEnabled && outputSettingDialog.draft.gdriveSubpath">
            <br />Drive 落點：
            <code>{{ outputSettingDialog.draft.gdriveRemote }}:{{ outputSettingDialog.draft.gdriveSubpath }}/public_info_{{ today }}.json</code>
          </template>
          <span v-if="gdriveDirty" class="path-dirty">
            ← 尚未儲存的變更，按「儲存設定」後生效
          </span>
          <br />
          上次上傳：
          <template v-if="outputSettingDialog.draft.gdriveLastRunAt">
            {{ outputSettingDialog.draft.gdriveLastRunAt }} —
            <code>{{ outputSettingDialog.draft.gdriveLastStatus || '—' }}</code>
          </template>
          <template v-else>—（尚未執行過）</template>
        </div>

        <div class="path-hint">
          以主機家目錄 <code>{{ outputSettingDialog.draft.baseDir || '/home/steven' }}</code>（對映主機
          <code>/Users/steven</code>）為根，只能選其下的子資料夾。目前落點：
          <code>{{ outputSettingDialog.draft.absolutePath || '—' }}/public_info_{{ today }}.json</code>
          <span v-if="exportPathDirty" class="path-dirty">
            ← 尚未儲存的變更：<code>{{ outputSettingDialog.draft.outputSubpath || '（家目錄根，儲存後將套用預設子資料夾）' }}</code>，
            按「儲存設定」後生效
          </span>
          <br />
          檔名固定為 <code>public_info_&lt;日期&gt;.json</code> 與 <code>.xlsx</code> <strong>兩份</strong>（主檔名相同；SRPP 退休規劃專案依 .json 這一份取用，故不開放修改）；
          同日多輪覆寫、跨日產生新檔。
          <template v-if="outputSettingDialog.draft.updatedAt">
            <br />上次修改：{{ outputSettingDialog.draft.updatedAt }}
          </template>
        </div>


      </div>
      </div>
      <template #footer><el-button :disabled="savingExportPath" @click="outputSettingDialog.visible = false">取消</el-button><el-button type="primary" :loading="savingExportPath" :disabled="savingExportPath" @click="saveExportPath">儲存設定</el-button></template>
      </el-dialog>
        <!-- 手動匯出兩顆（Requirement 63 / Task 280）：不必等排程時間點，也不必重啟容器靠 warmup。
             一顆在跑時另一顆停用——同時按必然有一顆拿到 BUSY -->
        <template v-if="auth.isAdmin">
          <div class="path-row run-row">
            <span class="field-label">手動匯出</span>
            <el-button
              type="primary"
              plain
              :loading="running === 'export'"
              :disabled="savingExportPath || (running !== '')"
              @click="runManual('export')"
            >
              <el-icon style="margin-right:4px"><Download /></el-icon>立即匯出
            </el-button>
            <el-button
              type="success"
              plain
              :loading="running === 'fetch'"
              :disabled="savingExportPath || (running !== '')"
              @click="runManual('fetch')"
            >
              <el-icon style="margin-right:4px"><Refresh /></el-icon>立即抓取並匯出
            </el-button>
          </div>

          <div class="path-hint">
            「立即匯出」＝<strong>只重產檔案</strong>（不重新抓取），內容與上一輪相同、適合改完設定後驗證落點，秒回；
            「立即抓取並匯出」＝<strong>完整跑一輪</strong>（重新抓新聞與公開資訊快照 → 入庫 → 產檔），會有最新資料。
            兩者都會產出 <code>.json</code> 與 <code>.xlsx</code> <strong>兩份</strong>，檔名與目錄和排程輪完全相同
            （同日覆寫當天那一份）；Google Drive 同步已啟用時，<strong>兩份都會上傳</strong>。
            上一輪還在跑時會提示「尚未結束」並略過，不會同時跑兩輪。
          </div>
        </template>
    </el-card>

    <!-- 輸出資料夾選擇器（本機／Google Drive 共用，由 dirPicker.mode 決定資料來源） -->
    <el-dialog v-model="dirPicker.visible" :title="dirPickerTitle" width="560px">
      <div class="dir-picker-path">
        目前選擇：<code>{{ dirPickerPreview }}</code>
      </div>
      <!-- Drive 端讀取失敗必須顯示原因；空樹會被誤讀為「Drive 裡沒有資料夾」 -->
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
  </div>
</template>

<script setup>
import { cloneExportSetting, replaceExportSetting } from '@/utils/exportSettingDraft'
import dayjs from 'dayjs'
import { ElMessage } from 'element-plus'
import { bffApi, apiErrorMessage } from '@/api'
import { showGdriveSelfCheckWarning } from '@/utils/gdriveSelfCheck'
import { useAuthStore } from '@/stores/authStore'

const auth = useAuthStore()

const CATEGORIES = [
  { value: 'news', label: '新聞' },
  { value: 'twse-institutional', label: '三大法人' },
  { value: 'twse-turnover', label: '大盤成交' },
  { value: 'fx', label: '台幣兌美元' },
  { value: 'us-market', label: '美股指數' },
  { value: 'kr-market', label: '韓股指數' }
]
const CAT_LABELS = Object.fromEntries(CATEGORIES.map(c => [c.value, c.label]))
const CAT_TYPES = {
  news: 'primary',
  'twse-institutional': 'success',
  'twse-turnover': 'warning',
  fx: 'danger',
  'us-market': 'info',
  'kr-market': 'info'
}
function catLabel(c) { return CAT_LABELS[c] || c || '—' }
function catType(c) { return CAT_TYPES[c] || 'info' }
function fmt(iso) { return iso ? dayjs(iso).format('YYYY-MM-DD HH:mm') : '—' }

// --- 查詢 ---
const date = ref(dayjs().format('YYYY-MM-DD'))
const dateField = ref('fetched')
const category = ref('')
const rows = ref([])
const loading = ref(false)

async function fetchData() {
  loading.value = true
  try {
    rows.value = (await bffApi.crawlerData.query(date.value, dateField.value, category.value || undefined)) || []
  } catch (e) {
    ElMessage.error('查詢失敗：' + (e?.response?.data?.detail || e.message))
    rows.value = []
  } finally {
    loading.value = false
  }
}

// --- 排程設定 ---
const schedule = ref([])
const scheduleLoading = ref(false)
const saving = ref(false)

function pad(n) { return String(n).padStart(2, '0') }

async function fetchSchedule() {
  scheduleLoading.value = true
  try {
    const data = (await bffApi.crawlerData.getSchedule()) || []
    schedule.value = data.map(s => ({ time: `${pad(s.hour)}:${pad(s.minute)}`, enabled: s.enabled }))
  } finally {
    scheduleLoading.value = false
  }
}

function addRow() {
  schedule.value.push({ time: '09:00', enabled: true })
}
function removeRow(idx) {
  schedule.value.splice(idx, 1)
}

async function saveSchedule() {
  for (const item of schedule.value) {
    if (!item.time) { ElMessage.warning('請填寫每個時間點'); return }
  }
  const payload = schedule.value.map(item => {
    const [h, m] = item.time.split(':')
    return { hour: Number(h), minute: Number(m), enabled: item.enabled }
  })
  saving.value = true
  try {
    const data = (await bffApi.crawlerData.saveSchedule(payload)) || []
    schedule.value = data.map(s => ({ time: `${pad(s.hour)}:${pad(s.minute)}`, enabled: s.enabled }))
    ElMessage.success('已儲存爬蟲執行時間，下一分鐘起生效')
  } catch (e) {
    ElMessage.error('儲存失敗：' + (e?.response?.data?.detail || e.message))
  } finally {
    saving.value = false
  }
}

// --- 輸出檔案路徑設定（Task 212；Drive 同步為 Task 241）---
const exportPath = reactive({
  outputSubpath: '', baseDir: '', absolutePath: '', updatedAt: null,
  // Google Drive 同步（Task 241）：本機一律照寫，這裡只控制「要不要多上傳一份副本」
  gdriveEnabled: false, gdriveSubpath: '', gdriveRemote: '',
  gdriveLastRunAt: null, gdriveLastStatus: ''
})
const exportPathLoading = ref(false)
const exportPathLoaded = ref(false)   // 未成功載入前停用儲存，避免以空值覆寫既有設定
const savingExportPath = ref(false)
const outputSettingDialog = reactive({ visible: false, draft: null })
const today = dayjs().format('YYYY-MM-DD')

// 已儲存的值；用來標示「改了但尚未儲存」。落點字串一律沿用後端回傳的 absolutePath，
// 不在前端重算正規化規則（空字串→預設子路徑等規則只存在後端，複製一份必然漂移）。
const savedSubpath = ref('')
const savedGdrive = reactive({ enabled: false, subpath: '' })
const exportPathDirty = computed(() => exportPathLoaded.value && exportPath.outputSubpath !== savedSubpath.value)
const gdriveDirty = computed(() => exportPathLoaded.value
  && (exportPath.gdriveEnabled !== savedGdrive.enabled || (exportPath.gdriveSubpath || '') !== savedGdrive.subpath))

// mode 決定這個 dialog 這次是在挑本機還是 Drive 資料夾（共用同一棵樹與同一組操作，只換資料來源）
const dirPicker = reactive({ visible: false, mode: 'local', baseDir: '', picked: '', newSub: '', treeKey: 0, error: '' })
const dirTreeProps = { label: 'name', isLeaf: 'leaf' }
const dirPickerTitle = computed(() => dirPicker.mode === 'gdrive' ? '選擇 Google Drive 資料夾' : '選擇輸出資料夾')

// dialog 內的「目前選擇」預覽。兩種 mode 的分隔符不同：Drive 的基底是 `remote:`（已含冒號，
// 後面直接接子路徑），本機的基底是 `/home/steven`（需要 `/` 分隔）。混用會顯示成
// `GDriveOutput:/投資理財` —— 多一個斜線，不是 rclone 的路徑格式，會誤導使用者。
const dirPickerPreview = computed(() => {
  const isGdrive = dirPicker.mode === 'gdrive'
  const base = dirPicker.baseDir
    || (isGdrive ? (exportPath.gdriveRemote || 'GDriveOutput') + ':' : (exportPath.baseDir || '/home/steven'))
  const parts = [dirPicker.picked, (dirPicker.newSub || '').trim()].filter(Boolean)
  if (!parts.length) return base
  const joined = parts.join('/')
  return isGdrive ? base + joined : base + '/' + joined
})

async function fetchExportPath() {
  exportPathLoading.value = true
  try {
    const s = (await bffApi.crawlerData.getExportPath()) || {}
    exportPath.outputSubpath = s.outputSubpath || ''
    exportPath.baseDir = s.baseDir || ''
    exportPath.absolutePath = s.absolutePath || ''
    exportPath.updatedAt = s.updatedAt || null
    exportPath.gdriveEnabled = !!s.gdriveEnabled
    exportPath.gdriveSubpath = s.gdriveSubpath || ''
    exportPath.gdriveRemote = s.gdriveRemote || ''
    exportPath.gdriveLastRunAt = s.gdriveLastRunAt || null
    exportPath.gdriveLastStatus = s.gdriveLastStatus || ''
    savedSubpath.value = exportPath.outputSubpath
    savedGdrive.enabled = exportPath.gdriveEnabled
    savedGdrive.subpath = exportPath.gdriveSubpath
    exportPathLoaded.value = true
  } catch (e) {
    exportPathLoaded.value = false
    ElMessage.error('讀取爬蟲輸出路徑失敗：' + apiErrorMessage(e))
  } finally {
    exportPathLoading.value = false
  }
}

function openExportDialog() {
  if (savingExportPath.value || !exportPathLoaded.value || !auth.isAdmin) return
  outputSettingDialog.draft = cloneExportSetting(exportPath)
  outputSettingDialog.visible = true
}
function closeExportDialog(done) { if (!savingExportPath.value) done() }

async function saveExportPath() {
  if (savingExportPath.value || !outputSettingDialog.draft) return
  const draft = outputSettingDialog.draft
  // 前端先擋一次（後端也會回 400）：開了同步卻沒指定資料夾，等於要把檔案倒在 Drive 根目錄
  if (draft.gdriveEnabled && !(draft.gdriveSubpath || '').trim()) {
    ElMessage.warning('已啟用 Google Drive 同步，請先選擇 Drive 目標資料夾')
    return
  }
  savingExportPath.value = true
  try {
    const s = (await bffApi.crawlerData.saveExportPath({
      outputSubpath: draft.outputSubpath || '',
      gdriveEnabled: draft.gdriveEnabled,
      gdriveSubpath: draft.gdriveSubpath || ''
    })) || {}
    const { gdriveSelfCheckWarning, ...saved } = s
    replaceExportSetting(exportPath, saved)
    savedSubpath.value = exportPath.outputSubpath
    savedGdrive.enabled = exportPath.gdriveEnabled
    savedGdrive.subpath = exportPath.gdriveSubpath
    outputSettingDialog.visible = false
    ElMessage.success('已儲存爬蟲輸出設定，下一輪抓取起生效')
    // 剛把 Drive 同步打開時後端會附一則自檢警告；正常時為 null，不顯示（Task 247.3.5）
    showGdriveSelfCheckWarning(s.gdriveSelfCheckWarning)
  } catch (e) {
    ElMessage.error('儲存失敗：' + apiErrorMessage(e))
  } finally {
    savingExportPath.value = false
  }
}

// --- 手動匯出兩顆（Requirement 63 / Task 280）---
// 'export'＝只重產檔案（不抓取）；'fetch'＝完整跑一輪。兩者共用同一支結果處理。
// 單一 running 字串同時當 loading 與互斥旗標：一顆在跑時另一顆停用，避免必然拿到 BUSY。
const running = ref('')

async function runManual(kind) {
  if (savingExportPath.value) return
  running.value = kind
  try {
    const r = (await (kind === 'fetch'
      ? bffApi.crawlerData.fetchAndRunExportNow()
      : bffApi.crawlerData.runExportNow())) || {}
    showManualResult(r)
  } catch (e) {
    ElMessage.error('手動匯出失敗：' + apiErrorMessage(e))
  } finally {
    running.value = ''
  }
  // 刷新頁面既有兩塊：當日爬回資料表格、輸出設定卡（含「上次上傳」狀態）。
  // 兩支的失敗都吞掉，不得覆蓋掉上面的操作結果訊息。
  fetchData().catch(() => {})
  fetchExportPath().catch(() => {})
}

// status 分三類呈現。總則：任何分支都不得把 null 印進訊息。
function showManualResult(r) {
  if (r.status !== 'OK') {
    // FAILED（跑了但檔案沒寫成）／ERROR（根本沒呼叫到爬蟲服務）才是真的失敗；
    // BUSY（沒啟動）／RUNNING（還在背景跑）／DISABLED（功能被關）用紅色會讓人以為要補救。
    const fn = (r.status === 'FAILED' || r.status === 'ERROR') ? ElMessage.error : ElMessage.warning
    fn(r.message || '爬蟲未執行')
    return
  }

  const counts = [
    r.exported == null ? null : `輸出 ${r.exported} 筆`,
    r.upserted == null ? null : `入庫 ${r.upserted} 筆`
  ].filter(Boolean).join('、')

  // OK 只代表「本機 JSON 那一份」寫成功，另有兩種部分成功必須看得出來：
  // (a) xlsx 沒產出（產檔失敗時 JSON 仍照寫）；(b) Drive 上傳失敗或跳過。
  if (!r.xlsxPath) {
    ElMessage.warning(`${counts}；已匯出 ${r.jsonPath}，但 Excel 這一份本輪未產出（不影響 SRPP 讀的 JSON）`)
    return
  }
  // 判準是「包含」不是「開頭」——狀態字串是 `xlsx …／json …` 的合併格式，
  // 「xlsx 上傳失敗、json 成功」時整串以 `xlsx ` 起頭而非「失敗」，用開頭判會顯示成完全成功。
  const gd = r.gdriveStatus || ''
  if (gd.includes('失敗') || gd.includes('跳過')) {
    ElMessage.warning(`${counts}；本機兩份已寫出，但 Google Drive 同步未全部成功：${gd}`)
    return
  }
  ElMessage.success(`${counts}；已匯出 ${r.jsonPath} 與 ${r.xlsxPath}`)
}

function openDirPicker(mode = 'local') {
  if (savingExportPath.value || !outputSettingDialog.draft) return
  dirPicker.mode = mode
  dirPicker.picked = (mode === 'gdrive' ? outputSettingDialog.draft.gdriveSubpath : outputSettingDialog.draft.outputSubpath) || ''
  dirPicker.newSub = ''
  dirPicker.baseDir = ''
  dirPicker.error = ''
  dirPicker.treeKey++            // 強制 el-tree 重新懶載入 root
  dirPicker.visible = true
}

// el-tree 懶載入：level 0 以基底（本機家目錄／Drive remote 根）為單一 root；其餘列該節點子目錄。
// 兩種 mode 共用同一棵樹，只換資料來源——Drive 端回傳形狀與本機完全相同，故不需第二套渲染邏輯。
async function loadDirNode(node, resolve) {
  const browse = dirPicker.mode === 'gdrive'
    ? bffApi.crawlerData.browseGdriveExportDir
    : bffApi.crawlerData.browseExportDir
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
    // Drive 端失敗（remote 未設定／授權失效）必須把原因顯示在 dialog 內，不能只是空樹——
    // 空樹會被誤讀為「Drive 裡沒有資料夾」而讓使用者以為是自己找錯位置
    if (dirPicker.mode === 'gdrive') dirPicker.error = apiErrorMessage(e)
    resolve([])
  }
}

const onDirNodeClick = (data) => { dirPicker.picked = data.path || '' }

function confirmDirPick() {
  if (savingExportPath.value || !outputSettingDialog.draft) return
  let p = dirPicker.picked || ''
  const sub = (dirPicker.newSub || '').trim().replace(/^\/+|\/+$/g, '')
  if (sub) p = p ? `${p}/${sub}` : sub
  if (dirPicker.mode === 'gdrive') outputSettingDialog.draft.gdriveSubpath = p
  else outputSettingDialog.draft.outputSubpath = p
  dirPicker.visible = false
}

onMounted(() => {
  fetchData()
  fetchSchedule()
  fetchExportPath()
})
</script>

<style scoped>
.section-title { font-size: 16px; font-weight: 600; color: #1e293b; }
.toolbar { display: flex; align-items: baseline; gap: 12px; flex-wrap: wrap; }
.hint { color: #94a3b8; font-size: 12px; }

.query-bar { display: flex; align-items: center; gap: 18px; flex-wrap: wrap; }
.field { display: flex; align-items: center; gap: 8px; }
.field-label { color: #475569; font-size: 13px; }
.count { color: #64748b; font-size: 13px; }

.title-link { color: #2563eb; text-decoration: none; }
.title-link:hover { text-decoration: underline; }

.time-row { display: flex; align-items: center; margin-bottom: 10px; }
.empty-schedule { color: #94a3b8; font-size: 13px; padding: 8px 0; }
.schedule-actions { margin-top: 12px; display: flex; gap: 12px; }

.path-row { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
/* Drive 同步列與本機輸出列拉開一點，讓「本機／Drive 是兩件事」在視覺上分得開 */
.gdrive-row { margin-top: 14px; }
/* 手動匯出是「動作」不是「設定」，以分隔線與上方的設定區隔開 */
.run-row { margin-top: 16px; padding-top: 16px; border-top: 1px solid #e2e8f0; }
.path-hint { margin-top: 10px; font-size: 12px; color: #94a3b8; line-height: 1.8; }
.path-hint code { background: #f1f5f9; color: #475569; padding: 1px 5px; border-radius: 4px; font-size: 11px; word-break: break-all; }
.path-dirty { color: #d97706; }
.path-dirty code { background: #fef3c7; color: #92400e; }

.dir-picker-path { font-size: 13px; color: #475569; margin-bottom: 10px; }
.dir-picker-path code { background: #f1f5f9; color: #0f172a; padding: 2px 6px; border-radius: 4px; word-break: break-all; }
.dir-tree { max-height: 340px; overflow: auto; border: 1px solid #e2e8f0; border-radius: 6px; padding: 6px; }
.dir-new-sub { display: flex; align-items: center; gap: 10px; margin-top: 12px; }
.dir-new-sub .dns-label { font-size: 13px; color: #475569; white-space: nowrap; }
</style>
