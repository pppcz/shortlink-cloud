import http, { type ApiResult, type LinkStats, type TrendPoint } from './http'

/** 查询单个短码的统计详情。 */
export async function fetchStats(shortCode: string, days = 7): Promise<LinkStats> {
  const { data } = await http.get<ApiResult<LinkStats>>(`/stats/${encodeURIComponent(shortCode)}`, {
    params: { days }
  })
  return data.data
}

/** 查询趋势：不传 shortCode 时返回全部短链的合计趋势。 */
export async function fetchTrend(shortCode: string | undefined, days = 7): Promise<TrendPoint[]> {
  const { data } = await http.get<ApiResult<TrendPoint[]>>('/stats/trend', {
    params: { shortCode, days }
  })
  return data.data || []
}
