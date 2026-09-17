<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import type { EChartsOption } from 'echarts'
import ChartPanel from '@/components/ChartPanel.vue'
import { pageLinks } from '@/api/link'
import { fetchTrend } from '@/api/stats'
import type { ShortLinkVO, TrendPoint } from '@/api/http'

const router = useRouter()

const loading = ref(false)
const trendLoading = ref(false)
const days = ref(7)

const trend = ref<TrendPoint[]>([])
const totalLinks = ref(0)
const totalPv = ref(0)
const totalUv = ref(0)
const disabledLinks = ref(0)
const recentLinks = ref<ShortLinkVO[]>([])

const trendOption = computed<EChartsOption>(() => ({
  tooltip: { trigger: 'axis' },
  legend: { data: ['PV', 'UV'], right: 0 },
  grid: { left: 40, right: 20, top: 40, bottom: 30 },
  xAxis: {
    type: 'category',
    boundaryGap: false,
    data: trend.value.map((item) => item.statDate)
  },
  yAxis: { type: 'value', minInterval: 1 },
  series: [
    {
      name: 'PV',
      type: 'line',
      smooth: true,
      showSymbol: false,
      areaStyle: { opacity: 0.15 },
      itemStyle: { color: '#409eff' },
      data: trend.value.map((item) => item.pv)
    },
    {
      name: 'UV',
      type: 'line',
      smooth: true,
      showSymbol: false,
      itemStyle: { color: '#67c23a' },
      data: trend.value.map((item) => item.uv)
    }
  ]
}))

async function loadOverview() {
  loading.value = true
  try {
    const [all, disabled, recent] = await Promise.all([
      pageLinks({ current: 1, size: 100 }),
      pageLinks({ current: 1, size: 1, status: 0 }),
      pageLinks({ current: 1, size: 5 })
    ])
    totalLinks.value = all.total
    totalPv.value = all.records.reduce((sum, item) => sum + (item.pv || 0), 0)
    totalUv.value = all.records.reduce((sum, item) => sum + (item.uv || 0), 0)
    disabledLinks.value = disabled.total
    recentLinks.value = recent.records
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

async function loadTrend() {
  trendLoading.value = true
  try {
    trend.value = await fetchTrend(undefined, days.value)
  } catch {
    // 拦截器已提示
  } finally {
    trendLoading.value = false
  }
}

function goStats(shortCode: string) {
  router.push(`/stats/${shortCode}`)
}

onMounted(() => {
  loadOverview()
  loadTrend()
})
</script>

<template>
  <div class="page-container">
    <el-row :gutter="16" class="card-gap">
      <el-col :xs="12" :sm="12" :md="6">
        <el-card shadow="hover">
          <div class="stat">
            <div class="stat-label">短链总数</div>
            <div class="stat-value">{{ totalLinks }}</div>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <el-card shadow="hover">
          <div class="stat">
            <div class="stat-label">累计 PV</div>
            <div class="stat-value">{{ totalPv }}</div>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <el-card shadow="hover">
          <div class="stat">
            <div class="stat-label">累计 UV</div>
            <div class="stat-value">{{ totalUv }}</div>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <el-card shadow="hover">
          <div class="stat">
            <div class="stat-label">已禁用</div>
            <div class="stat-value warning">{{ disabledLinks }}</div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" class="card-gap">
      <template #header>
        <div class="card-header">
          <span>访问趋势（全部短链合计）</span>
          <el-radio-group v-model="days" size="small" @change="loadTrend">
            <el-radio-button :value="7">近 7 天</el-radio-button>
            <el-radio-button :value="30">近 30 天</el-radio-button>
            <el-radio-button :value="90">近 90 天</el-radio-button>
          </el-radio-group>
        </div>
      </template>
      <ChartPanel :option="trendOption" :loading="trendLoading" height="320px" />
      <el-empty
        v-if="!trendLoading && trend.length === 0"
        description="暂无访问数据（统计由 MQ 消费者异步写入，跳转后约 2 秒可见）"
      />
    </el-card>

    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>最近创建</span>
          <el-button type="primary" link @click="router.push('/links')">查看全部</el-button>
        </div>
      </template>
      <el-table v-loading="loading" :data="recentLinks" stripe>
        <el-table-column prop="shortCode" label="短码" width="140">
          <template #default="{ row }">
            <el-link type="primary" @click="goStats(row.shortCode)">{{ row.shortCode }}</el-link>
          </template>
        </el-table-column>
        <el-table-column prop="originalUrl" label="原始链接" show-overflow-tooltip />
        <el-table-column prop="title" label="标题" width="160" show-overflow-tooltip />
        <el-table-column prop="pv" label="PV" width="90" />
        <el-table-column prop="uv" label="UV" width="90" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
              {{ row.status === 1 ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.stat-label {
  color: #909399;
  font-size: 13px;
}

.stat-value {
  margin-top: 6px;
  font-size: 26px;
  font-weight: 600;
  color: #303133;
}

.stat-value.warning {
  color: #e6a23c;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
</style>
