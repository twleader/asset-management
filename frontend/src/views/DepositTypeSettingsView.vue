<template>
  <div v-loading="loading">
    <div class="page-header">
      <h2>💳 存款類型設定</h2>
      <el-button type="primary" :icon="Plus" @click="openDialog()">新增類型</el-button>
    </div>

    <el-card>
      <el-table :data="depositTypes" size="small" stripe>
        <el-table-column prop="sortOrder" label="排序" width="70" align="center" />
        <el-table-column prop="code" label="代碼（存入資料庫的值）" width="160" />
        <el-table-column prop="displayName" label="顯示名稱" min-width="160" />
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
    <el-dialog v-model="dialogVisible" :title="editing ? '編輯存款類型' : '新增存款類型'" width="420px">
      <el-form :model="form" :rules="rules" ref="formRef" label-width="110px">
        <el-form-item label="代碼" prop="code">
          <el-input v-model="form.code" :disabled="!!editing" placeholder="如 活存（即存入 DB 的值）" />
        </el-form-item>
        <el-form-item label="顯示名稱" prop="displayName">
          <el-input v-model="form.displayName" placeholder="如 台幣活存" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sortOrder" :min="0" :max="999" />
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
const depositTypes = ref([])
const dialogVisible = ref(false)
const editing = ref(null)
const formRef = ref()

const form = reactive({ code: '', displayName: '', sortOrder: 0 })
const rules = {
  code:        [{ required: true, message: '請輸入代碼' }],
  displayName: [{ required: true, message: '請輸入顯示名稱' }]
}

async function load() {
  loading.value = true
  try { depositTypes.value = await bffApi.depositTypeSettings.getAll() }
  finally { loading.value = false }
}

function openDialog(item = null) {
  editing.value = item
  if (item) {
    form.code = item.code
    form.displayName = item.displayName
    form.sortOrder = item.sortOrder ?? 0
  } else {
    form.code = ''
    form.displayName = ''
    form.sortOrder = 0
  }
  dialogVisible.value = true
}

async function save() {
  await formRef.value.validate()
  saving.value = true
  try {
    if (editing.value) {
      await bffApi.depositTypeSettings.update(editing.value.id, {
        displayName: form.displayName,
        sortOrder: form.sortOrder
      })
      ElMessage.success('已更新')
    } else {
      await bffApi.depositTypeSettings.create({
        code: form.code,
        displayName: form.displayName,
        sortOrder: form.sortOrder
      })
      ElMessage.success('已新增')
    }
    dialogVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function toggleActive(item) {
  const action = item.active ? '停用' : '啟用'
  await ElMessageBox.confirm(`確定要${action}「${item.displayName}」嗎？`, '確認', { type: 'warning' })
  await bffApi.depositTypeSettings.setActive(item.id, !item.active)
  ElMessage.success(`已${action}`)
  await load()
}

onMounted(load)
</script>

<style scoped>
.page-header { display: flex; align-items: center; gap: 12px; margin-bottom: 20px; }
.page-header h2 { margin: 0; flex: 1; }
</style>
