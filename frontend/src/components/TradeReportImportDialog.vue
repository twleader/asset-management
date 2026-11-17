<template>
  <el-dialog
    :model-value="modelValue"
    title="匯入富邦成交回報"
    width="min(960px, 94vw)"
    :close-on-click-modal="!draft.confirming && !draft.applying"
    :close-on-press-escape="!draft.confirming && !draft.applying"
    :show-close="!draft.confirming && !draft.applying"
    :before-close="beforeClose"
    @update:model-value="onVisibleChange"
  >
    <div class="trade-import-dialog">
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="這是登入使用者的來源與本人帳戶聲明，不是系統驗證。系統不驗證 DKIM／S/MIME，也不驗證券商帳戶持有人。"
      />

      <el-form label-position="top" class="trade-import-form" :disabled="draft.confirming || draft.applying">
        <el-form-item label="成交回報 .eml 檔案">
          <input
            type="file"
            accept=".eml"
            :disabled="draft.confirming || draft.applying"
            @change="onFileChange"
          />
          <div class="trade-import-help">
            僅在本機讀取原始郵件 bytes（上限 512 KiB）後送至本頁 BFF。郵件 HTML 不會執行或顯示，也不會下載圖片、連結或其他外部內容。
          </div>
          <div v-if="draft.fileName" class="trade-import-filename">已選擇：{{ draft.fileName }}</div>
          <div v-if="draft.reading" class="trade-import-help">正在本機讀取檔案…</div>
          <div v-if="draft.fileError" class="trade-import-error">{{ draft.fileError }}</div>
        </el-form-item>

        <el-form-item label="來源郵件 Message-ID">
          <el-input
            v-model="draft.sourceMessageId"
            maxlength="200"
            show-word-limit
            autocomplete="off"
            placeholder="可由 .eml 的 Message-ID 預填，請自行核對"
            @input="onInputChanged"
          />
          <div class="trade-import-help">Message-ID 只是來源識別值，不是寄件者、簽章或帳戶身分證明。</div>
        </el-form-item>

        <el-checkbox v-model="draft.attestedAuthenticReport" @change="onInputChanged">
          我聲明這是富邦正式成交回報資料（系統不驗證 DKIM／S/MIME）
        </el-checkbox>
        <el-checkbox v-model="draft.attestedOwnerAccount" @change="onInputChanged">
          我聲明回報中的成交屬於本人帳戶（系統不驗證券商帳戶持有人）
        </el-checkbox>
      </el-form>

      <el-alert
        v-if="draft.previewError"
        class="trade-import-alert"
        type="error"
        :closable="false"
        show-icon
        :title="draft.previewError"
      />
      <el-alert
        v-if="draft.applyError"
        class="trade-import-alert"
        type="error"
        :closable="false"
        show-icon
        :title="draft.applyError"
      />

      <section v-if="draft.preview" class="trade-import-preview" aria-label="成交回報預覽">
        <div class="trade-import-preview-head">
          <strong>預覽結果：{{ importStatusLabel(draft.preview.status) }}</strong>
          <span v-if="draft.preview.rows?.length">{{ draft.preview.rows.length }} 筆</span>
        </div>
        <div class="trade-import-trust">
          來源信任：<code>{{ draft.preview.sourceTrust || 'ASSERTED_BY_AUTHENTICATED_IMPORTER' }}</code>
          <span>（由登入使用者聲明，非系統驗證）</span>
        </div>
        <div v-if="draft.preview.rawSha256" class="trade-import-hash">
          原始郵件 SHA-256：<code>{{ draft.preview.rawSha256 }}</code>
        </div>

        <el-alert
          v-if="draft.preview.status === 'CONFLICT' || conflictRows.length"
          class="trade-import-alert"
          type="error"
          :closable="false"
          show-icon
          title="偵測到衝突；不會自動新增、覆寫或合併交易。請先核對既有交易紀錄。"
        />
        <el-alert
          v-else-if="draft.preview.status === 'ALREADY_IMPORTED'"
          class="trade-import-alert"
          type="info"
          :closable="false"
          title="成交事實已存在。仍須明確確認，才能為這封來源郵件保存收件紀錄。"
        />

        <el-table :data="draft.preview.rows || []" size="small" stripe max-height="360">
          <el-table-column label="股票" min-width="130">
            <template #default="{ row }">
              <div>{{ row.stockName || '—' }}</div>
              <small>{{ row.stockCode || '—' }}</small>
            </template>
          </el-table-column>
          <el-table-column prop="side" label="買／賣" width="72" align="center" />
          <el-table-column prop="tradeDate" label="成交日期" width="105" />
          <el-table-column prop="filledTime" label="成交時間" width="92" />
          <el-table-column prop="orderNo" label="委託書編號" min-width="110" show-overflow-tooltip />
          <el-table-column label="數量" width="92" align="right">
            <template #default="{ row }">{{ displayValue(row.shares) }}</template>
          </el-table-column>
          <el-table-column label="單價" width="100" align="right">
            <template #default="{ row }">{{ displayValue(row.price) }}</template>
          </el-table-column>
          <el-table-column label="成交金額" width="110" align="right">
            <template #default="{ row }">{{ displayValue(row.amount) }}</template>
          </el-table-column>
          <el-table-column label="手續費" width="115" align="right">
            <template #default="{ row }">
              <div>{{ displayFeeOrTax(row.fee) }}</div>
              <small
                v-if="row.feeProvenance"
                class="trade-import-provenance"
                :class="{ 'is-unavailable': row.feeProvenance.status === 'UNAVAILABLE' }"
              >{{ feeProvenanceLabel(row.feeProvenance) }}</small>
            </template>
          </el-table-column>
          <el-table-column label="證交稅" width="115" align="right">
            <template #default="{ row }">{{ displayFeeOrTax(row.transactionTax) }}</template>
          </el-table-column>
          <el-table-column label="匯入狀態" min-width="155">
            <template #default="{ row }">
              <div>{{ importStatusLabel(row.status) }}</div>
              <small v-if="row.reason" class="trade-import-error">{{ conflictReasonLabel(row.reason) }}</small>
              <small v-if="row.transactionId != null">交易紀錄 ID：{{ row.transactionId }}</small>
            </template>
          </el-table-column>
        </el-table>

        <div class="trade-import-unknown-hint">
          費用欄位若顯示「未知（原始回報未提供）」代表 fee/tax 為 null，不會當成 0，也不會推估。
        </div>
        <div v-if="conflictRows.length" class="trade-import-conflicts">
          <strong>需人工核對的列</strong>
          <ul>
            <li v-for="(row, index) in conflictRows" :key="`${row.stockCode || 'row'}-${row.orderNo || index}-${index}`">
              {{ row.stockCode || '—' }} {{ row.stockName || '' }}：{{ conflictReasonLabel(row.reason) }}
              <span v-if="row.transactionId != null">（交易紀錄 ID：{{ row.transactionId }}）</span>
            </li>
          </ul>
        </div>
      </section>
    </div>

    <template #footer>
      <el-button :disabled="draft.confirming || draft.applying" @click="closeDialog">取消</el-button>
      <el-button
        type="primary"
        :loading="draft.previewing"
        :disabled="!canPreview || draft.confirming || draft.applying"
        @click="previewImport"
      >
        {{ draft.preview ? '重新預覽' : '預覽成交資料' }}
      </el-button>
      <el-button
        v-if="draft.preview"
        type="danger"
        :loading="draft.applying"
        :disabled="!canApply || draft.confirming || draft.applying"
        @click="confirmApply"
      >
        確認匯入 {{ draft.preview.rows?.length || 0 }} 筆
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ElMessage, ElMessageBox } from 'element-plus'
import { apiErrorMessage, bffApi } from '@/api'
import {
  canApplyTradeReportPreview,
  canPreviewTradeReport,
  conflictReasonLabel,
  createTradeReportImportDraft,
  feeProvenanceLabel,
  getTradeReportConflictRows,
  importStatusLabel,
  invalidateTradeReportPreview,
  readEmlFile
} from '@/utils/tradeReportImport'

