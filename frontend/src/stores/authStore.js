import { defineStore } from 'pinia'
import { authApi, appFeatureSettingsApi } from '@/api'

/**
 * 登入態與多租戶（Requirement 28）。
 *
 * `me` 來自 GET /api/me：{ email, name, picture, role, status, effectiveUserId,
 * effectiveUserName, isImpersonating, switchableUsers[] }。狀態由後端即時查 DB，核准/停用立即反映。
 */
export const useAuthStore = defineStore('auth', {
  state: () => ({
    me: null,
    loaded: false,
    // Requirement 134 / Task 407：一般使用者角色被關閉的功能 code（= 路由 path）清單，供選單過濾與路由 guard 使用。
    // 管理者固定為 []（不受限制）；取得失敗時 fail-closed 為 []（本設定只影響 UI 可見性，非安全邊界，
    // 失敗時不應讓一般使用者整個選單消失）。
    disabledFeatureCodes: []
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
    effectiveUserName: (s) => s.me?.effectiveUserName || '',
    // Requirement 134 / Task 407：管理者永遠不受限制；一般使用者依 disabledFeatureCodes 判斷。
    isFeatureEnabled: (s) => (path) => s.isAdmin || !s.disabledFeatureCodes.includes(path)
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
      await this.loadDisabledFeatures()
      return this.me
    },
    /**
     * Requirement 134 / Task 407：管理者不受限（固定 []，不呼叫 API）；一般使用者取得停用功能清單。
     * fail-closed：呼叫失敗時維持 []（=全部顯示），本設定只影響 UI 可見性、非安全邊界。
     */
    async loadDisabledFeatures() {
      if (!this.isLoggedIn || this.isAdmin) {
        this.disabledFeatureCodes = []
        return
      }
      try {
        const features = await appFeatureSettingsApi.getAll()
        this.disabledFeatureCodes = (features || [])
          .filter(f => f.enabledForUser === false)
          .map(f => f.code)
      } catch (e) {
        this.disabledFeatureCodes = []
      }
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
