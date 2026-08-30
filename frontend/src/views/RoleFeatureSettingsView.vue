<template>
  <div v-loading="loading">
    <div class="page-header">
      <h2>🧩 角色功能管理</h2>
    </div>

    <p class="page-desc">
      切換每一項功能對「一般使用者」角色的開放狀態；管理者角色永遠看得到、用得到全部功能，不受此設定影響。
    </p>

    <el-card v-for="group in groupedFeatures" :key="group.name" class="group-card">
      <template #header>
        <span class="group-title">{{ group.name }}</span>
      </template>
      <el-table :data="group.items" size="small" stripe>
        <el-table-column prop="sortOrder" label="排序" width="70" align="center" />
        <el-table-column prop="displayName" label="功能名稱" min-width="160" />
        <el-table-column prop="code" label="路徑" min-width="200" />
        <el-table-column label="一般使用者可用" width="140" align="center">
          <template #default="{ row }">
            <el-switch
              :model-value="row.enabledForUser"
              :loading="row.saving"
              @change="(val) => toggle(row, val)"
            />
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ElMessage } from 'element-plus'
import { appFeatureSettingsApi, apiErrorMessage } from '@/api'

const loading = ref(false)
const features = ref([])

const groupedFeatures = computed(() => {
  const groups = new Map()
  for (const f of features.value) {
    const name = f.menuGroup || '其他'
    if (!groups.has(name)) groups.set(name, [])
    groups.get(name).push(f)
  }
  return Array.from(groups.entries()).map(([name, items]) => ({
    name,
    items: items.slice().sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0))
  }))
})

async function load() {
  loading.value = true
  try {
    const data = await appFeatureSettingsApi.getAll()
    features.value = (data || []).map(f => ({ ...f, saving: false }))
  } catch (e) {
    ElMessage.error(apiErrorMessage(e, '載入功能清單失敗'))
  } finally {
    loading.value = false
  }
}

async function toggle(row, enabled) {
  const previous = row.enabledForUser
  row.saving = true
  row.enabledForUser = enabled
  try {
    const updated = await appFeatureSettingsApi.setEnabledForUser(row.id, enabled)
    row.enabledForUser = updated?.enabledForUser ?? enabled
    ElMessage.success(`已${enabled ? '開放' : '關閉'}「${row.displayName}」`)
  } catch (e) {
    row.enabledForUser = previous
    ElMessage.error(apiErrorMessage(e, '更新失敗'))
  } finally {
    row.saving = false
  }
}

onMounted(load)
</script>

<style scoped>
.page-header { display: flex; align-items: center; gap: 12px; margin-bottom: 8px; }
.page-header h2 { margin: 0; flex: 1; }
.page-desc { color: #64748b; font-size: 13px; margin-bottom: 20px; }
.group-card { margin-bottom: 16px; }
.group-title { font-weight: 600; }
</style>
