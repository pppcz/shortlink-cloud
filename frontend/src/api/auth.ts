import http, { type ApiResult, type UserVO } from './http'

/** 登录响应。 */
export interface LoginResult {
  token: string
  tokenType: string
  expiresIn: number
  user: UserVO
}

/** 登录换取 JWT。 */
export async function login(username: string, password: string): Promise<LoginResult> {
  const { data } = await http.post<ApiResult<LoginResult>>('/auth/login', { username, password })
  return data.data
}

/** 查询当前登录用户，用于刷新页面后校验 token 是否仍然有效。 */
export async function fetchCurrentUser(): Promise<UserVO> {
  const { data } = await http.get<ApiResult<UserVO>>('/auth/me')
  return data.data
}
