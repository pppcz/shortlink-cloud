# 推送到 GitHub

> 本文件给出把本项目上传到你自己 GitHub 账号的**两种方式**。
> 之所以需要你手动执行最后一步，是因为生成这份代码的沙箱环境
> 无法连接 GitHub（实测证据见 `docs/progress.md` 的「关于推送到 GitHub」小节）。

---

## 当前状态

- 本地已有完整 Git 仓库，分支 `main`，共 7 个提交
- **没有任何 remote**，代码尚未上传到任何远端
- 已导出单文件包：`shortlink-cloud.bundle`（含全部分支与历史）

```
$ git log --oneline
e81cffe docs: 修正测试类计数并完善进度记录
8fcd954 feat(release): 前端与部署
16025e4 feat(stats): MQ 异步统计
2802218 feat(cache): Redis 缓存与限流
45e63b8 feat(shortlink): 短链生成与跳转
5600c00 chore: init shortlink-cloud
```

---

## 方式一：在你有网的机器上直接推送（推荐）

### 1. 在 GitHub 网页上建一个空仓库

打开 <https://github.com/new>：

| 字段 | 填写 |
| --- | --- |
| Repository name | `shortlink-cloud` |
| Description | 高并发短链平台：短链生成、跳转、统计、限流、防刷、管理后台 |
| Visibility | 按需选择 Public / Private |
| Initialize this repository with | **全部不要勾选**（不要 README、不要 .gitignore、不要 License） |

> ⚠️ 勾选「Add a README」会让远端产生一个提交，导致推送时历史分叉而需要先 pull。
> 建**空**仓库最省事。

### 2. 在本地仓库目录执行

如果你就在这台机器上（代码已经在 `D:\deepseek\workspace\shortlink-cloud`）：

```bash
cd /d D:\deepseek\workspace\shortlink-cloud

# 把 <你的用户名> 换成你的 GitHub 用户名
git remote add origin https://github.com/<你的用户名>/shortlink-cloud.git

# 首次推送会弹出浏览器让你登录 GitHub（Git Credential Manager 已配置）
git push -u origin main
```

推送成功后访问 `https://github.com/<你的用户名>/shortlink-cloud` 即可看到代码。

### 3. 如果不想用命令行

也可以用 GitHub Desktop：`File → Add local repository` 选中
`D:\deepseek\workspace\shortlink-cloud`，然后点 `Publish repository`。

---

## 方式二：从这台机器拷贝 bundle 到有网的机器

`shortlink-cloud.bundle` 是一个**自包含的单文件 Git 仓库**，
包含全部分支、标签与提交历史。适合"这台机器没网、另一台有网"的情况。

### 1. 拷贝文件

把 `shortlink-cloud.bundle`（约 1 MB 以内）复制到有网的机器。

### 2. 在有网的机器上克隆出来

```bash
git clone shortlink-cloud.bundle shortlink-cloud
cd shortlink-cloud

# 克隆出来的默认分支可能不叫 main，确认并整理一下
git branch -a
git checkout -B main origin/main      # 若默认分支已是 main 则跳过

git remote remove origin              # 去掉指向 bundle 的临时 remote
git remote add origin https://github.com/<你的用户名>/shortlink-cloud.git
git push -u origin main
```

### 3. 校验完整性（可选）

```bash
git bundle verify shortlink-cloud.bundle
```

应输出 `The bundle contains these N refs` 且无 `error` 字样。

---

## 推送后建议做的两件事

### 1. 修掉仓库里唯一的已知隐患

`admin / admin123` 是写在 Flyway 迁移 `V2__seed_admin_user.sql` 里的默认账号。
公开仓库意味着任何人都会看到它。建议：

- 在 README 顶部保留警告（已经有了）
- 或者改成首次启动时从环境变量读取初始密码

### 2. 别急着把 README 的"压测报告"当成已完成

`docs/benchmark.md` 的实测表格是**空的**。
在你有 Docker 的机器上跑通 §3 的步骤、把数字填进 §4 之后再对外宣传性能，
否则仓库里会出现互相矛盾的表述（README 声称有压测报告，实际没有数据）。

---

## 常见问题

**Q: `git push` 报 `remote: Support for password authentication was removed`**
A: GitHub 已不支持账号密码推送，必须用 Personal Access Token 或 SSH key。
用 Git Credential Manager 时会自动走浏览器 OAuth，正常不会遇到这个问题。
若遇到，去 <https://github.com/settings/tokens> 建一个带 `repo` 权限的 token，
在提示输入密码时粘贴 token 即可。

**Q: 报 `Updates were rejected because the remote contains work that you do not have`**
A: 建仓时勾选了 README/.gitignore 等。执行
`git pull --rebase origin main` 后再 `git push -u origin main`。

**Q: 我想把仓库名改成别的**
A: 仓库名与本地目录名无关，随意改。只需保证 `git remote add` 的 URL 用新名字。

**Q: 推送后想再更新代码**
A: 在本地 `git add -A && git commit -m "..."` 然后 `git push` 即可。
