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
            <el-menu-item index="/settings/asset-classes">
              <el-icon><PieChart /></el-icon>
              <template #title>資產類別歸類</template>
            </el-menu-item>
            <el-menu-item index="/settings/transit-fund-types">
              <el-icon><Timer /></el-icon>
              <template #title>在途款項類型設定</template>
            </el-menu-item>
            <el-menu-item index="/settings/funds">
              <el-icon><Money /></el-icon>
              <template #title>信託基金設定</template>
            </el-menu-item>
            <el-menu-item index="/settings/notifications">
              <el-icon><Bell /></el-icon>
              <template #title>警示通知設定</template>
            </el-menu-item>
            <!-- Requirement 28：備份/還原與使用者管理僅管理者可見 -->
            <el-menu-item v-if="auth.isAdmin" index="/settings/backup-restore">
              <el-icon><FolderOpened /></el-icon>
              <template #title>備份/還原 資料</template>
            </el-menu-item>
            <el-menu-item v-if="auth.isAdmin" index="/settings/users">
              <el-icon><User /></el-icon>
              <template #title>使用者管理</template>
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
          <div class="header-right header-clocks">
            <span class="clock-tag clock-tpe">
              <span class="clock-label">TPE</span> <span class="clock-time">{{ tpeNow }}</span>
            </span>
            <span class="clock-tag clock-nyc">
              <span class="clock-label">NYC</span> <span class="clock-time">{{ nycNow }}</span>
            </span>
            <span class="clock-tag clock-lon">
              <span class="clock-label">LON</span> <span class="clock-time">{{ lonNow }}</span>
            </span>

            <!-- Requirement 28：管理者使用者切換（代看） -->
            <el-select
              v-if="auth.isAdmin"
              :model-value="auth.effectiveUserId"
              size="small"
              class="impersonate-select"
              :class="{ 'is-impersonating': auth.isImpersonating }"
              placeholder="切換使用者"
              @change="onImpersonate"
            >
              <el-option
                v-for="u in auth.switchableUsers"
                :key="u.id"
                :label="impersonateLabel(u)"
                :value="u.id"
              />
            </el-select>

            <!-- Requirement 28：登入者資訊 + 登出 -->
            <el-dropdown v-if="auth.isLoggedIn" trigger="click" @command="onUserCommand">
              <span class="user-chip">
                <el-avatar :size="26" :src="auth.me?.picture">{{ userInitial }}</el-avatar>
                <span class="user-chip-name">{{ auth.me?.name || auth.me?.email }}</span>
                <el-icon><ArrowDown /></el-icon>
              </span>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item disabled>{{ auth.me?.email }}</el-dropdown-item>
                  <el-dropdown-item v-if="auth.isImpersonating" command="stopImpersonate" divided>
                    結束代看（{{ auth.effectiveUserName }}）
                  </el-dropdown-item>
                  <el-dropdown-item command="logout" divided>登出</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
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
import { ElLoading } from 'element-plus'
import { useAssetStore } from '@/stores/assetStore'
import { useAuthStore } from '@/stores/authStore'

const collapsed = ref(false)
const store = useAssetStore()
const auth = useAuthStore()

// Requirement 28：登入者顯示與管理者代看
const userInitial = computed(() => {
  const s = auth.me?.name || auth.me?.email || '?'
  return s.trim().charAt(0).toUpperCase()
})
function impersonateLabel(u) {
  const role = u.role === 'ADMIN' ? '（管理者）' : ''
  const pending = u.status !== 'ACTIVE' ? '〔待核准〕' : ''
  return `${u.name || u.email}${role}${pending}`
}
async function switchAndReload(userId) {
  // 切換代看對象後整頁重載，確保所有頁面資料以新視角重新取得；過程顯示全屏 loading
  const loading = ElLoading.service({ fullscreen: true, lock: true, text: '切換使用者中，載入資料…' })
  try {
    await auth.impersonate(userId)
    window.location.reload()   // reload 後 loading 隨頁面卸載自然消失
  } catch (e) {
    loading.close()
  }
}
function onImpersonate(userId) {
  switchAndReload(userId)
}
async function onUserCommand(cmd) {
  if (cmd === 'logout') {
    await auth.logout()
  } else if (cmd === 'stopImpersonate') {
    switchAndReload(null)
  }
}

