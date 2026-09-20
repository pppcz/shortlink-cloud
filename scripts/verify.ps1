# =====================================================================
# shortlink-cloud 本地验证脚本（Windows PowerShell）
#
# 作用：在你有网络的机器上，按顺序执行全部验收步骤，并在每一步失败时
#       立刻停下并告诉你原因。生成这份代码的沙箱无法编译/运行本项目，
#       所以这个脚本是「把未验证的代码变成已验证」的最短路径。
#
# 用法：
#   pwsh -File scripts/verify.ps1              # 全部步骤
#   pwsh -File scripts/verify.ps1 -SkipDocker  # 跳过需要 Docker 的步骤
#
# 前置要求：
#   JDK 17、Maven 3.9+、Node 18+（Docker 步骤需要 Docker Desktop）
# =====================================================================
[CmdletBinding()]
param(
    [switch]$SkipDocker,
    [switch]$SkipFrontend
)

$ErrorActionPreference = 'Stop'
$script:StepNo = 0
$script:Results = @()

function Write-Step {
    param([string]$Title)
    $script:StepNo++
    Write-Host ''
    Write-Host ('=' * 68) -ForegroundColor DarkGray
    Write-Host ("步骤 {0}: {1}" -f $script:StepNo, $Title) -ForegroundColor Cyan
    Write-Host ('=' * 68) -ForegroundColor DarkGray
}

function Write-Pass {
    param([string]$Title, [string]$Note = '')
    $script:Results += [pscustomobject]@{ Step = $Title; Status = 'PASS'; Note = $Note }
    Write-Host ("  [通过] {0} {1}" -f $Title, $Note) -ForegroundColor Green
}

function Write-Fail {
    param([string]$Title, [string]$Reason)
    $script:Results += [pscustomobject]@{ Step = $Title; Status = 'FAIL'; Note = $Reason }
    Write-Host ("  [失败] {0}" -f $Title) -ForegroundColor Red
    Write-Host ("         原因: {0}" -f $Reason) -ForegroundColor Red
}

function Write-Skip {
    param([string]$Title, [string]$Reason)
    $script:Results += [pscustomobject]@{ Step = $Title; Status = 'SKIP'; Note = $Reason }
    Write-Host ("  [跳过] {0} —— {1}" -f $Title, $Reason) -ForegroundColor Yellow
}

function Test-Command {
    param([string]$Name)
    $cmd = Get-Command $Name -ErrorAction SilentlyContinue
    return [bool]$cmd
}

# 让 native 命令的非零退出码变成 PowerShell 异常，便于统一捕获
function Invoke-Checked {
    param(
        [string]$Title,
        [string]$Exe,
        [string[]]$Arguments,
        [string]$WorkDir = '.'
    )
    Push-Location $WorkDir
    try {
        & $Exe @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw ("{0} 退出码 {1}" -f $Exe, $LASTEXITCODE)
        }
        Write-Pass $Title
        return $true
    } catch {
        Write-Fail $Title $_.Exception.Message
        return $false
    } finally {
        Pop-Location
    }
}

Write-Host ''
Write-Host 'shortlink-cloud 本地验证' -ForegroundColor White
Write-Host '说明：每步失败会立即停止，避免在错误的基础上继续验证。' -ForegroundColor DarkGray

$root = Split-Path -Parent $PSScriptRoot

# ---------------------------------------------------------------------
# 步骤 0：工具链检查
# ---------------------------------------------------------------------
Write-Step '检查工具链'

$missing = @()
foreach ($tool in @('java', 'mvn', 'node')) {
    if (Test-Command $tool) {
        Write-Host ("  {0,-6} 已安装" -f $tool) -ForegroundColor Gray
    } else {
        Write-Host ("  {0,-6} 缺失" -f $tool) -ForegroundColor Red
        $missing += $tool
    }
}
$hasDocker = Test-Command 'docker'
Write-Host ("  {0,-6} {1}" -f 'docker', $(if ($hasDocker) { '已安装' } else { '缺失（将跳过容器相关步骤）' })) -ForegroundColor Gray

if ($missing.Count -gt 0) {
    Write-Fail '工具链检查' ("缺少: " + ($missing -join ', '))
    Write-Host ''
    Write-Host '请先安装缺失的工具后重新运行本脚本。' -ForegroundColor Red
    exit 1
}
Write-Pass '工具链检查'

