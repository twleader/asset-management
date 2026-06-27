<template>
  <div>
    <el-card>
      <template #header>
        <div class="card-header">
          <span>使用者管理</span>
          <el-button :icon="Refresh" circle @click="load" />
        </div>
      </template>

      <el-table :data="users" v-loading="loading" stripe>
        <el-table-column label="使用者" min-width="220">
          <template #default="{ row }">
            <div class="user-cell">
              <el-avatar :size="32" :src="row.picture">{{ initials(row) }}</el-avatar>
              <div class="user-meta">
                <div class="user-name">{{ row.name || '—' }}</div>
                <div class="user-email">{{ row.email }}</div>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="角色" width="120" align="center">
          <template #default="{ row }">
            <el-tag :type="row.role === 'ADMIN' ? 'warning' : 'info'" effect="plain">
              {{ row.role === 'ADMIN' ? '管理者' : '一般使用者' }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="狀態" width="120" align="center">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>

        <el-table-column label="操作" min-width="280" align="right">
          <template #default="{ row }">
            <template v-if="row.email === ADMIN_EMAIL">
              <span class="self-hint">管理者帳號</span>
            </template>
            <template v-else>
              <el-button
                v-if="row.status !== 'ACTIVE'"
                type="success" size="small" plain
                @click="setStatus(row, 'ACTIVE')">核准 / 啟用</el-button>
              <el-button
                v-if="row.status === 'ACTIVE'"
                type="danger" size="small" plain
                @click="setStatus(row, 'DISABLED')">停用</el-button>
              <el-button
                v-if="row.role === 'USER'"
                size="small" plain
                @click="setRole(row, 'ADMIN')">設為管理者</el-button>
              <el-button
                v-else
                size="small" plain
                @click="setRole(row, 'USER')">取消管理者</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { userManagementApi } from '@/api'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'

const ADMIN_EMAIL = 'tw.leader@gmail.com'

const users = ref([])
const loading = ref(false)

function initials(row) {
  const s = row.name || row.email || '?'
  return s.trim().charAt(0).toUpperCase()
}
function statusLabel(s) {
  return s === 'ACTIVE' ? '已啟用' : s === 'PENDING' ? '待核准' : '已停用'
}
function statusType(s) {
  return s === 'ACTIVE' ? 'success' : s === 'PENDING' ? 'warning' : 'danger'
}

async function load() {
  loading.value = true
  try {
    users.value = await userManagementApi.list()
  } finally {
    loading.value = false
  }
}

async function setStatus(row, status) {
  const verb = status === 'ACTIVE' ? '核准/啟用' : '停用'
  try {
    await ElMessageBox.confirm(`確定要${verb}使用者「${row.email}」嗎？`, '確認', { type: 'warning' })
  } catch { return }
  await userManagementApi.updateStatus(row.id, status)
  ElMessage.success(`已${verb}`)
  load()
}

async function setRole(row, role) {
  const verb = role === 'ADMIN' ? '設為管理者' : '取消管理者'
  try {
    await ElMessageBox.confirm(`確定要將「${row.email}」${verb}嗎？`, '確認', { type: 'warning' })
  } catch { return }
  await userManagementApi.updateRole(row.id, role)
  ElMessage.success(`已${verb}`)
  load()
}

onMounted(load)
</script>

<style scoped>
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.user-cell {
  display: flex;
  align-items: center;
  gap: 12px;
}
.user-name {
  font-weight: 600;
  color: #1e293b;
}
.user-email {
  font-size: 12px;
  color: #94a3b8;
}
.self-hint {
  color: #94a3b8;
  font-size: 13px;
}
</style>
