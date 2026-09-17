import axios, { type AxiosInstance, type AxiosResponse } from 'axios'
import { ElMessage } from 'element-plus'

/** 后端统一响应结构。 */
export interface ApiResult<T = unknown> {
  code: number
  message: string
  data: T
  traceId?: string
  timestamp?: number
}

/** 分页结果（MyBatis-Plus IPage 的序列化形态）。 */
export interface PageResult<T> {
  records: T[]
  total: number
  size: number
  current: number
  pages: number
}

/** 短链信息。 */
export interface ShortLinkVO {
  id: number
  shortCode: string
  shortUrl: string
  originalUrl: string
  title?: string
  status: number
  expireTime?: string
  pv: number
  uv: number
  lastAccess?: string
  createTime?: string
}

/** 趋势数据点。 */
export interface TrendPoint {
  statDate: string
  pv: number
  uv: number
}

/** 统计详情。 */
export interface LinkStats {
  shortCode: string
  originalUrl: string
  title?: string
  status: number
  createTime?: string
  expireTime?: string
  totalPv: number
  totalUv: number
  statsPvSum: number
  statsUvSum: number
  lastAccess?: string
  startDate: string
  endDate: string
  trend: TrendPoint[]
}

/** 当前用户。 */
export interface UserVO {
  id: number
  username: string
  nickname?: string
  email?: string
  role: string
  status: number
  lastLogin?: string
}

const TOKEN_KEY = 'shortlink_token'

export function getToken(): string {
  return localStorage.getItem(TOKEN_KEY) || ''
}

export function setToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token)
}

export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY)
}

const http: AxiosInstance = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 15000,
  headers: { 'Content-Type': 'application/json' }
})

// 请求拦截：附带 JWT
http.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// 响应拦截：统一解包 Result，并把业务错误转成 reject
http.interceptors.response.use(
  (response: AxiosResponse<ApiResult>) => {
    const body = response.data
    // 跳转类接口不返回 Result 结构，直接放行
    if (body == null || typeof body.code !== 'number') {
      return response
    }
    if (body.code !== 0) {
      ElMessage.error(body.message || '请求失败')
      return Promise.reject(new Error(body.message || '请求失败'))
    }
    return response
  },
  (error) => {
    const status = error?.response?.status
    if (status === 401) {
      clearToken()
      ElMessage.error('登录已过期，请重新登录')
      // 不在这里直接跳转，交给路由守卫处理，避免循环依赖
      if (!window.location.pathname.startsWith('/login')) {
        window.location.href = '/login'
      }
    } else if (status === 429) {
      ElMessage.warning('请求过于频繁，请稍后再试')
    } else {
      const message = error?.response?.data?.message || error.message || '网络异常'
      ElMessage.error(message)
    }
    return Promise.reject(error)
  }
)

export default http