const props = defineProps({ modelValue: { type: Boolean, default: false } })
const emit = defineEmits(['update:modelValue', 'applied'])
const draft = reactive(createTradeReportImportDraft())

let fileReadSequence = 0
let previewRequestSequence = 0
let previewController = null
let lastSuggestedMessageId = ''
let appliedForCurrentDraft = false

const canPreview = computed(() => canPreviewTradeReport(draft))
const canApply = computed(() => canApplyTradeReportPreview(draft.preview) && !draft.applying)
const conflictRows = computed(() => getTradeReportConflictRows(draft.preview))

function displayValue(value) {
  return value == null || value === '' ? '—' : String(value)
}

function displayFeeOrTax(value) {
  return value == null ? '未知（原始回報未提供）' : String(value)
}

function invalidatePreview() {
  invalidateTradeReportPreview(draft)
  previewRequestSequence++
  previewController?.abort()
  previewController = null
}

function onInputChanged() {
  if (draft.confirming || draft.applying) return
  invalidatePreview()
}

async function onFileChange(event) {
  const file = event.target.files?.[0] || null
  // Clear the native input so choosing the same file again still emits a change event.
  event.target.value = ''
  if (draft.confirming || draft.applying) return

  invalidatePreview()
  const sequence = ++fileReadSequence
  draft.rawMimeBase64 = ''
  draft.fileName = file?.name || ''
  draft.fileError = ''
  draft.reading = false

  if (!file) {
    lastSuggestedMessageId = ''
    return
  }

  draft.reading = true
  try {
    const loaded = await readEmlFile(file)
    if (sequence !== fileReadSequence || !props.modelValue) return

    draft.rawMimeBase64 = loaded.rawMimeBase64
    if (!draft.sourceMessageId.trim() || draft.sourceMessageId === lastSuggestedMessageId) {
      draft.sourceMessageId = loaded.suggestedMessageId
      lastSuggestedMessageId = loaded.suggestedMessageId
    } else {
      // A manually entered ID is never replaced by the next file's header.
      lastSuggestedMessageId = ''
    }
  } catch (error) {
    if (sequence === fileReadSequence) {
      draft.fileError = error?.message || '讀取 .eml 檔案失敗'
    }
  } finally {
    if (sequence === fileReadSequence) draft.reading = false
  }
}