# ---------------------------------------------------------------------
# 步骤 1：后端编译 + 单元测试（不需要中间件）
# ---------------------------------------------------------------------
Write-Step '后端编译与单元测试（需要联网下载 Maven 依赖，首次约 3-5 分钟）'
$ok = Invoke-Checked -Title 'mvn test' -Exe 'mvn' -Arguments @('-B', '-ntp', 'test') -WorkDir (Join-Path $root 'backend')
if (-not $ok) {
    Write-Host ''
    Write-Host '编译或单元测试失败。请看上面的报错定位问题。' -ForegroundColor Red
    Write-Host '常见原因：' -ForegroundColor Yellow
    Write-Host '  · 依赖下载失败 → 检查网络/镜像配置'
    Write-Host '  · 编译错误 → 代码问题，需要修复'
    Write-Host '  · 断言失败 → 实现与测试预期不一致'
    exit 1
}

# ---------------------------------------------------------------------
# 步骤 2：后端打包
# ---------------------------------------------------------------------
Write-Step '后端打包（确认能产出可运行 jar）'
$ok = Invoke-Checked -Title 'mvn package' -Exe 'mvn' -Arguments @('-B', '-ntp', '-DskipTests', 'package') -WorkDir (Join-Path $root 'backend')
if (-not $ok) { exit 1 }

$jar = Join-Path $root 'backend\target\shortlink-cloud-backend.jar'
if (Test-Path $jar) {
    $sizeMb = [math]::Round((Get-Item $jar).Length / 1MB, 1)
    Write-Pass 'jar 产物存在' ("{0} MB" -f $sizeMb)
} else {
    Write-Fail 'jar 产物存在' "未找到 $jar"
    exit 1
}

# ---------------------------------------------------------------------
# 步骤 3：前端类型检查与构建
# ---------------------------------------------------------------------
if ($SkipFrontend) {
    Write-Step '前端类型检查与构建'
    Write-Skip '前端构建' '按参数要求跳过'
} else {
    Write-Step '前端依赖安装（首次约 1-2 分钟）'
    $ok = Invoke-Checked -Title 'npm install' -Exe 'npm.cmd' -Arguments @('install') -WorkDir (Join-Path $root 'frontend')
    if (-not $ok) { exit 1 }

    Write-Step '前端 TypeScript 类型检查（最可能暴露问题的一步）'
    $ok = Invoke-Checked -Title 'vue-tsc 类型检查' -Exe 'npm.cmd' -Arguments @('run', 'type-check') -WorkDir (Join-Path $root 'frontend')
    if (-not $ok) {
        Write-Host ''
        Write-Host '类型检查失败。这是本项目风险最高的一步——' -ForegroundColor Red
        Write-Host '前端代码从未经过类型检查，报错属于预期内，需要逐个修复。' -ForegroundColor Yellow
        exit 1
    }

    Write-Step '前端生产构建'
    $ok = Invoke-Checked -Title 'npm run build' -Exe 'npm.cmd' -Arguments @('run', 'build') -WorkDir (Join-Path $root 'frontend')
    if (-not $ok) { exit 1 }
}

# ---------------------------------------------------------------------
# 步骤 4：Docker 集成测试（可选）
# ---------------------------------------------------------------------
if ($SkipDocker -or -not $hasDocker) {
    Write-Step '集成测试（Testcontainers）'
    Write-Skip '集成测试' $(if ($SkipDocker) { '按参数要求跳过' } else { 'Docker 不可用' })
} else {
    Write-Step '集成测试（启动真实 MySQL/Redis/RabbitMQ，首次需拉镜像，约 2-3 分钟）'
    $ok = Invoke-Checked -Title 'mvn -Pintegration test' -Exe 'mvn' -Arguments @('-B', '-ntp', '-Pintegration', 'test') -WorkDir (Join-Path $root 'backend')
    if (-not $ok) {
        Write-Host ''
        Write-Host '集成测试失败。这一步验证的是真实数据库上的 SQL 与迁移，' -ForegroundColor Red
        Write-Host '失败通常意味着 SQL 语法、字段映射或 Flyway 脚本有问题。' -ForegroundColor Yellow
        exit 1
    }
}

