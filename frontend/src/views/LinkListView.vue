<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { createLink, disableLink, pageLinks, type CreateLinkPayload } from '@/api/link'
import type { ShortLinkVO } from '@/api/http'

const router = useRouter()

const loading = ref(false)
const rows = ref<ShortLinkVO[]>([])
const total = ref(0)

const query = reactive({
  current: 1,
  size: 10,
  shortCode: '',
  originalUrl: '',
  status: undefined as number | undefined
})

// ---------------- 列表 ----------------

async function loadList() {
  loading.value = true
  try {
    const page = await pageLinks({
      current: query.current,
      size: query.size,
      shortCode: query.shortCode || undefined,
      originalUrl: query.originalUrl || undefined,
      status: query.status
    })
    rows.value = page.records
    total.value = page.total
  } catch {
    // 错误提示已由 axios 拦截器统一处理
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  query.current = 1
  loadList()
}

function handleReset() {
  query.shortCode = ''
  query.originalUrl = ''
  query.status = undefined
  query.current = 1
  loadList()
}

async function handleDisable(row: ShortLinkVO) {
  await ElMessageBox.confirm(
    `禁用后该短链将立即返回 410，确定禁用「${row.shortCode}」吗？`,
    '确认禁用',
    { confirmButtonText: '禁用', cancelButtonText: '取消', type: 'warning' }
  )
  await disableLink(row.id)
  ElMessage.success('已禁用')
  loadList()
}

function goStats(row: ShortLinkVO) {
  router.push(`/stats/${row.shortCode}`)
}

async function copyShortUrl(row: ShortLinkVO) {
  const text = row.shortUrl
  try {
    // navigator.clipboard 在非 HTTPS 或旧浏览器下不可用，需要降级
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text)
    } else {
      const input = document.createElement('input')
      input.value = text
      document.body.appendChild(input)
      input.select()
      document.execCommand('copy')
      document.body.removeChild(input)
    }
    ElMessage.success('短链已复制')
  } catch {
    ElMessage.warning('复制失败，请手动复制：' + text)
  }
}

// ---------------- 创建 ----------------

const dialogVisible = ref(false)
const submitting = ref(false)
const formRef = ref<FormInstance>()

const createForm = reactive<CreateLinkPayload>({
  originalUrl: '',
  title: '',
  expireDays: 0,
  customCode: ''
})

const createRules: FormRules = {
  originalUrl: [
    { required: true, message: '请输入原始链接', trigger: 'blur' },
    { max: 2048, message: '链接长度不能超过 2048', trigger: 'blur' }
  ],
  customCode: [
    {
      pattern: /^[0-9a-zA-Z]{4,16}$/,
      message: '只能包含字母和数字，长度 4-16',
      trigger: 'blur'
    }
  ]
}

function openCreateDialog() {
  createForm.originalUrl = ''
  createForm.title = ''
  createForm.expireDays = 0
  createForm.customCode = ''
  dialogVisible.value = true
}

async function submitCreate() {
  if (!formRef.value) {
    return
  }
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) {
    return
  }
  submitting.value = true
  try {
    const payload: CreateLinkPayload = {
      originalUrl: createForm.originalUrl,
      title: createForm.title || undefined,
      expireDays: createForm.expireDays || 0,
      customCode: createForm.customCode || undefined
    }
    const result = await createLink(payload)
    ElMessage.success(`创建成功：${result.shortUrl}`)
    dialogVisible.value = false
    query.current = 1
    loadList()
  } catch {
    // 错误提示已由 axios 拦截器统一处理
  } finally {
    submitting.value = false
  }
}

onMounted(loadList)
</script>

<template>
  <div class="page-container">
    <el-card shadow="never" class="card-gap">
      <el-form :inline="true" @submit.prevent>
        <el-form-item label="短码">
          <el-input v-model="query.shortCode" placeholder="精确匹配" clearable style="width: 150px" />
        </el-form-item>
        <el-form-item label="原始链接">
          <el-input
            v-model="query.originalUrl"
            placeholder="模糊匹配"
            clearable
            style="width: 200px"
          />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" placeholder="全部" clearable style="width: 120px">
            <el-option label="启用" :value="1" />
            <el-option label="禁用" :value="0" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleSearch">查询</el-button>
          <el-button @click="handleReset">重置</el-button>
        </el-form-item>
        <el-form-item>
          <el-button type="success" @click="openCreateDialog">
            <el-icon><Plus /></el-icon>
            创建短链
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="rows" stripe border>
        <el-table-column prop="shortCode" label="短码" width="130" fixed />
        <el-table-column label="短链" width="240">
          <template #default="{ row }">
            <el-link type="primary" :href="row.shortUrl" target="_blank">{{ row.shortUrl }}</el-link>
          </template>
        </el-table-column>
        <el-table-column prop="originalUrl" label="原始链接" min-width="240" show-overflow-tooltip />
        <el-table-column prop="title" label="标题" width="150" show-overflow-tooltip />
        <el-table-column prop="pv" label="PV" width="90" sortable />
        <el-table-column prop="uv" label="UV" width="90" sortable />
        <el-table-column prop="lastAccess" label="最近访问" width="170" />
        <el-table-column prop="createTime" label="创建时间" width="170" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
              {{ row.status === 1 ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="goStats(row)">统计</el-button>
            <el-button type="primary" link size="small" @click="copyShortUrl(row)">复制</el-button>
            <el-button
              type="danger"
              link
              size="small"
              :disabled="row.status === 0"
              @click="handleDisable(row)"
            >
              禁用
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无短链，点击右上角「创建短链」开始" />
        </template>
      </el-table>

      <el-pagination
        class="pagination"
        v-model:current-page="query.current"
        v-model:page-size="query.size"
        :total="total"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next, jumper"
        @current-change="loadList"
        @size-change="handleSearch"
      />
    </el-card>

    <el-dialog v-model="dialogVisible" title="创建短链" width="560px">
      <el-form ref="formRef" :model="createForm" :rules="createRules" label-width="100px">
        <el-form-item label="原始链接" prop="originalUrl">
          <el-input
            v-model="createForm.originalUrl"
            type="textarea"
            :rows="3"
            placeholder="https://example.com/very/long/path（可省略 https://）"
          />
        </el-form-item>
        <el-form-item label="标题" prop="title">
          <el-input v-model="createForm.title" placeholder="可选，便于后台识别" />
        </el-form-item>
        <el-form-item label="有效期" prop="expireDays">
          <el-input-number v-model="createForm.expireDays" :min="0" :max="3650" />
          <span class="tip">天（0 表示永久有效）</span>
        </el-form-item>
        <el-form-item label="自定义短码" prop="customCode">
          <el-input v-model="createForm.customCode" placeholder="可选，4-16 位字母数字" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.pagination {
  margin-top: 16px;
  justify-content: flex-end;
}

.tip {
  margin-left: 8px;
  color: #909399;
  font-size: 12px;
}
</style>
