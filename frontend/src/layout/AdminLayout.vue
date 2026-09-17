<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/store/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const collapsed = ref(false)

const activeMenu = computed(() => {
  // 统计页也归属短链管理，保持菜单高亮不跳变
  if (route.path.startsWith('/stats/')) {
    return '/links'
  }
  return route.path
})

const displayName = computed(() => auth.user?.nickname || auth.user?.username || '未登录')

onMounted(async () => {
  if (!auth.user) {
    await auth.loadUser()
  }
})

async function handleLogout() {
  await ElMessageBox.confirm('确定要退出登录吗？', '提示', {
    confirmButtonText: '退出',
    cancelButtonText: '取消',
    type: 'warning'
  })
  auth.logout()
  router.push('/login')
}
</script>

<template>
  <el-container class="admin-layout">
    <el-aside :width="collapsed ? '64px' : '210px'" class="sidebar">
      <div class="logo">
        <el-icon :size="20"><Link /></el-icon>
        <span v-show="!collapsed" class="logo-text">shortlink-cloud</span>
      </div>
      <el-menu
        :default-active="activeMenu"
        :collapse="collapsed"
        :collapse-transition="false"
        router
        class="sidebar-menu"
      >
        <el-menu-item index="/dashboard">
          <el-icon><DataLine /></el-icon>
          <template #title>概览</template>
        </el-menu-item>
        <el-menu-item index="/links">
          <el-icon><Management /></el-icon>
          <template #title>短链管理</template>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="header">
        <div class="header-left">
          <el-icon class="collapse-btn" :size="18" @click="collapsed = !collapsed">
            <Fold v-if="!collapsed" />
            <Expand v-else />
          </el-icon>
          <span class="page-title">{{ route.meta.title || '' }}</span>
        </div>
        <div class="header-right">
          <el-tag v-if="auth.user?.role" size="small" type="info">{{ auth.user.role }}</el-tag>
          <el-dropdown @command="handleLogout">
            <span class="user-name">
              <el-icon><User /></el-icon>
              {{ displayName }}
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.admin-layout {
  height: 100vh;
}

.sidebar {
  background-color: #001529;
  transition: width 0.2s;
  overflow: hidden;
}

.logo {
  display: flex;
  align-items: center;
  gap: 8px;
  height: var(--sl-header-height);
  padding: 0 18px;
  color: #fff;
  font-weight: 600;
  white-space: nowrap;
}

.logo-text {
  font-size: 15px;
}

.sidebar-menu {
  border-right: none;
  background-color: #001529;
}

.sidebar-menu :deep(.el-menu-item) {
  color: rgba(255, 255, 255, 0.72);
}

.sidebar-menu :deep(.el-menu-item.is-active) {
  color: #fff;
  background-color: #409eff;
}

.sidebar-menu :deep(.el-menu-item:hover) {
  background-color: #12263f;
}

.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: var(--sl-header-height);
  background: #fff;
  border-bottom: 1px solid #ebeef5;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 14px;
}

.collapse-btn {
  cursor: pointer;
  color: #606266;
}

.page-title {
  font-size: 15px;
  font-weight: 600;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 14px;
}

.user-name {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  color: #303133;
  outline: none;
}

.main {
  padding: 16px;
  background-color: #f5f7fa;
  overflow-y: auto;
}
</style>
