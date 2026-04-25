<template>
  <el-config-provider :locale="zhTw">
    <el-container class="app-container">
      <!-- Sidebar -->
      <el-aside :width="collapsed ? '64px' : '220px'" class="sidebar">
        <div class="logo">
          <el-icon size="24" color="#409EFF"><DataLine /></el-icon>
          <span v-show="!collapsed" class="logo-text">資產管理</span>
        </div>

        <el-menu
          :default-active="$route.path"
          :collapse="collapsed"
          :collapse-transition="false"
          router
          background-color="#1e293b"
          text-color="#94a3b8"
          active-text-color="#60a5fa"
        >
            <el-menu-item v-for="item in mainMenuItems" :key="item.path" :index="item.path">
            <el-icon><component :is="item.icon" /></el-icon>
            <template #title>{{ item.title }}</template>
          </el-menu-item>

          <el-sub-menu index="settings">
            <template #title>
              <el-icon><Setting /></el-icon>
              <span>系統設定</span>
            </template>
            <el-menu-item index="/settings/banks">
              <el-icon><OfficeBuilding /></el-icon>
              <template #title>銀行設定</template>
            </el-menu-item>
            <el-menu-item index="/settings/brokers">
              <el-icon><TrendCharts /></el-icon>
              <template #title>券商設定</template>
            </el-menu-item>
            <el-menu-item index="/settings/deposit-types">
              <el-icon><Coin /></el-icon>
              <template #title>存款類型設定</template>
            </el-menu-item>
            <el-menu-item index="/settings/market-types">
              <el-icon><Connection /></el-icon>
              <template #title>市場類型設定</template>
            </el-menu-item>
            <el-menu-item index="/settings/transit-fund-types">
              <el-icon><Timer /></el-icon>
              <template #title>在途款項類型設定</template>
            </el-menu-item>
            <el-menu-item index="/settings/backup-restore">
              <el-icon><FolderOpened /></el-icon>
              <template #title>備份/還原 資料</template>
            </el-menu-item>
          </el-sub-menu>
        </el-menu>

        <div class="collapse-btn" @click="collapsed = !collapsed">
          <el-icon><component :is="collapsed ? 'Expand' : 'Fold'" /></el-icon>
        </div>
      </el-aside>

      <!-- Main Content -->
      <el-container>
        <el-header class="app-header">
          <div class="header-left">
            <span class="page-title">{{ $route.meta.title }}</span>
          </div>
          <div class="header-right">
            <el-tag type="success" size="small">
              {{ today }}
            </el-tag>
          </div>
        </el-header>

        <el-main class="app-main">
          <router-view v-slot="{ Component }">
            <transition name="fade" mode="out-in">
              <component :is="Component" />
            </transition>
          </router-view>
        </el-main>
      </el-container>
    </el-container>
  </el-config-provider>
</template>

<script setup>
import zhTw from 'element-plus/dist/locale/zh-tw.mjs'
import dayjs from 'dayjs'
import { useAssetStore } from '@/stores/assetStore'

const collapsed = ref(false)
const today = dayjs().format('YYYY/MM/DD')
const store = useAssetStore()

// 載入快照列表以取得最新快照 ID
onMounted(async () => {
  if (store.snapshots.length === 0) {
    await store.fetchSnapshots()
  }
})

const mainMenuItems = computed(() => [
  { path: '/dashboard', title: '總覽儀表板', icon: 'DataLine' },
  { path: '/history', title: '歷年資產管理', icon: 'TrendCharts' },
  { path: '/realized-gains', title: '已實現損益', icon: 'Money' },
  { path: '/stocks', title: '股票觀察', icon: 'View' },
  { path: '/trading-calendar', title: '交易日曆', icon: 'AlarmClock' },
  { path: '/exchange-rate', title: '台幣兌美元', icon: 'Money' }
])
</script>

<style>
* { box-sizing: border-box; margin: 0; padding: 0; }

body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang TC', sans-serif;
  background: #f0f4f8;
}

.app-container {
  height: 100vh;
}

.sidebar {
  background: #1e293b;
  display: flex;
  flex-direction: column;
  transition: width 0.3s;
  overflow: hidden;
}

.logo {
  height: 64px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 20px;
  border-bottom: 1px solid #334155;
}

.logo-text {
  color: #f1f5f9;
  font-size: 18px;
  font-weight: 700;
  white-space: nowrap;
}

.el-menu {
  border-right: none !important;
  flex: 1;
}

.el-menu-item.is-active {
  background: #1d4ed8 !important;
}

.collapse-btn {
  padding: 16px 20px;
  color: #94a3b8;
  cursor: pointer;
  display: flex;
  align-items: center;
  border-top: 1px solid #334155;
  transition: color 0.2s;
}
.collapse-btn:hover { color: #60a5fa; }

.app-header {
  background: white;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  border-bottom: 1px solid #e2e8f0;
  box-shadow: 0 1px 4px rgba(0,0,0,0.05);
}

.page-title {
  font-size: 18px;
  font-weight: 600;
  color: #1e293b;
}

.app-main {
  padding: 24px;
  overflow-y: auto;
  background: #f0f4f8;
}

.fade-enter-active, .fade-leave-active { transition: opacity 0.2s ease; }
.fade-enter-from, .fade-leave-to { opacity: 0; }

/* Global card style */
.el-card { border-radius: 12px !important; border: none !important; box-shadow: 0 1px 8px rgba(0,0,0,0.08) !important; }
</style>