async function previewImport() {
  if (!canPreview.value || !props.modelValue) return
  invalidatePreview()
  const sequence = ++previewRequestSequence
  const controller = new AbortController()
  previewController = controller
  draft.previewing = true
  draft.previewError = ''

  const payload = {
    sourceMessageId: draft.sourceMessageId.trim(),
    rawMimeBase64: draft.rawMimeBase64,
    attestedOwnerAccount: true,
    attestedAuthenticReport: true
  }

  try {
    const result = await bffApi.transaction.previewTradeReportImport(payload, { signal: controller.signal })
    if (sequence !== previewRequestSequence || !props.modelValue) return
    draft.preview = result
    draft.applyError = ''
  } catch (error) {
    if (sequence !== previewRequestSequence || !props.modelValue) return
    draft.previewError = apiErrorMessage(error, '預覽失敗，請檢查檔案與來源郵件識別碼')
  } finally {
    if (sequence === previewRequestSequence) {
      draft.previewing = false
      if (previewController === controller) previewController = null
    }
  }
}

function applyConflictResponse(error) {
  const data = error?.response?.data
  if (data?.status === 'CONFLICT' && Array.isArray(data.rows)) draft.preview = data
  draft.applyError = apiErrorMessage(error, '匯入遭遇衝突，未覆寫既有交易')
}

