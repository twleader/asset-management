<template>
  <div v-loading="loading">
    <div class="page-header">
      <h2>🏢 券商設定</h2>
      <el-button type="primary" :icon="Plus" @click="openDialog()">新增券商</el-button>
    </div>

    <el-card>
      <el-table :data="brokers" size="small" stripe>
        <el-table-column prop="code" label="識別代碼" width="120" />
        <el-table-column prop="displayName" label="顯示名稱" width="160" />
        <el-table-column prop="keywords" label="Excel 關鍵字" min-width="200">
          <template #default="{ row }">
            <el-space wrap>
              <el-tag v-for="kw in splitKeywords(row.keywords)" :key="kw" size="small" type="info">
                {{ kw }}
              </el-tag>
            </el-space>
          </template>
        </el-table-column>
        <el-table-column label="狀態" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.active ? 'success' : 'danger'" size="small">
              {{ row.active ? '啟用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" align="center">
          <template #default="{ row }">
            <el-button size="small" :icon="Edit" @click="openDialog(row)">編輯</el-button>
            <el-button
              size="small"
              :type="row.active ? 'danger' : 'success'"
              @click="toggleActive(row)">
              {{ row.active ? '停用' : '啟用' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- Dialog -->
    <el-dialog v-model="dialogVisible" :title="editing ? '編輯券商' : '新增券商'" width="480px">
      <el-form :model="form" :rules="rules" ref="formRef" label-width="110px">
        <el-form-item label="識別代碼" prop="code">
          <el-input v-model="form.code" :disabled="!!editing" placeholder="英文小寫，如 fubon" />
        </el-form-item>
        <el-form-item label="顯示名稱" prop="displayName">
          <el-input v-model="form.displayName" placeholder="如 富邦證券" />
        </el-form-item>
        <el-form-item label="Excel 關鍵字">
          <el-input v-model="form.keywords" placeholder="逗號分隔，如 富邦,Fubon" />
          <div class="form-hint">匯入 Excel 時，欄位內容含有任一關鍵字即視為此券商</div>
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
import { institutionApi } from '@/api'

const loading = ref(false)
const saving  = ref(false)
const brokers = ref([])
const dialogVisible = ref(false)
const editing = ref(null)
const formRef = ref()

const form = reactive({ code: '', displayName: '', keywords: '' })
const rules = {
  code:        [{ required: true, message: '請輸入識別代碼' }],
  displayName: [{ required: true, message: '請輸入顯示名稱' }]
}

const splitKeywords = (kw) =>
  kw ? kw.split(',').map(s => s.trim()).filter(Boolean) : []

async function load() {
  loading.value = true
  try { brokers.value = await institutionApi.getAllBrokers() }
  finally { loading.value = false }
}

function openDialog(broker = null) {
  editing.value = broker
  if (broker) {
    form.code = broker.code
    form.displayName = broker.displayName
    form.keywords = broker.keywords || ''
  } else {
    form.code = ''
    form.displayName = ''
    form.keywords = ''
  }
  dialogVisible.value = true
}

async function save() {
  await formRef.value.validate()
  saving.value = true
  try {
    if (editing.value) {
      await institutionApi.updateBroker(editing.value.id, {
        displayName: form.displayName,
        keywords: form.keywords
      })
      ElMessage.success('已更新')
    } else {
      await institutionApi.createBroker({
        code: form.code,
        displayName: form.displayName,
        keywords: form.keywords
      })
      ElMessage.success('已新增')
    }
    dialogVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function toggleActive(broker) {
  const action = broker.active ? '停用' : '啟用'
  await ElMessageBox.confirm(`確定要${action}「${broker.displayName}」嗎？`, '確認', { type: 'warning' })
  await institutionApi.setBrokerActive(broker.id, !broker.active)
  ElMessage.success(`已${action}`)
  await load()
}

onMounted(load)
</script>

<style scoped>
.page-header { display: flex; align-items: center; gap: 12px; margin-bottom: 20px; }
.page-header h2 { margin: 0; flex: 1; }
.form-hint { font-size: 12px; color: #94a3b8; margin-top: 4px; }
</style>
