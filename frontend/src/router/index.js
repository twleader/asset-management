import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      redirect: '/dashboard'
    },
    {
      path: '/dashboard',
      name: 'Dashboard',
      component: () => import('@/views/DashboardView.vue'),
      meta: { title: '總覽儀表板', icon: 'DataLine' }
    },
    {
      path: '/snapshots',
      name: 'Snapshots',
      component: () => import('@/views/SnapshotListView.vue'),
      meta: { title: '資產快照', icon: 'Calendar' }
    },
    {
      path: '/snapshots/:id',
      name: 'SnapshotDetail',
      component: () => import('@/views/SnapshotDetailView.vue'),
      meta: { title: '快照詳情', hidden: true }
    },
    {
      path: '/snapshots/new',
      name: 'SnapshotNew',
      component: () => import('@/views/SnapshotFormView.vue'),
      meta: { title: '新增快照', hidden: true }
    },
    {
      path: '/snapshots/:id/edit',
      name: 'SnapshotEdit',
      component: () => import('@/views/SnapshotFormView.vue'),
      meta: { title: '管理資產', hidden: true }
    },
    {
      path: '/history',
      name: 'History',
      component: () => import('@/views/AssetHistoryView.vue'),
      meta: { title: '歷年資產管理', icon: 'TrendCharts' }
    },
    {
      path: '/realized-gains',
      name: 'RealizedGains',
      component: () => import('@/views/RealizedGainView.vue'),
      meta: { title: '已實現損益', icon: 'Money' }
    },
    {
      path: '/trading-calendar',
      name: 'TradingCalendar',
      component: () => import('@/views/TradingCalendarView.vue'),
      meta: { title: '交易日曆', icon: 'AlarmClock' }
    },
    {
      path: '/exchange-rate',
      name: 'ExchangeRate',
      component: () => import('@/views/ExchangeRateView.vue'),
      meta: { title: '台幣兌美元', icon: 'Money' }
    },
    {
      path: '/gdp-twse',
      name: 'GdpTwse',
      component: () => import('@/views/GdpTwseView.vue'),
      meta: { title: '股市分析', icon: 'TrendCharts' }
    },
    {
      path: '/stocks',
      name: 'StockMonitor',
      component: () => import('@/views/StockMonitorView.vue'),
      meta: { title: '股票觀察', icon: 'View' }
    },
    {
      path: '/stock-alerts',
      redirect: { path: '/stocks', query: { tab: 'alert' } },
      meta: { hidden: true }
    },
    {
      path: '/watch-stocks',
      redirect: { path: '/stocks', query: { tab: 'watch' } },
      meta: { hidden: true }
    },
    {
      path: '/settings/banks',
      name: 'BankSettings',
      component: () => import('@/views/BankSettingsView.vue'),
      meta: { title: '銀行設定', icon: 'Setting' }
    },
    {
      path: '/settings/brokers',
      name: 'BrokerSettings',
      component: () => import('@/views/BrokerSettingsView.vue'),
      meta: { title: '券商設定', icon: 'Setting' }
    },
    {
      path: '/settings/deposit-types',
      name: 'DepositTypeSettings',
      component: () => import('@/views/DepositTypeSettingsView.vue'),
      meta: { title: '存款類型設定', icon: 'Setting' }
    },
    {
      path: '/settings/market-types',
      name: 'MarketTypeSettings',
      component: () => import('@/views/MarketTypeSettingsView.vue'),
      meta: { title: '市場類型設定', icon: 'Setting' }
    },
    {
      path: '/settings/transit-fund-types',
      name: 'TransitFundTypeSettings',
      component: () => import('@/views/TransitFundTypeSettingsView.vue'),
      meta: { title: '在途款項類型設定', icon: 'Setting' }
    },
    {
      path: '/settings/funds',
      name: 'FundSettings',
      component: () => import('@/views/FundSettingsView.vue'),
      meta: { title: '信託基金設定', icon: 'Setting' }
    },
    {
      path: '/payment-accounts',
      name: 'PaymentAccountSettings',
      component: () => import('@/views/PaymentAccountSettingsView.vue'),
      meta: { title: '自動代繳', icon: 'Tickets' }
    },
    {
      path: '/settings/payment-accounts',
      redirect: '/payment-accounts',
      meta: { hidden: true }
    },
    {
      path: '/settings/backup-restore',
      name: 'BackupRestore',
      component: () => import('@/views/BackupRestoreView.vue'),
      meta: { title: '備份/還原 資料', icon: 'Setting' }
    },
    {
      path: '/settings/notifications',
      name: 'NotificationSettings',
      component: () => import('@/views/NotificationSettingsView.vue'),
      meta: { title: '警示通知設定', icon: 'Bell' }
    },
  ]
})

router.beforeEach((to) => {
  document.title = `${to.meta.title || '資產管理'} | 資產管理系統`
})

export default router
