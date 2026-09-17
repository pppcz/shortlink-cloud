import http, { type ApiResult, type PageResult, type ShortLinkVO } from './http'

/** 创建短链请求。 */
export interface CreateLinkPayload {
  originalUrl: string
  title?: string
  expireDays?: number
  customCode?: string
}

/** 创建短链响应。 */
export interface CreateLinkResult {
  id: number
  shortCode: string
  shortUrl: string
  originalUrl: string
  title?: string
  expireTime?: string
  createTime?: string
}

/** 分页查询参数。 */
export interface LinkPageParams {
  current?: number
  size?: number
  shortCode?: string
  originalUrl?: string
  title?: string
  status?: number
}

/** 创建短链。 */
export async function createLink(payload: CreateLinkPayload): Promise<CreateLinkResult> {
  const { data } = await http.post<ApiResult<CreateLinkResult>>('/link/create', payload)
  return data.data
}

/** 分页查询短链。 */
export async function pageLinks(params: LinkPageParams): Promise<PageResult<ShortLinkVO>> {
  const { data } = await http.get<ApiResult<PageResult<ShortLinkVO>>>('/link/page', { params })
  return data.data
}

/** 禁用短链。 */
export async function disableLink(id: number): Promise<void> {
  await http.put<ApiResult<void>>(`/link/disable/${id}`)
}
