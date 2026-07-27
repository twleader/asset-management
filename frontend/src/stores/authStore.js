import { defineStore } from 'pinia'
import { authApi } from '@/api'

/**
 * 登入態與多租戶（Requirement 28）。
 *
 * `me` 來自 GET /api/me：{ email, name, picture, role, status, effectiveUserId,
 * effectiveUserName, isImpersonating, switchableUsers[] }。狀態由後端即時查 DB，核准/停用立即反映。
 */
export const useAuthStore = defineStore('auth', {
  state: () => ({
    me: null,
    loaded: false
  }),
  getters: {
    isLoggedIn: (s) => !!(s.me && s.me.email),
    isAdmin: (s) => s.me?.role === 'ADMIN',
    // 「主要管理者」（ADMIN_EMAIL 本人，全庫唯一）。**與 isAdmin 語意不同，不可互換**：
    // role === 'ADMIN' 可以有多人，而 Google Drive 同步的 remote 全機只有一份、綁定特定 Google 帳號，
    // 若以 isAdmin 判斷，第二位 ADMIN 的財務報表就會被上傳到該帳號。Drive 相關 UI 一律用這一個
    // （Requirement 51 / Task 242）。前端只決定「顯不顯示」，真正的閘門在 business 端的 403。
    isConfiguredAdmin: (s) => !!s.me?.configuredAdmin,
    isActive: (s) => s.me?.status === 'ACTIVE',
    isPending: (s) => !!s.me && s.me.status !== 'ACTIVE',
    isImpersonating: (s) => !!s.me?.isImpersonating,
    switchableUsers: (s) => s.me?.switchableUsers || [],
    effectiveUserId: (s) => s.me?.effectiveUserId ?? null,
    effectiveUserName: (s) => s.me?.effectiveUserName || ''
  },
  actions: {
    /** 載入目前登入者；未登入（401）時 me=null，不拋錯。 */
    async fetchMe() {
      try {
        this.me = await authApi.me()
      } catch (e) {
        this.me = null
      } finally {
        this.loaded = true
      }
      return this.me
    },
    /** 整頁跳轉 Google 登入。 */
    login() {
      window.location.href = '/oauth2/authorization/google'
    },
    /** 登出後回到登入流程。 */
    async logout() {
      try { await authApi.logout() } catch (e) { /* ignore */ }
      this.me = null
      window.location.href = '/oauth2/authorization/google'
    },
    /** 管理者代看切換；userId 為空或等於自己 → 回到看自己。 */
    async impersonate(userId) {
      await authApi.impersonate(userId)
      await this.fetchMe()
    }
  }
})
