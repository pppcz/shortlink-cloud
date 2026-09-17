import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { getToken } from '@/api/http'

/**
 * 路由表。
 *
 * <p>布局策略：登录页独立全屏，其余页面挂在 AdminLayout 下（侧边栏 + 顶栏）。
 */
const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/LoginView.vue'),
    meta: { public: true, title: '登录' }
  },
  {
    path: '/',
    component: () => import('@/layout/AdminLayout.vue'),
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('@/views/DashboardView.vue'),
        meta: { title: '概览' }
      },
      {
        path: 'links',
        name: 'Links',
        component: () => import('@/views/LinkListView.vue'),
        meta: { title: '短链管理' }
      },
      {
        path: 'stats/:shortCode',
        name: 'Stats',
        component: () => import('@/views/StatsView.vue'),
        props: true,
        meta: { title: '访问统计' }
      }
    ]
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'NotFound',
    component: () => import('@/views/NotFoundView.vue'),
    meta: { public: true, title: '页面不存在' }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

/**
 * 全局前置守卫。
 *
 * <p>只做「有没有 token」的粗粒度判断（不做解析，token 是否有效由后端裁决）；
 * 真正的失效处理在 axios 拦截器里（收到 401 就清 token 并跳登录）。
 */
router.beforeEach((to) => {
  document.title = to.meta.title ? `${to.meta.title as string} · shortlink-cloud` : 'shortlink-cloud'

  const isPublic = Boolean(to.meta.public)
  if (isPublic) {
    // 已登录还去登录页，直接回首页
    if (to.name === 'Login' && getToken()) {
      return { path: '/dashboard' }
    }
    return true
  }
  if (!getToken()) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  return true
})

export default router