# ---------------------------------------------------------------------
# 步骤 5：端到端冒烟（最多尝试一次，不重试）
# ---------------------------------------------------------------------
if ($SkipDocker -or -not $hasDocker) {
    Write-Step '端到端冒烟测试'
    Write-Skip '冒烟测试' 'Docker 不可用'
} else {
    Write-Step '启动全部服务并做端到端冒烟'
    Push-Location $root
    try {
        if (-not (Test-Path '.env')) {
            Copy-Item '.env.example' '.env'
            Write-Host '  已从 .env.example 创建 .env' -ForegroundColor Gray
        }
        docker compose up -d --build
        if ($LASTEXITCODE -ne 0) { throw 'docker compose up 失败' }
        Write-Pass 'docker compose up -d --build'

        Write-Host '  等待后端健康检查通过（最多 120 秒）...' -ForegroundColor Gray
        $healthy = $false
        for ($i = 0; $i -lt 24; $i++) {
            Start-Sleep -Seconds 5
            try {
                $resp = Invoke-WebRequest -Uri 'http://localhost:8080/actuator/health' `
                    -UseBasicParsing -TimeoutSec 5
                if ($resp.StatusCode -eq 200) { $healthy = $true; break }
            } catch {
                # 还没起来，继续等
            }
        }
        if ($healthy) {
            Write-Pass '后端健康检查'
        } else {
            Write-Fail '后端健康检查' '120 秒内未就绪'
            Write-Host '  查看日志: docker compose logs backend' -ForegroundColor Yellow
            exit 1
        }

        # 创建短链
        $body = '{"originalUrl":"https://example.com/verify","title":"verify"}'
        $created = Invoke-RestMethod -Uri 'http://localhost:8080/api/link/create' `
            -Method Post -ContentType 'application/json' -Body $body
        if ($created.code -ne 0) { throw ("创建短链返回业务码 " + $created.code) }
        $code = $created.data.shortCode
        Write-Pass '创建短链' ("shortCode = " + $code)

        # 跳转必须返回 302 且带 no-store
        $redirect = Invoke-WebRequest -Uri ("http://localhost:8080/" + $code) `
            -MaximumRedirection 0 -SkipHttpErrorCheck -UseBasicParsing
        if ($redirect.StatusCode -eq 302) {
            Write-Pass '跳转返回 302'
        } else {
            Write-Fail '跳转返回 302' ("实际 " + $redirect.StatusCode)
            exit 1
        }
        $cacheControl = $redirect.Headers['Cache-Control']
        if ($cacheControl -like '*no-store*') {
            Write-Pass 'no-store 响应头存在'
        } else {
            Write-Fail 'no-store 响应头' ("实际值: " + $cacheControl)
        }

        # 登录并查统计
        Start-Sleep -Seconds 3
        $login = Invoke-RestMethod -Uri 'http://localhost:8080/api/auth/login' `
            -Method Post -ContentType 'application/json' `
            -Body '{"username":"admin","password":"admin123"}'
        if ($login.code -ne 0) { throw '登录失败，检查种子管理员密码' }
        Write-Pass '管理员登录'

        $headers = @{ Authorization = "Bearer " + $login.data.token }
        $stats = Invoke-RestMethod -Uri ("http://localhost:8080/api/stats/" + $code) -Headers $headers
        if ($stats.code -ne 0) { throw ("统计接口返回业务码 " + $stats.code) }
        Write-Pass '统计接口' ("PV = " + $stats.data.totalPv)
    } catch {
        Write-Fail '端到端冒烟' $_.Exception.Message
        Write-Host '  排查: docker compose logs backend' -ForegroundColor Yellow
        exit 1
    } finally {
        Pop-Location
    }
}

# ---------------------------------------------------------------------
# 汇总
# ---------------------------------------------------------------------
Write-Host ''
Write-Host ('=' * 68) -ForegroundColor DarkGray
Write-Host '验证汇总' -ForegroundColor Cyan
Write-Host ('=' * 68) -ForegroundColor DarkGray
$script:Results | Format-Table -AutoSize

$failed = @($script:Results | Where-Object { $_.Status -eq 'FAIL' })
if ($failed.Count -eq 0) {
    Write-Host '全部通过。可以放心推送到 GitHub 了。' -ForegroundColor Green
    Write-Host ''
    Write-Host '下一步：' -ForegroundColor White
    Write-Host '  1. 把 docs/benchmark.md 的实测表格填上（跑 loadtest/wrk/redirect.sh）'
    Write-Host '  2. 更新 docs/progress.md，把"未执行"改成实际结果'
    Write-Host '  3. git add -A && git commit -m "验证通过" && 推送到 GitHub'
    exit 0
} else {
    Write-Host ("有 {0} 项失败，请修复后重跑。" -f $failed.Count) -ForegroundColor Red
    exit 1
}
