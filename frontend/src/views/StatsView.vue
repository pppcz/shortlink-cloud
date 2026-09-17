<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { EChartsOption } from 'echarts'
import ChartPanel from '@/components/ChartPanel.vue'
import { fetchStats } from '@/api/stats'
import type { LinkStats } from '@/api/http'

const props = defineProps<{ shortCode: string }>()

const route = useRoute()
const router = useRouter()

const loading = ref(false)
const days = ref(7)
const stats = ref<LinkStats | null>(null)

const trend = computed(() => stats.value?.trend || [])

const lineOption = computed<EChartsOption>(() => ({
  tooltip: { trigger: 'axis' },
  legend: { data: ['PV', 'UV'], right: 0 },
  grid: { left: 45, right: 20, top: 40, bottom: 30 },
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
      areaStyle: { opacity: 0.15 },
      itemStyle: { color: '#409eff' },
      data: trend.value.map((item) => item.pv)
    },
    {
      name: 'UV',
      type: 'line',
      smooth: true,
      itemStyle: { color: '#67c23a' },
      data: trend.value.map((item) => item.uv)
    }
  ]
}))

const barOption = computed<EChartsOption>(() => ({
  tooltip: { trigger: 'axis' },
  grid: { left: 45, right: 20, top: 30, bottom: 30 },
  xAxis: { type: 'category', data: trend.value.map((item) => item.statDate) },
  yAxis: { type: 'value', minInterval: 1 },
  series: [
    {
      name: 'PV',
      type: 'bar',
      barMaxWidth: 32,
      itemStyle: { color: '#409eff', borderRadius: [3, 3, 0, 0] },
      data: trend.value.map((item) => item.pv)
    }
  ]
}))

async function load() {
  loading.value = true
  try {
    stats.value = await fetchStats(props.shortCode, days.value)
  } catch {
    // 错误提示已由 axios 拦截器统一处理（404 会提示「短链不存在」）
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page-container">
    <el-card shadow="never" class="card-gap">
      <div class="header">
        <div class="meta">
          <div class="code-row">
            <span class="code">{{ props.shortCode }}</span>
            <el-tag v-if="stats" :type="stats.status === 1 ? 'success' : 'info'" size="small">
              {{ stats.status === 1 ? '启用' : '禁用' }}
            </el-tag>
          </div>
          <el-link
            v-if="stats"
            type="primary"
            :href="stats.originalUrl"
            target="_blank"
            class="origin"
          >
            {{ stats.originalUrl }}
          </el-link>
          <div v-if="stats" class="time">
            创建于 {{ stats.createTime || '-' }} ·
            最近访问 {{ stats.lastAccess || '暂无' }} ·
            过期 {{ stats.expireTime || '永久' }}
          </div>
        </div>
        <div class="actions">
          <el-radio-group v-model="days" size="small" @change="load">
            <el-radio-button :value="7">近 7 天</el-radio-button>
            <el-radio-button :value="30">近 30 天</el-radio-button>
            <el-radio-button :value="90">近 90 天</el-radio-button>
          </el-radio-group>
          <el-button size="small" @click="router.push(`/links?code=${route.params.shortCode}`)">
            返回列表
          </el-button>
        </div>
      </div>
    </el-card>

    <el-row :gutter="16" class="card-gap">
      <el-col :xs="12" :sm="12" :md="6">
        <el-card shadow="hover">
          <div class="stat">
            <div class="stat-label">累计 PV</div>
            <div class="stat-value">{{ stats?.totalPv ?? 0 }}</div>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <el-card shadow="hover">
          <div class="stat">
            <div class="stat-label">累计 UV</div>
            <div class="stat-value">{{ stats?.totalUv ?? 0 }}</div>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <el-card shadow="hover">
          <div class="stat">
            <div class="stat-label">按天统计 PV 合计</div>
            <div class="stat-value">{{ stats?.statsPvSum ?? 0 }}</div>
            <div class="stat-hint">与累计 PV 交叉校验</div>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <el-card shadow="hover">
          <div class="stat">
            <div class="stat-label">按天统计 UV 合计</div>
            <div class="stat-value">{{ stats?.statsUvSum ?? 0 }}</div>
            <div class="stat-hint">与累计 UV 交叉校验</div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" class="card-gap">
      <template #header>PV / UV 趋势</template>
      <ChartPanel :option="lineOption" :loading="loading" height="320px" />
      <el-empty v-if="!loading && trend.length === 0" description="暂无访问数据" />
    </el-card>

    <el-card shadow="never" class="card-gap">
      <template #header>每日访问量</template>
      <ChartPanel :option="barOption" :loading="loading" height="280px" />
    </el-card>

    <el-card shadow="never">
      <template #header>明细数据</template>
      <el-table :data="trend" stripe border size="small">
        <el-table-column prop="statDate" label="日期" width="140" />
        <el-table-column prop="pv" label="PV" sortable />
        <el-table-column prop="uv" label="UV" sortable />
        <template #empty>
          <el-empty description="暂无数据" />
        </template>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

.code-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.code {
  font-size: 20px;
  font-weight: 600;
  font-family: 'SFMono-Regular', Consolas, monospace;
}

.origin {
  display: block;
  margin-top: 6px;
  word-break: break-all;
}

.time {
  margin-top: 8px;
  color: #909399;
  font-size: 12px;
}

.actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

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

.stat-hint {
  margin-top: 4px;
  color: #c0c4cc;
  font-size: 12px;
}
</style>
