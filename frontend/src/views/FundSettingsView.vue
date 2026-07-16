<template>
  <div v-loading="loading">
    <div class="page-header">
      <h2>💰 信託基金設定</h2>
      <el-button type="primary" :icon="Plus" @click="openDialog()">新增基金</el-button>
    </div>

    <el-card>
      <el-table :data="funds" size="small" stripe>
        <el-table-column prop="fundCode" label="基金代號" width="120" />
        <el-table-column prop="fundName" label="基金名稱" min-width="240" />
        <el-table-column prop="bankDisplayName" label="銀行" width="120" />
        <el-table-column prop="currency" label="計價" width="80" align="center" />
        <el-table-column prop="site" label="境內外" width="100" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.site === 'offshore' ? 'warning' : 'success'">
              {{ row.site === 'offshore' ? '境外' : '境內' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="FundClear 三段代碼" min-width="240">
          <template #default="{ row }">
            <span class="mono">{{ row.fundclearOrgCode }} / {{ row.fundclearFundCode }} / {{ row.fundclearClassCode }}</span>
          </template>
        </el-table-column>
        <el-table-column label="最新 NAV" width="130" align="right">
          <template #default="{ row }">
            <span v-if="row.latestNav != null">
              {{ row.latestNav }}
              <small style="color:#94a3b8">{{ row.latestNavDate }}</small>
            </span>
            <span v-else style="color:#94a3b8">-</span>
          </template>
        </el-table-column>
        <el-table-column label="狀態" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.active ? 'success' : 'danger'" size="small">
              {{ row.active ? '啟用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" align="center">
          <template #default="{ row }">
            <el-button size="small" :icon="Edit" @click="openDialog(row)">編輯</el-button>
            <el-button size="small" :type="row.active ? 'danger' : 'success'" @click="toggleActive(row)">
              {{ row.active ? '停用' : '啟用' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="editing ? '編輯基金' : '新增基金'" width="640px">
      <el-form :model="form" :rules="rules" ref="formRef" label-width="140px">
        <el-form-item label="基金代號" prop="fundCode">
          <el-input v-model="form.fundCode" :disabled="!!editing"
            placeholder="銀行內代號或 FundClear class code，如 02A8、93100953A" />
          <div class="hint">PK，編輯時無法修改</div>
        </el-form-item>
        <el-form-item label="基金名稱" prop="fundName">
          <el-input v-model="form.fundName" placeholder="如 富達亞洲非投資等級債券基金 A股F1穩定月配息美元" />
        </el-form-item>
        <el-form-item label="銷售銀行">
          <el-select v-model="form.bankId" clearable style="width:100%" placeholder="（可選）">
            <el-option v-for="b in bankOptions" :key="b.value" :label="b.label" :value="b.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="計價幣別" prop="currency">
          <el-select v-model="form.currency" style="width:100%">
            <el-option label="USD 美元" value="USD" />
            <el-option label="ZAR 南非幣" value="ZAR" />
            <el-option label="EUR 歐元" value="EUR" />
            <el-option label="JPY 日圓" value="JPY" />
            <el-option label="TWD 新台幣（無需匯率換算）" value="TWD" />
          </el-select>
        </el-form-item>
        <el-form-item label="境內外" prop="site">
          <el-radio-group v-model="form.site">
            <el-radio value="offshore">境外（offshore）</el-radio>
            <el-radio value="onshore">境內（onshore）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-divider>FundClear 三段代碼（抓 NAV 用）</el-divider>
        <el-form-item label="機構代碼" prop="fundclearOrgCode">
          <el-input v-model="form.fundclearOrgCode" placeholder="offshore 為 3 碼如 043；onshore 為 5 碼如 A0005" />
        </el-form-item>
        <el-form-item label="基金代碼" prop="fundclearFundCode">
          <el-input v-model="form.fundclearFundCode" placeholder="offshore 10 碼如 A003800030；onshore 8 碼如 93100953" />
        </el-form-item>
        <el-form-item label="級別代碼" prop="fundclearClassCode">
          <el-input v-model="form.fundclearClassCode" placeholder="多為 ISIN 如 LU0937949237；少數投信內部 code 如 GSBAMU、ABGHYATUSD" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">儲存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { Plus, Edit } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { bffApi } from '@/api'

const loading = ref(false)
const saving  = ref(false)
const funds   = ref([])
const bankOptions = ref([])
const dialogVisible = ref(false)
const editing = ref(null)
const formRef = ref()

const form = reactive({
  fundCode: '', fundName: '', bankId: null, currency: 'USD',
  site: 'offshore', fundclearOrgCode: '', fundclearFundCode: '', fundclearClassCode: ''
})

const rules = {
  fundCode:           [{ required: true, message: '請輸入基金代號' }],
  fundName:           [{ required: true, message: '請輸入基金名稱' }],
  currency:           [{ required: true, message: '請選擇計價幣別' }],
  site:               [{ required: true, message: '請選擇境內外' }],
  fundclearOrgCode:   [{ required: true, message: '請輸入 FundClear 機構代碼' }],
  fundclearFundCode:  [{ required: true, message: '請輸入 FundClear 基金代碼' }],
  fundclearClassCode: [{ required: true, message: '請輸入 FundClear 級別代碼' }]
}

async function load() {
  loading.value = true
  try {
    const [list, banks] = await Promise.allSettled([
      bffApi.fundSettings.getAll(),
      bffApi.fundSettings.getBankOptions()
    ])
    if (list.status === 'fulfilled') funds.value = list.value
    if (banks.status === 'fulfilled') {
      bankOptions.value = banks.value.map(b => ({ value: b.id, label: b.displayName }))
    }
  } finally { loading.value = false }
}

function openDialog(fund = null) {
  editing.value = fund
  if (fund) {
    Object.assign(form, {
      fundCode: fund.fundCode, fundName: fund.fundName, bankId: fund.bankId,
      currency: fund.currency, site: fund.site,
      fundclearOrgCode: fund.fundclearOrgCode,
      fundclearFundCode: fund.fundclearFundCode,
      fundclearClassCode: fund.fundclearClassCode
    })
  } else {
    Object.assign(form, {
      fundCode: '', fundName: '', bankId: null, currency: 'USD',
      site: 'offshore', fundclearOrgCode: '', fundclearFundCode: '', fundclearClassCode: ''
    })
  }
  dialogVisible.value = true
}

async function save() {
  await formRef.value.validate()
  saving.value = true
  try {
    const payload = {
      fundName: form.fundName,
      bankId: form.bankId,
      currency: form.currency,
      site: form.site,
      fundclearOrgCode: form.fundclearOrgCode.trim(),
      fundclearFundCode: form.fundclearFundCode.trim(),
      fundclearClassCode: form.fundclearClassCode.trim()
    }
    if (editing.value) {
      await bffApi.fundSettings.update(editing.value.fundCode, payload)
      ElMessage.success('已更新')
    } else {
      await bffApi.fundSettings.create({ fundCode: form.fundCode.trim(), ...payload, active: true })
      ElMessage.success('已新增')
    }
    dialogVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function toggleActive(fund) {
  const action = fund.active ? '停用' : '啟用'
  await ElMessageBox.confirm(`確定要${action}「${fund.fundName}」嗎？`, '確認', { type: 'warning' })
  await bffApi.fundSettings.setActive(fund.fundCode, !fund.active)
  ElMessage.success(`已${action}`)
  await load()
}

onMounted(load)
</script>

<style scoped>
.page-header { display: flex; align-items: center; gap: 12px; margin-bottom: 20px; }
.page-header h2 { margin: 0; flex: 1; }
.hint { font-size: 12px; color: #94a3b8; margin-top: 4px; }
.mono { font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 12px; }
</style>
