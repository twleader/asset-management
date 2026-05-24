<template>
  <div v-loading="loading">
    <div class="page-header">
      <h2>📋 代繳設定</h2>
      <el-button :icon="Setting" @click="categoryDialogVisible = true">分類維護</el-button>
      <el-button type="primary" :icon="Plus" @click="openAccountDialog()">新增代繳項目</el-button>
    </div>

    <!-- 分類快速過濾 -->
    <el-card style="margin-bottom: 16px;">
      <div class="filter-row">
        <span class="filter-label">分類過濾：</span>
        <el-radio-group v-model="filterCategoryId" size="small">
          <el-radio-button :value="null">全部</el-radio-button>
          <el-radio-button v-for="c in activeCategories" :key="c.id" :value="c.id">
            {{ c.displayName }}
          </el-radio-button>
        </el-radio-group>
      </div>
    </el-card>

    <!-- 代繳記錄主表 -->
    <el-card>
      <el-table :data="filteredAccounts" size="small" stripe :row-class-name="rowClass">
        <el-table-column label="分類" width="100">
          <template #default="{ row }">
            <el-tag :type="categoryTagType(row.categoryCode)" size="small">
              {{ row.categoryDisplayName }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="itemName" label="項目" min-width="180" />
        <el-table-column prop="paymentAccount" label="帳戶" min-width="160">
          <template #default="{ row }">
            <span v-if="row.paymentAccount">{{ row.paymentAccount }}</span>
            <span v-else class="text-muted">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="note" label="備註" min-width="260">
          <template #default="{ row }">
            <span v-if="row.note">{{ row.note }}</span>
            <span v-else class="text-muted">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="sortOrder" label="排序" width="80" align="center" />
        <el-table-column label="操作" width="160" align="center">
          <template #default="{ row }">
            <el-button size="small" :icon="Edit" @click="openAccountDialog(row)">編輯</el-button>
            <el-button size="small" type="danger" :icon="Delete" @click="deleteAccount(row)">刪除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="尚無代繳項目，點右上「新增代繳項目」開始建立" />
        </template>
      </el-table>
    </el-card>

    <!-- 代繳項目 Dialog -->
    <el-dialog v-model="accountDialogVisible" :title="accountEditing ? '編輯代繳項目' : '新增代繳項目'" width="520px">
      <el-form :model="accountForm" :rules="accountRules" ref="accountFormRef" label-width="90px">
        <el-form-item label="分類" prop="categoryId">
          <el-select v-model="accountForm.categoryId" placeholder="請選擇分類" style="width: 100%;">
            <el-option
              v-for="c in activeCategories"
              :key="c.id"
              :label="c.displayName"
              :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="項目" prop="itemName">
          <el-input v-model="accountForm.itemName" placeholder="如 市話 + MOD、台電電費" />
        </el-form-item>
        <el-form-item label="帳戶">
          <el-input v-model="accountForm.paymentAccount" placeholder="如 momo 信用卡、台北富邦銀行帳戶" />
        </el-form-item>
        <el-form-item label="備註">
          <el-input v-model="accountForm.note" type="textarea" :rows="2" placeholder="如 用戶號碼: Y046509" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="accountForm.sortOrder" :min="0" :max="999" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="accountDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveAccount">儲存</el-button>
      </template>
    </el-dialog>

    <!-- 分類維護 Dialog -->
    <el-dialog v-model="categoryDialogVisible" title="代繳分類維護" width="640px">
      <div class="dialog-toolbar">
        <el-button size="small" type="primary" :icon="Plus" @click="openCategoryEditor()">新增分類</el-button>
      </div>
      <el-table :data="categories" size="small" stripe>
        <el-table-column prop="sortOrder" label="排序" width="70" align="center" />
        <el-table-column prop="code" label="代碼" width="120" />
        <el-table-column prop="displayName" label="顯示名稱" min-width="120" />
        <el-table-column label="狀態" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.active ? 'success' : 'danger'" size="small">
              {{ row.active ? '啟用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180" align="center">
          <template #default="{ row }">
            <el-button size="small" :icon="Edit" @click="openCategoryEditor(row)">編輯</el-button>
            <el-button
              size="small"
              :type="row.active ? 'danger' : 'success'"
              @click="toggleCategoryActive(row)">
              {{ row.active ? '停用' : '啟用' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 分類編輯內嵌 dialog -->
      <el-dialog
        v-model="categoryEditorVisible"
        :title="categoryEditing ? '編輯分類' : '新增分類'"
        width="420px"
        append-to-body>
        <el-form :model="categoryForm" :rules="categoryRules" ref="categoryFormRef" label-width="90px">
          <el-form-item label="代碼" prop="code">
            <el-input v-model="categoryForm.code" :disabled="!!categoryEditing" placeholder="如 bill" />
          </el-form-item>
          <el-form-item label="顯示名稱" prop="displayName">
            <el-input v-model="categoryForm.displayName" placeholder="如 繳費" />
          </el-form-item>
          <el-form-item label="排序">
            <el-input-number v-model="categoryForm.sortOrder" :min="0" :max="999" />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="categoryEditorVisible = false">取消</el-button>
          <el-button type="primary" :loading="categorySaving" @click="saveCategory">儲存</el-button>
        </template>
      </el-dialog>
    </el-dialog>
  </div>
</template>

<script setup>
import { Plus, Edit, Delete, Setting } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { bffApi } from '@/api'

const loading = ref(false)
const saving = ref(false)
const categorySaving = ref(false)

const categories = ref([])
const accounts = ref([])
const filterCategoryId = ref(null)

const accountDialogVisible = ref(false)
const accountEditing = ref(null)
const accountFormRef = ref()
const accountForm = reactive({ categoryId: null, itemName: '', paymentAccount: '', note: '', sortOrder: 0 })
const accountRules = {
  categoryId: [{ required: true, message: '請選擇分類' }],
  itemName:   [{ required: true, message: '請輸入項目' }]
}

const categoryDialogVisible = ref(false)
const categoryEditorVisible = ref(false)
const categoryEditing = ref(null)
const categoryFormRef = ref()
const categoryForm = reactive({ code: '', displayName: '', sortOrder: 0 })
const categoryRules = {
  code:        [{ required: true, message: '請輸入代碼' }],
  displayName: [{ required: true, message: '請輸入顯示名稱' }]
}

const activeCategories = computed(() => categories.value.filter(c => c.active))

const filteredAccounts = computed(() => {
  if (filterCategoryId.value == null) return accounts.value
  return accounts.value.filter(a => a.categoryId === filterCategoryId.value)
})

function rowClass({ row }) {
  return categories.value.find(c => c.id === row.categoryId && !c.active) ? 'inactive-row' : ''
}

const tagPalette = ['', 'success', 'warning', 'danger', 'info']
function categoryTagType(code) {
  const idx = categories.value.findIndex(c => c.code === code)
  return tagPalette[idx % tagPalette.length] || ''
}

async function load() {
  loading.value = true
  try {
    const [cats, accs] = await Promise.allSettled([
      bffApi.paymentAccountSettings.getCategories(),
      bffApi.paymentAccountSettings.getAccounts()
    ])
    if (cats.status === 'fulfilled') categories.value = cats.value
    if (accs.status === 'fulfilled') accounts.value = accs.value
  } finally {
    loading.value = false
  }
}

function openAccountDialog(item = null) {
  accountEditing.value = item
  if (item) {
    accountForm.categoryId = item.categoryId
    accountForm.itemName = item.itemName
    accountForm.paymentAccount = item.paymentAccount ?? ''
    accountForm.note = item.note ?? ''
    accountForm.sortOrder = item.sortOrder ?? 0
  } else {
    accountForm.categoryId = activeCategories.value[0]?.id ?? null
    accountForm.itemName = ''
    accountForm.paymentAccount = ''
    accountForm.note = ''
    accountForm.sortOrder = 0
  }
  accountDialogVisible.value = true
}

async function saveAccount() {
  await accountFormRef.value.validate()
  saving.value = true
  try {
    const payload = {
      categoryId: accountForm.categoryId,
      itemName: accountForm.itemName.trim(),
      paymentAccount: accountForm.paymentAccount?.trim() || null,
      note: accountForm.note?.trim() || null,
      sortOrder: accountForm.sortOrder
    }
    if (accountEditing.value) {
      await bffApi.paymentAccountSettings.updateAccount(accountEditing.value.id, payload)
      ElMessage.success('已更新')
    } else {
      await bffApi.paymentAccountSettings.createAccount(payload)
      ElMessage.success('已新增')
    }
    accountDialogVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function deleteAccount(row) {
  await ElMessageBox.confirm(`確定要刪除「${row.itemName}」嗎？`, '確認刪除', { type: 'warning' })
  await bffApi.paymentAccountSettings.deleteAccount(row.id)
  ElMessage.success('已刪除')
  await load()
}

function openCategoryEditor(item = null) {
  categoryEditing.value = item
  if (item) {
    categoryForm.code = item.code
    categoryForm.displayName = item.displayName
    categoryForm.sortOrder = item.sortOrder ?? 0
  } else {
    categoryForm.code = ''
    categoryForm.displayName = ''
    categoryForm.sortOrder = 0
  }
  categoryEditorVisible.value = true
}

async function saveCategory() {
  await categoryFormRef.value.validate()
  categorySaving.value = true
  try {
    if (categoryEditing.value) {
      await bffApi.paymentAccountSettings.updateCategory(categoryEditing.value.id, {
        displayName: categoryForm.displayName,
        sortOrder: categoryForm.sortOrder
      })
      ElMessage.success('已更新')
    } else {
      await bffApi.paymentAccountSettings.createCategory({
        code: categoryForm.code,
        displayName: categoryForm.displayName,
        sortOrder: categoryForm.sortOrder
      })
      ElMessage.success('已新增')
    }
    categoryEditorVisible.value = false
    await load()
  } finally {
    categorySaving.value = false
  }
}

async function toggleCategoryActive(row) {
  const action = row.active ? '停用' : '啟用'
  await ElMessageBox.confirm(`確定要${action}分類「${row.displayName}」嗎？`, '確認', { type: 'warning' })
  await bffApi.paymentAccountSettings.setCategoryActive(row.id, !row.active)
  ElMessage.success(`已${action}`)
  await load()
}

onMounted(load)
</script>

<style scoped>
.page-header { display: flex; align-items: center; gap: 12px; margin-bottom: 20px; }
.page-header h2 { margin: 0; flex: 1; }
.filter-row { display: flex; align-items: center; gap: 12px; }
.filter-label { color: #64748b; font-size: 13px; }
.dialog-toolbar { display: flex; justify-content: flex-end; margin-bottom: 12px; }
.text-muted { color: #94a3b8; }
:deep(.inactive-row) { opacity: 0.5; }
</style>
