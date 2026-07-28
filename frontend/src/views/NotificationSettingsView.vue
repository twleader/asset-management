<template>
  <div v-loading="loading">
    <div class="page-header">
      <h2>📧 警示通知設定</h2>
      <el-button type="primary" :icon="Plus" @click="openDialog()">新增收件人</el-button>
    </div>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      style="margin-bottom: 16px;">
      <template #title>
        <span>股票觀察清單的警示條件觸發時，系統會自動寄 email 到下列「啟用」中的收件人。同一輪同時觸發多筆會合併成單一 digest 寄送。</span>
        <br>
        <span>Gmail 收件人可另開啟「加入 Google 日曆」，警示信會夾帶日曆邀請、由日曆推播提醒；需收件人的 Google 日曆維持「自動將邀請加入日曆」設定（預設為是），若設為「僅在我回覆時」則需在信中手動接受一次。</span>
      </template>
    </el-alert>

    <el-card>
      <el-table :data="recipients" size="small" stripe empty-text="尚無收件人，警示觸發時不會寄信">
        <el-table-column prop="email" label="Email" min-width="240" />
        <el-table-column label="狀態" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="row.active ? 'success' : 'danger'" size="small">
              {{ row.active ? '啟用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="Google 日曆" width="130" align="center">
          <template #default="{ row }">
            <el-switch
              v-if="isGmail(row.email)"
              v-model="row.addToCalendar"
              @change="toggleCalendar(row)" />
            <el-tooltip v-else content="僅 Gmail 收件人支援" placement="top">
              <span class="not-supported">—</span>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="建立時間" width="180">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="220" align="center">
          <template #default="{ row }">
            <el-button size="small" :icon="Edit" @click="openDialog(row)">編輯</el-button>
            <el-button
              size="small"
              :type="row.active ? 'warning' : 'success'"
              @click="toggleActive(row)">
              {{ row.active ? '停用' : '啟用' }}
            </el-button>
            <el-button size="small" type="danger" :icon="Delete" @click="remove(row)" />
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="editing ? '編輯收件人' : '新增收件人'" width="440px">
      <el-form :model="form" :rules="rules" ref="formRef" label-width="80px">
        <el-form-item label="Email" prop="email">
          <el-input v-model="form.email" placeholder="someone@example.com" />
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
import { Plus, Edit, Delete } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { bffApi } from '@/api'

const loading = ref(false)
const saving  = ref(false)
const recipients = ref([])
const dialogVisible = ref(false)
const editing = ref(null)
const formRef = ref()
const form = reactive({ email: '' })
const rules = {
  email: [
    { required: true, message: '請輸入 email' },
    { type: 'email', message: 'email 格式不正確' }
  ]
}

async function load() {
  loading.value = true
  try { recipients.value = await bffApi.notificationSettings.getRecipients() }
  finally { loading.value = false }
}

function openDialog(r = null) {
  editing.value = r
  form.email = r ? r.email : ''
  dialogVisible.value = true
}

async function save() {
  await formRef.value.validate()
  saving.value = true
  try {
    if (editing.value) {
      await bffApi.notificationSettings.updateRecipient(editing.value.id, { email: form.email })
      ElMessage.success('已更新')
    } else {
      await bffApi.notificationSettings.createRecipient({ email: form.email, active: true })
      ElMessage.success('已新增')
    }
    dialogVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function toggleActive(r) {
  const action = r.active ? '停用' : '啟用'
  await ElMessageBox.confirm(`確定要${action}收件人「${r.email}」嗎？`, '確認', { type: 'warning' })
  await bffApi.notificationSettings.toggleActive(r.id)
  ElMessage.success(`已${action}`)
  await load()
}

/** 與後端 NotificationRecipientService.isGmail 同判定；後端仍會擋，這裡只決定是否顯示開關。 */
function isGmail(email) {
  if (!email) return false
  const at = email.lastIndexOf('@')
  if (at < 0) return false
  const domain = email.slice(at + 1).trim().toLowerCase()
  return domain === 'gmail.com' || domain === 'googlemail.com'
}

async function toggleCalendar(r) {
  try {
    await bffApi.notificationSettings.toggleCalendar(r.id)
    ElMessage.success(r.addToCalendar ? '已加入 Google 日曆' : '已取消 Google 日曆')
    await load()
  } catch (e) {
    r.addToCalendar = !r.addToCalendar   // 後端拒絕時把 switch 還原
    throw e
  }
}

async function remove(r) {
  await ElMessageBox.confirm(`確定要刪除收件人「${r.email}」嗎？此操作無法復原。`, '確認', { type: 'warning' })
  await bffApi.notificationSettings.deleteRecipient(r.id)
  ElMessage.success('已刪除')
  await load()
}

function formatDateTime(s) {
  if (!s) return '-'
  return s.replace('T', ' ').slice(0, 16)
}

onMounted(load)
</script>

<style scoped>
.page-header { display: flex; align-items: center; gap: 12px; margin-bottom: 20px; }
.page-header h2 { margin: 0; flex: 1; }
.not-supported { color: var(--el-text-color-placeholder); }
</style>
