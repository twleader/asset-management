<template>
  <div>
    <div class="page-header">
      <div>
        <el-button type="primary" :icon="Plus" @click="$router.push('/snapshots/new')">新增快照</el-button>
        <el-upload :show-file-list="false" :before-upload="handleImport" accept=".xlsx" style="display:inline-block;margin-left:10px">
          <el-button :icon="Upload" :loading="importing">匯入 Excel</el-button>
        </el-upload>
      </div>
    </div>

    <el-card v-loading="store.loading">
      <el-table :data="store.snapshots" row-key="id" @row-click="row => $router.push('/snapshots/'+row.id)">
        <el-table-column prop="snapshotDate" label="日期" width="120" sortable />
        <el-table-column label="存款" align="right" :formatter="(r) => fmt(r.totalDeposit)" />
        <el-table-column label="信託基金現值" align="right" :formatter="(r) => fmt(r.totalFundValue)" />
        <el-table-column label="股票現值" align="right" :formatter="(r) => fmt(r.totalStockValue)" />
        <el-table-column label="資產總計" align="right" min-width="130">
          <template #default="{ row }">
            <strong>{{ fmt(row.totalAssets) }}</strong>
          </template>
        </el-table-column>
        <el-table-column label="投資損益" align="right">
          <template #default="{ row }">
            <span :class="(row.stockProfit + row.fundProfit) >= 0 ? 'profit' : 'loss'">
              {{ fmt(Number(row.stockProfit||0) + Number(row.fundProfit||0)) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="預估配息" align="right" :formatter="(r) => fmt(r.estimatedAnnualDividend)" />
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button size="small" :icon="Edit" @click.stop="$router.push('/snapshots/'+row.id+'/edit')">編輯</el-button>
            <el-popconfirm title="確定刪除此快照？" @confirm="store.deleteSnapshot(row.id)">
              <template #reference>
                <el-button size="small" type="danger" :icon="Delete" @click.stop />
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { Plus, Upload, Edit, Delete } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useAssetStore } from '@/stores/assetStore'

const store = useAssetStore()
const importing = ref(false)

onMounted(() => store.fetchSnapshots())

const fmt = (v) => {
  if (v == null) return '-'
  const n = Number(v)
  return `$${n.toLocaleString('zh-TW', { maximumFractionDigits: 0 })}`
}

const handleImport = async (file) => {
  importing.value = true
  try {
    const result = await store.importExcel(file)
    ElMessage.success(`匯入完成：${result.snapshotsImported} 個快照，${result.gainsImported} 筆損益`)
    if (result.errors?.length) {
      result.errors.forEach(e => ElMessage.warning(e))
    }
  } finally {
    importing.value = false
  }
  return false
}
</script>

<style scoped>
.page-header { display: flex; justify-content: flex-end; margin-bottom: 16px; }
.profit { color: #16a34a; font-weight: 600; }
.loss { color: #dc2626; font-weight: 600; }
:deep(tr) { cursor: pointer; }
</style>
