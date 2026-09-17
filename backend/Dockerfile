# =====================================================================
# 后端镜像：Maven 构建 → JRE 运行
# 说明：镜像内已完成构建，因此 Maven 依赖也会被缓存进镜像层；
#       如需更小的运行镜像，可改为挂载宿主机构建产物。
# =====================================================================

# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build

# 先只拷贝 pom，预热依赖缓存层：源码改动不会导致重新下载依赖
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

COPY src ./src

# 跳过测试：镜像构建只负责产出可运行产物，测试应在 CI 中单独执行
RUN mvn -B -q -DskipTests clean package

# 利用 Spring Boot 分层能力，把依赖层与业务层拆开，提升镜像复用率
RUN java -Djarmode=layertools -jar target/shortlink-cloud-backend.jar extract --destination /build/layers

# ---------- 运行阶段 ----------
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends tzdata curl \
    && ln -snf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && echo "Asia/Shanghai" > /etc/timezone \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /app/logs

# 非 root 运行，降低容器逃逸后的影响面
RUN groupadd -r shortlink && useradd -r -g shortlink shortlink \
    && chown -R shortlink:shortlink /app

# 分层拷贝：变化频率低的层在前，最大化缓存命中
COPY --from=builder --chown=shortlink:shortlink /build/layers/dependencies/ ./
COPY --from=builder --chown=shortlink:shortlink /build/layers/spring-boot-loader/ ./
COPY --from=builder --chown=shortlink:shortlink /build/layers/snapshot-dependencies/ ./
COPY --from=builder --chown=shortlink:shortlink /build/layers/application/ ./

USER shortlink

EXPOSE 8080

ENV JAVA_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC -XX:MaxGCPauseMillis=100 -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/app/logs"

# 用 shell 形式以便展开 JAVA_OPTS 环境变量
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
