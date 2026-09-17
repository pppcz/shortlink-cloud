import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

/**
 * 路由骨架。Phase 4 在此挂载完整管理后台页面：
 *   /login      登录
 *   /dashboard  概览
 *   /links      短链管理
 *   /stats/:code 统计详情
 */
const routes: RouteRecordRaw[] = [
  {
    path: '/',
    redirect: '/dashboard'
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'NotFound',
    component: () => import('@/views/NotFoundView.vue')
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router
