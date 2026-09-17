# =====================================================================
# 前端镜像：Node 构建 → Nginx 托管
# 多阶段构建，最终镜像不含 node_modules 与源码
# =====================================================================

# ---------- 构建阶段 ----------
FROM node:20-alpine AS builder

WORKDIR /app

# 先只拷贝依赖清单，利用 Docker 层缓存：依赖不变时不会重复 npm install
COPY package.json package-lock.json* ./

# 没有 lock 文件时 npm ci 会失败，这里用条件判断兼容两种情况
RUN if [ -f package-lock.json ]; then npm ci; else npm install; fi

COPY . .

# 允许在构建期注入后端地址（默认走 Nginx 同源反代）
ARG VITE_API_BASE_URL=/api
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}
RUN npm run build

# ---------- 运行阶段 ----------
FROM nginx:1.27-alpine

# 时区与 locale：日志时间与中文文件名不乱码
RUN apk add --no-cache tzdata curl \
    && cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && echo "Asia/Shanghai" > /etc/timezone

COPY --from=builder /app/dist /usr/share/nginx/html
COPY nginx/default.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD curl -fsS http://127.0.0.1/healthz || exit 1

CMD ["nginx", "-g", "daemon off;"]
