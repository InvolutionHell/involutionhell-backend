# 多阶段构建：第一阶段用 GraalVM 编译 Native Image
# 使用标准 glibc 版本，muslib (musl) 变体对 arm64 支持有限
FROM ghcr.io/graalvm/native-image-community:25 AS build

WORKDIR /app

# 先复制 Maven Wrapper 和 pom.xml，利用 Docker 层缓存加速依赖下载
COPY mvnw mvnw.cmd pom.xml ./
COPY .mvn .mvn

RUN chmod +x mvnw && ./mvnw dependency:go-offline -q

# 复制源码并编译 Native Image
COPY src ./src
# GitHub Actions buildkit 容器中 /tmp 以 noexec 挂载，GraalVM 需要在 /tmp 执行 C 辅助程序
# 将 TMPDIR 指向有执行权限的目录解决此问题
RUN mkdir -p /app/tmp && TMPDIR=/app/tmp ./mvnw -DskipTests -Pnative package

# 第二阶段：最小化运行镜像
FROM ubuntu:24.04

# 安装运行时依赖（curl 用于 healthcheck）
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /app/target/backend ./backend

RUN chmod +x ./backend

EXPOSE 8080

ENTRYPOINT ["./backend"]
