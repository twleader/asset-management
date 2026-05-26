<template>
  <div v-loading.fullscreen.lock="restoring" :element-loading-text="loadingText">
    <div class="page-header">
      <h2>💾 備份/還原 資料</h2>
    </div>

    <!-- 立即備份 -->
    <el-card class="section">
      <template #header>
        <div class="card-title">立即備份</div>
      </template>
      <p class="hint">
        手動觸發一次 PostgreSQL 備份，加密上傳到 Google Drive 的
        <code>backups/manual/</code>。手動備份僅保留最近 {{ settings.manualRetention }} 份（自救點不計入）。
      </p>
      <el-button type="primary" :icon="Upload"
                 :loading="backing"
                 :disabled="!settings.backupEnabled"
                 @click="doBackup">
        立即備份
      </el-button>
      <span v-if="!settings.backupEnabled" style="margin-left:12px;color:#94a3b8;font-size:13px">
        備份開關已關閉
      </span>
      <div v-if="lastBackup" class="last-backup">
        最近一次手動備份：<b>{{ lastBackup.filename }}</b>
        （{{ formatBytes(lastBackup.sizeBytes) }}，{{ formatTime(lastBackup.uploadedAt) }}）
      </div>
    </el-card>

    <!-- 保留設定 -->
    <el-card class="section" v-loading="loadingSettings">
      <template #header>
        <div class="card-title">保留設定</div>
      </template>
      <p class="hint">
        各類備份保留的代數（份數）。超過設定上限時，每次備份完成後會自動刪除最舊的檔案。範圍 1～999。
      </p>
      <el-form :model="settings" inline label-width="120px" class="settings-form">
        <el-form-item label="啟用備份">
          <el-switch v-model="settings.backupEnabled"
                     active-text="開啟" inactive-text="關閉" inline-prompt />
        </el-form-item>
        <el-form-item label="人工備份">
          <el-input-number v-model="settings.manualRetention" :min="1" :max="999" controls-position="right" />
        </el-form-item>
        <el-form-item label="交易日備份">
          <el-input-number v-model="settings.dailyRetention" :min="1" :max="999" controls-position="right" />
        </el-form-item>
        <el-form-item label="周備份">
          <el-input-number v-model="settings.weeklyRetention" :min="1" :max="999" controls-position="right" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="savingSettings" @click="saveSettings">儲存設定</el-button>
        </el-form-item>
      </el-form>
      <p class="hint" style="margin-top:8px;margin-bottom:0;color:#94a3b8;font-size:12px">
        關閉「啟用備份」後，所有自動排程與手動備份都會 skip；還原前的「自救點」備份不受影響。
      </p>
    </el-card>

    <!-- 還原資料 -->
    <el-card class="section" v-loading="loadingList">
      <template #header>
        <div class="card-title">
          還原資料
          <el-button size="small" :icon="Refresh" @click="loadList" style="margin-left: 12px">
            重新整理
          </el-button>
          <el-button size="small" :icon="Connection"
                     :loading="syncing" @click="doSync" style="margin-left: 8px">
            從 Google Drive 同步
          </el-button>
        </div>
      </template>
      <p class="hint">
        備份紀錄存於本地資料庫，列表開啟即時顯示，<b>不會每次都連 Google Drive</b>。
        若 Google Drive 上的檔案有外部變動（手動刪檔、其他設備同步），按
        <b>「從 Google Drive 同步」</b>對齊。執行還原會
        <b style="color: #ef4444">覆蓋目前資料庫</b>，
        系統會在還原前自動建立一份「自救點」備份。
      </p>

      <el-table :data="backups" size="small" stripe>
        <el-table-column label="來源" width="100">
          <template #default="{ row }">
            <el-tag :type="folderTagType(row.folder)" size="small">{{ row.folder }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="filename" label="檔名" min-width="320">
          <template #default="{ row }">
            <span>{{ row.filename }}</span>
            <el-tag v-if="row.autoPreRestore" size="small" type="warning" style="margin-left: 8px">
              自救點
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="備份時間" width="180">
          <template #default="{ row }">{{ formatTime(row.modifiedAt) }}</template>
        </el-table-column>
        <el-table-column label="大小" width="110" align="right">
          <template #default="{ row }">{{ formatBytes(row.sizeBytes) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="120" align="center">
          <template #default="{ row }">
            <el-button size="small" type="danger" :icon="Refresh" @click="openRestoreDialog(row)">
              還原
            </el-button>
          </template>
        </el-table-column>
        <template #empty>尚無任何備份</template>
      </el-table>
    </el-card>

    <!-- 還原確認對話框 -->
    <el-dialog v-model="dialogVisible" title="確認還原" width="520px" :close-on-click-modal="false">
      <el-alert type="error" show-icon :closable="false" style="margin-bottom: 16px">
        此操作將以選定的備份<b>覆蓋目前資料庫</b>，無法直接撤銷。
        系統會先自動建立一份自救點備份。
      </el-alert>

      <div v-if="selected" class="restore-info">
        <div><b>來源：</b>{{ selected.folder }}</div>
        <div><b>檔名：</b>{{ selected.filename }}</div>
        <div><b>備份時間：</b>{{ formatTime(selected.modifiedAt) }}</div>
        <div><b>大小：</b>{{ formatBytes(selected.sizeBytes) }}</div>
      </div>

      <el-form-item label="確認文字" style="margin-top: 16px">
        <el-input
          v-model="confirmText"
          placeholder="請輸入「確認還原」以啟用按鈕"
          clearable />
      </el-form-item>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button
          type="danger"
          :disabled="confirmText !== '確認還原'"
          @click="doRestore">
          執行還原
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { Upload, Refresh, Connection } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'
import { bffApi } from '@/api'

const loadingList = ref(false)
const backing = ref(false)
const restoring = ref(false)
const loadingText = ref('還原中…請勿關閉視窗')

const backups = ref([])
const lastBackup = ref(null)

const dialogVisible = ref(false)
const selected = ref(null)
const confirmText = ref('')

const loadingSettings = ref(false)
const savingSettings = ref(false)
const syncing = ref(false)
const settings = reactive({
  manualRetention: 5, dailyRetention: 50, weeklyRetention: 5, backupEnabled: true
})

async function loadSettings() {
  loadingSettings.value = true
  try {
    const s = await bffApi.backupRestore.getSettings()
    settings.manualRetention = s.manualRetention
    settings.dailyRetention  = s.dailyRetention
    settings.weeklyRetention = s.weeklyRetention
    settings.backupEnabled   = s.backupEnabled !== false
  } finally {
    loadingSettings.value = false
  }
}

async function saveSettings() {
  savingSettings.value = true
  try {
    const s = await bffApi.backupRestore.updateSettings({
      manualRetention: settings.manualRetention,
      dailyRetention:  settings.dailyRetention,
      weeklyRetention: settings.weeklyRetention,
      backupEnabled:   settings.backupEnabled
    })
    settings.manualRetention = s.manualRetention
    settings.dailyRetention  = s.dailyRetention
    settings.weeklyRetention = s.weeklyRetention
    settings.backupEnabled   = s.backupEnabled !== false
    ElMessage.success('保留設定已更新')
    // 後端在 updateSetting 時會立即套用新 retention（rotate 三個資料夾），需 reload 列表反映被刪掉的舊備份
    await loadList()
  } finally {
    savingSettings.value = false
  }
}

async function doSync() {
  syncing.value = true
  try {
    const res = await bffApi.backupRestore.sync()
    ElMessage.success(`同步完成：新增 ${res.inserted}、刪除孤兒 ${res.deleted}、Google Drive 共 ${res.total} 份`)
    await loadList()
  } finally {
    syncing.value = false
  }
}

async function loadList() {
  loadingList.value = true
  try {
    backups.value = await bffApi.backupRestore.list()
  } finally {
    loadingList.value = false
  }
}

async function doBackup() {
  backing.value = true
  try {
    lastBackup.value = await bffApi.backupRestore.create()
    ElMessage.success('備份完成')
    await loadList()
  } finally {
    backing.value = false
  }
}

function openRestoreDialog(row) {
  selected.value = row
  confirmText.value = ''
  dialogVisible.value = true
}

async function doRestore() {
  if (!selected.value) return
  loadingText.value = `還原中：${selected.value.folder}/${selected.value.filename}…請勿關閉視窗`
  restoring.value = true
  dialogVisible.value = false
  try {
    const res = await bffApi.backupRestore.restore({
      folder: selected.value.folder,
      filename: selected.value.filename,
      confirmation: confirmText.value
    })
    ElMessage.success(`還原成功，自救點：${res.preRestoreBackup}。1.5 秒後重新載入頁面…`)
    setTimeout(() => location.reload(), 1500)
  } catch (e) {
    restoring.value = false
  }
}

function folderTagType(folder) {
  return {
    manual:  'primary',
    daily:   'success',
    weekly:  'warning',
    monthly: 'info'
  }[folder] || ''
}

function formatBytes(n) {
  if (n == null) return '-'
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  if (n < 1024 * 1024 * 1024) return `${(n / 1024 / 1024).toFixed(1)} MB`
  return `${(n / 1024 / 1024 / 1024).toFixed(2)} GB`
}

function formatTime(t) {
  if (!t) return '-'
  return dayjs(t).format('YYYY-MM-DD HH:mm:ss')
}

onMounted(() => {
  loadList()
  loadSettings()
})
</script>

<style scoped>
.page-header { display: flex; align-items: center; gap: 12px; margin-bottom: 20px; }
.page-header h2 { margin: 0; flex: 1; }
.section { margin-bottom: 20px; }
.card-title { font-weight: 600; font-size: 16px; }
.hint { color: #64748b; font-size: 13px; margin-bottom: 12px; line-height: 1.6; }
.last-backup { margin-top: 12px; color: #475569; font-size: 13px; }
.restore-info { background: #f8fafc; padding: 12px 16px; border-radius: 8px; line-height: 1.8; font-size: 13px; }
.restore-info b { color: #1e293b; margin-right: 4px; }
code { background: #f1f5f9; padding: 1px 6px; border-radius: 4px; font-size: 12px; }
</style>
