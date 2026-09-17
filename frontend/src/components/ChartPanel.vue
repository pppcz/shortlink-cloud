<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import * as echarts from 'echarts'
import type { EChartsOption } from 'echarts'

const props = defineProps<{
  option: EChartsOption
  height?: string
  loading?: boolean
}>()

const container = ref<HTMLDivElement | null>(null)
// shallowRef：ECharts 实例是庞大的非响应式对象，用 ref 会带来无谓的深度代理开销
const chart = shallowRef<echarts.ECharts | null>(null)

function render() {
  if (!container.value) {
    return
  }
  if (!chart.value) {
    chart.value = echarts.init(container.value)
  }
  chart.value.setOption(props.option, true)
}

function resize() {
  chart.value?.resize()
}

onMounted(() => {
  render()
  window.addEventListener('resize', resize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  chart.value?.dispose()
  chart.value = null
})

// 数据变化时重绘（option 由父组件以计算属性提供）
watch(() => props.option, render, { deep: true })

// loading 状态用 ECharts 内置的遮罩，避免额外的 DOM 结构
watch(
  () => props.loading,
  (value) => {
    if (value) {
      chart.value?.showLoading('default', { text: '加载中', maskColor: 'rgba(255,255,255,0.6)' })
    } else {
      chart.value?.hideLoading()
    }
  }
)
</script>

<template>
  <div ref="container" :style="{ width: '100%', height: height || '320px' }"></div>
</template>