async function confirmApply() {
  if (!canApply.value || !props.modelValue || draft.confirming || draft.applying || appliedForCurrentDraft) return
  const preview = draft.preview
  const revision = draft.revision
  draft.confirming = true
  let confirmed = false
  try {
    await ElMessageBox.confirm(
      `將把預覽中的 ${preview.rows.length} 筆成交事實寫入目前登入帳戶的交易帳本。費用與稅額若未知會保持未知。兩項來源聲明由你提供，系統不驗證郵件簽章或券商帳戶持有人。要繼續嗎？`,
      '確認匯入成交回報',
      {
        type: 'warning',
        confirmButtonText: '確認匯入',
        cancelButtonText: '返回預覽',
        closeOnClickModal: false,
        distinguishCancelAndClose: true
      }
    )
    confirmed = true
  } catch {
    confirmed = false
  }
  draft.confirming = false
  if (!confirmed || revision !== draft.revision || draft.preview !== preview || !props.modelValue) return

  draft.applying = true
  draft.applyError = ''
  const payload = {
    sourceMessageId: draft.sourceMessageId.trim(),
    rawMimeBase64: draft.rawMimeBase64,
    attestedOwnerAccount: true,
    attestedAuthenticReport: true,
    previewHash: preview.previewHash
  }
  try {
    const result = await bffApi.transaction.applyTradeReportImport(payload)
    if (!props.modelValue || appliedForCurrentDraft) return
    appliedForCurrentDraft = true
    ElMessage.success(result?.insertedCount === 0
      ? '來源郵件已記錄；帳本已有相同成交，未新增交易'
      : `已匯入成交回報${result?.insertedCount != null ? `，新增 ${result.insertedCount} 筆` : ''}`)
    draft.applying = false
    emit('applied', result)
    emit('update:modelValue', false)
  } catch (error) {
    if (error?.response?.status === 409 || error?.response?.data?.status === 'CONFLICT') {
      applyConflictResponse(error)
    } else {
      draft.applyError = apiErrorMessage(error, '匯入失敗，請查看錯誤訊息後重新預覽')
    }
  } finally {
    draft.applying = false
  }
}

function resetDraft() {
  invalidatePreview()
  fileReadSequence++
  lastSuggestedMessageId = ''
  appliedForCurrentDraft = false
  Object.assign(draft, createTradeReportImportDraft())
}

function closeDialog() {
  if (draft.confirming || draft.applying) return
  resetDraft()
  emit('update:modelValue', false)
}

function beforeClose(done) {
  if (draft.confirming || draft.applying) return
  resetDraft()
  emit('update:modelValue', false)
  done()
}

function onVisibleChange(visible) {
  if (visible) {
    emit('update:modelValue', true)
    return
  }
  closeDialog()
}

watch(() => props.modelValue, (visible, wasVisible) => {
  if (!visible && wasVisible) resetDraft()
})
</script>

<style scoped>
.trade-import-dialog { display: flex; flex-direction: column; gap: 12px; }
.trade-import-form { display: flex; flex-direction: column; gap: 2px; }
.trade-import-help, .trade-import-hash, .trade-import-unknown-hint { color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.6; }
.trade-import-filename { margin-top: 4px; font-size: 12px; color: var(--el-text-color-regular); overflow-wrap: anywhere; }
.trade-import-preview { display: flex; flex-direction: column; gap: 8px; }
.trade-import-preview-head { display: flex; justify-content: space-between; align-items: center; }
.trade-import-trust { color: var(--el-text-color-regular); font-size: 13px; }
.trade-import-trust span { color: var(--el-text-color-secondary); font-size: 12px; }
.trade-import-hash code { overflow-wrap: anywhere; }
.trade-import-alert { margin-top: 4px; }
.trade-import-error { color: var(--el-color-danger); font-size: 12px; }
.trade-import-provenance { display: block; color: var(--el-text-color-secondary); font-size: 11px; line-height: 1.4; white-space: normal; }
.trade-import-provenance.is-unavailable { color: var(--el-color-warning); }
.trade-import-conflicts { color: var(--el-color-danger); font-size: 13px; }
.trade-import-conflicts ul { margin: 6px 0 0; padding-left: 22px; }
.trade-import-conflicts li { margin: 4px 0; }
</style>
