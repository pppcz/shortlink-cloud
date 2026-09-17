import { defineStore } from 'pinia'
import { ref } from 'vue'
import { fetchCurrentUser, login as loginApi, type LoginResult } from '@/api/auth'
import { clearToken, getToken, setToken, type UserVO } from '@/api/http'

/**
 * 登录状态。
 *
 * <p>token 存 localStorage（刷新页面不丢），用户信息只在内存中，
 * 刷新后通过 /api/auth/me 重新拉取——这样用户信息被后台改动也能及时反映。
 */
export const useAuthStore = defineStore('auth', () => {
  const token = ref<string>(getToken())
  const user = ref<UserVO | null>(null)
  const loading = ref(false)

  /** 登录。 */
  async function login(username: string, password: string): Promise<LoginResult> {
    loading.value = true
    try {
      const result = await loginApi(username, password)
      setToken(result.token)
      token.value = result.token
      user.value = result.user
      return result
    } finally {
      loading.value = false
    }
  }

  /** 拉取当前用户；失败说明 token 已失效。 */
  async function loadUser(): Promise<boolean> {
    if (!token.value) {
      return false
    }
    try {
      user.value = await fetchCurrentUser()
      return true
    } catch {
      logout()
      return false
    }
  }

  /** 退出登录。 */
  function logout(): void {
    clearToken()
    token.value = ''
    user.value = null
  }

  return { token, user, loading, login, loadUser, logout }
})
