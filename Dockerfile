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
# GitHub Actions buildkit 容器中 /tmp 以 noexec 挂载
# 用 --mount=type=tmpfs 挂载一个有 exec 权限的新 tmpfs，GraalVM 的 C 辅助程序才能执行
RUN --mount=type=tmpfs,target=/tmp \
    ./mvnw -DskipTests -Pnative package

# 第二阶段：最小化运行镜像
FROM ubuntu:24.04

# 安装运行时依赖（curl 用于 healthcheck）
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /app/target/backend ./backend

RUN chmod +x ./backend

EXPOSE 8080

ENTRYPOINT ["./backend"]