// 雙時區即時時鐘（每秒更新）
const tpeNow = ref('')
const nycNow = ref('')
const lonNow = ref('')
let clockTimer = null
function fmtNow(tz) {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: tz, year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false
  }).formatToParts(new Date())
  const get = t => parts.find(p => p.type === t)?.value ?? ''
  return `${get('year')}/${get('month')}/${get('day')} ${get('hour')}:${get('minute')}:${get('second')}`
}
function tickClock() {
  tpeNow.value = fmtNow('Asia/Taipei')
  nycNow.value = fmtNow('America/New_York')
  lonNow.value = fmtNow('Europe/London')
}
tickClock()

// 載入快照列表以取得最新快照 ID
onMounted(async () => {
  clockTimer = setInterval(tickClock, 1000)
  if (store.snapshots.length === 0) {
    await store.fetchSnapshots()
  }
})

onUnmounted(() => {
  if (clockTimer) clearInterval(clockTimer)
})

const mainMenuItems = computed(() => [
  { path: '/dashboard', title: '總覽儀表板', icon: 'DataLine' },
  { path: '/history', title: '歷年資產管理', icon: 'TrendCharts' },
  { path: '/gdp-twse', title: '股市分析', icon: 'TrendCharts' },
  { path: '/today-market-analysis', title: '今日股市分析', icon: 'Sunrise' },
  { path: '/stocks', title: '股票觀察', icon: 'View' },
  { path: '/realized-gains', title: '已實現損益', icon: 'Money' },
  { path: '/trading-calendar', title: '交易日曆', icon: 'AlarmClock' },
  { path: '/exchange-rate', title: '台幣兌美元', icon: 'Money' },
  { path: '/payment-accounts', title: '自動代繳', icon: 'Tickets' }
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
  overflow-y: auto;
  /* 隱藏自訂捲軸視覺，但仍可滾動 */
  scrollbar-width: thin;
}
.el-menu::-webkit-scrollbar { width: 6px; }
.el-menu::-webkit-scrollbar-thumb { background: #475569; border-radius: 3px; }

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

/* 標頭雙時區時鐘：間距 11px、整體左移 10px、字型略大、用等寬數字避免跳動 */
.header-clocks { display: flex; gap: 11px; margin-right: 10px; }
.clock-tag {
  display: inline-flex; align-items: center; gap: 6px;
  font-size: 13px; padding: 4px 12px; border-radius: 6px;
  color: #fff; font-weight: 500; letter-spacing: 0.2px;
}
.clock-tpe { background: #16a34a; box-shadow: 0 1px 2px rgba(22,163,74,0.3); }
.clock-nyc { background: #d97706; box-shadow: 0 1px 2px rgba(217,119,6,0.3); }
.clock-lon { background: #0ea5e9; box-shadow: 0 1px 2px rgba(14,165,233,0.3); }
.clock-label { font-weight: 700; font-size: 12px; opacity: 0.9; }
.clock-time { font-variant-numeric: tabular-nums; font-feature-settings: "tnum"; }

/* Requirement 28：使用者切換 + 登入者資訊 */
.impersonate-select { width: 180px; margin-left: 4px; }
.impersonate-select.is-impersonating :deep(.el-select__wrapper) {
  box-shadow: 0 0 0 1px #d97706 inset;
  background: #fffbeb;
}
.user-chip {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 8px;
  color: #1e293b;
  transition: background 0.2s;
}
.user-chip:hover { background: #f1f5f9; }
.user-chip-name { font-size: 13px; font-weight: 500; max-width: 140px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

/* Global card style */
.el-card { border-radius: 12px !important; border: none !important; box-shadow: 0 1px 8px rgba(0,0,0,0.08) !important; }
</style>
