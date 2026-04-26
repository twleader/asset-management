<template>
  <div>
    <div class="page-header">
      <div>
        <el-button type="primary" :icon="Plus" @click="$router.push('/snapshots/new')">新增快照</el-button>
        <el-button :icon="Download" :loading="exporting" @click="handleExport"
          style="margin-left:10px">匯出 Excel</el-button>
      </div>
    </div>

    <el-card v-loading="loading">
      <el-table :data="snapshots" row-key="id" @row-click="row => $router.push('/snapshots/'+row.id)">
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
            <el-popconfirm title="確定刪除此快照？" @confirm="onDelete(row.id)">
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
import { Plus, Download, Edit, Delete } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'
import { bffApi } from '@/api'

const snapshots = ref([])
const loading = ref(false)
const exporting = ref(false)

const reload = async () => {
  loading.value = true
  try { snapshots.value = await bffApi.snapshotList.getAll() }
  finally { loading.value = false }
}
onMounted(reload)

const onDelete = async (id) => {
  await bffApi.snapshotList.delete(id)
  await reload()
}

const fmt = (v) => {
  if (v == null) return '-'
  const n = Number(v)
  return `$${n.toLocaleString('zh-TW', { maximumFractionDigits: 0 })}`
}

async function handleExport() {
  exporting.value = true
  try {
    const blob = await bffApi.snapshotList.exportExcel()
    downloadBlob(blob, `資產管理_${dayjs().format('YYYYMMDD')}.xlsx`)
    ElMessage.success('匯出完成')
  } finally {
    exporting.value = false
  }
}

function downloadBlob(blob, filename) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}
</script>

<style scoped>
.page-header { display: flex; justify-content: flex-end; margin-bottom: 16px; }
.profit { color: #16a34a; font-weight: 600; }
.loss { color: #dc2626; font-weight: 600; }
:deep(tr) { cursor: pointer; }
</style>
