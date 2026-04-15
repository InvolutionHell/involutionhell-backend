# 多阶段构建：第一阶段用 JDK 25 打 fat JAR
# 注：之前用 GraalVM native-image，但 @Cacheable 的 SpEL key 表达式在 native 模式下
# 反复触发 reflection hint 缺失导致容器启动失败（2026-04-14 起持续部署失败的根因）。
# 后端不跑在 serverless / edge 上，JVM 启动 ~5s 完全可接受，先切回 JVM 解阻塞。
# 后续若要重新切 native，需要补 RuntimeHints / @ImportRuntimeHints。
FROM eclipse-temurin:25-jdk AS build

WORKDIR /app

# 先复制 Maven Wrapper 和 pom.xml，利用 Docker 层缓存加速依赖下载
COPY mvnw mvnw.cmd pom.xml ./
COPY .mvn .mvn

RUN chmod +x mvnw && ./mvnw dependency:go-offline -q

# 复制源码并打 JAR
COPY src ./src
RUN ./mvnw -DskipTests package

# 第二阶段：最小化运行镜像
FROM eclipse-temurin:25-jre

# 安装运行时依赖（curl 用于 healthcheck）
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*

WORKDIR /app
# Spring Boot 默认产物 backend-*.jar，固定重命名为 backend.jar 方便 ENTRYPOINT
COPY --from=build /app/target/backend-*.jar ./backend.jar

EXPOSE 8080

# JVM 参数：容器感知内存、UTF-8 默认编码、GC 选 G1（默认即可，显式写出便于将来调）
ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75", \
    "-Dfile.encoding=UTF-8", \
    "-jar", "backend.jar"]
