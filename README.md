<p align="right">
  <a href="./README.md">简体中文</a>
</p>

<p align="center">
  <a href="https://involutionhell.com">
    <picture>
      <!-- Dark mode logo -->
      <source media="(prefers-color-scheme: dark)" srcset="https://involutionhell.com/logo/logoInDark.svg">
      <!-- Light mode logo -->
      <source media="(prefers-color-scheme: light)" srcset="https://involutionhell.com/logo/logoInLight.svg">
      <!-- Fallback -->
      <img src="https://involutionhell.com/mascot.webp" width="150" alt="Involution Hell Logo">
    </picture>
  </a>
</p>

<p align="center">
  <img src="https://readme-typing-svg.demolab.com/?font=Noto+Sans+SC&weight=700&size=32&pause=1000&color=f6671b&center=true&vCenter=true&width=420&lines=%E5%86%85%E5%8D%B7%E5%9C%B0%E7%8B%B1%E5%90%8E%E7%AB%AF&duration=3000" alt="Typing SVG" />
</p>

<p align="center">
  <img alt="Spring Boot" src="https://img.shields.io/badge/Spring_Boot-6DB33F?style=for-the-badge&logo=springboot&logoColor=white" />
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" />
  <img alt="PostgreSQL" src="https://img.shields.io/badge/PostgreSQL-4169E1?style=for-the-badge&logo=postgresql&logoColor=white" />
  <img alt="Neon" src="https://img.shields.io/badge/Neon-00E599?style=for-the-badge&logo=neon&logoColor=black" />
</p>

---

## 📖 项目简介

这是 **[内卷地狱 (Involution Hell)](https://involutionhell.com)** 的官方后端服务。

本项目采用 **Kotlin** 和 **Spring Boot** 构建，旨在为前端提供高性能、可扩展的 API 支持，包括但不限于用户管理、文档贡献统计、协作预览以及 AI 助手集成。

> [!NOTE]
> 本仓库仅包含后端代码。关于整个项目的愿景、贡献指南及前端实现，请参阅 **[主项目 README](../frontend/README.md)**。

## ✨ 技术栈

- **核心框架**: Spring Boot 4.0.3 (基于 Java 25)
- **开发语言**: Kotlin 2.1.10 (协程支持)
- **持久层**: Spring Data JPA + Hibernate 7
- **数据库**: PostgreSQL (托管于 Neon.tech)
- **构建工具**: Maven

## 🚀 快速开始

### 环境要求

- **JDK 25+** (必须，项目利用了 Java 25 的最新特性)
- **Maven 3.9+**
- **PostgreSQL** (推荐使用 Neon.tech 提供的 Serverless 数据库)

### 运行步骤

1. **克隆仓库** (如果你还没有克隆整个项目):
   ```bash
   git clone https://github.com/involutionhell/involutionhell.git
   cd involutionhell/backend-java
   ```

2. **配置环境变量**:
   在 `src/main/resources/application.properties` 中配置你的数据库连接信息。
   > [!IMPORTANT]
   > 如果使用 Neon 数据库，请确保 URL 中包含 `sslmode=require`。

3. **编译并启动**:
   ```bash
   ./mvnw spring-boot:run
   ```
   服务默认运行在 [http://localhost:8080](http://localhost:8080)。

4. **验证接口**:
   访问 [http://localhost:8080/api/users](http://localhost:8080/api/users) 查看用户列表测试。

## 📁 目录概览

```
📦 backend-java
├── 📂 src/main/kotlin    # Kotlin 源代码
│   └── 📂 com.involutionhell.backend
│       ├── 📄 BackendApplication.kt  # 入口类
│       ├── 📄 UserController.kt      # 用户相关 API 与实体
│       └── 📄 HomeTaskController.kt  # 远程任务分发示例
├── 📂 src/main/resources   # 配置文件
│   └── 📄 application.properties    # 主要配置文件
├── 📂 .github/workflows    # CI/CD (GitHub Actions)
└── 📄 pom.xml              # Maven 配置
```

## 🤝 贡献规范

请参考主项目的 **[CONTRIBUTING.md](../frontend/CONTRIBUTING.md)** 以了解如何参与贡献。后端代码需遵循 Kotlin 官方代码风格指南，并确保通过所有单元测试。

## 📜 协议与版权

本项目代码遵循 [署名-非商业性使用-相同方式共享 4.0 国际许可协议（CC BY-NC-SA 4.0）](../frontend/LICENSE)。

## 项目简介

这是 **内卷地狱 (Involution Hell)** 的官方后端服务。本项目采用 **Java** 和 **Spring Boot** 构建，旨在为前端提供高性能、可扩展的 API 支持，包括但不限于用户管理、文档贡献统计、协作预览以及 AI 助手集成等功能。

当前仓库已经落地了以下基础能力：

- Spring Boot 4 Web 服务基线
- Sa-Token 用户登录态与权限控制
- PostgreSQL 18 持久化用户中心
- GraalVM Native Image 原生编译支持
- Spring Boot 4 测试链路与接口权限测试

后续可以在此基础上继续接入数据库、缓存、消息队列、对象存储和 AI 服务能力。

## 技术栈

### 当前已接入

- Java 25
- Spring Boot 4.0.4
- Spring Web MVC
- Spring JDBC
- PostgreSQL 18
- Sa-Token 1.45.0
- Jakarta Validation
- OpenAI Responses API
- Maven Wrapper
- GraalVM Native Build Tools 0.11.3
- JUnit 5 / Spring Boot Test

### 生产推荐配套

- PostgreSQL 18
- Redis 7
- Apache RocketMQ 5.3.x
- Caddy
- maven < 3.9

#### !检查是否成功使用 GraalVM 

```bash
mvn --version
```

```text
Apache Maven 3.9.11 (3e54c93a704957b63ee3494413a2b544fd3d825b)
Maven home: /opt/homebrew/Cellar/maven/3.9.11/libexec
Java version: 25.0.2, vendor: Oracle Corporation, runtime: /Users/polaris/.sdkman/candidates/java/25.0.2-graal
Default locale: zh_AU_#Hans, platform encoding: UTF-8
OS name: "mac os x", version: "26.3.1", arch: "aarch64", family: "mac"
```

说明：

- 当前代码仓库已经包含基于 PostgreSQL 的用户中心与权限控制实现。
- 仓库已补充中间件编排示例与反向代理示例，便于本地联调和生产方案设计。
- 当前业务代码已经接入 `PostgreSQL`，`Redis`、`RocketMQ` 仍保留为后续扩展骨架。

## 环境要求

- macOS 或 Linux
- `zsh`、`bash` 等常见 shell 环境
- JDK 25
- GraalVM 25（为你满足 spring boot4 执行 `native:compile-no-fork` 时需要）
- Docker Engine 24+ 与 Docker Compose v2
- 建议内存：
  - JVM 模式开发：4GB 及以上
  - Native Image 编译：16GB 及以上

如果你使用 macOS ARM 设备，请优先验证目标中间件镜像是否适配 `arm64` 平台。
如果你使用 Spring Boot Buildpacks 构建原生镜像，请保证 Docker 可用，并按下文示例显式设置 `BP_JVM_VERSION=25` 与 `imagePlatform=linux/amd64`。

## 目录概览

```text
.
├── .mvn/                             # Maven Wrapper 配置
├── docker-compose.yml                # Redis 7 + backend + Caddy 编排
├── docker-compose.middleware.yml     # 本地/测试环境中间件编排
├── .env.example                      # 中间件环境变量示例
├── src/
│   ├── main/
│   │   ├── java/com/involutionhell/backend/
│   │   │   ├── BackendApplication.java
│   │   │   ├── common/               # 通用响应、异常处理、Native Hint
│   │   │   ├── openai/               # OpenAI SSE 接口与流式转发
│   │   │   └── usercenter/           # 用户中心、鉴权、RBAC 相关代码
│   │   └── resources/
│   │       ├── application.properties
│   │       └── schema.sql            # PostgreSQL 建表脚本
│   └── test/
│       ├── java/com/involutionhell/backend/
│       │   └── BackendApplicationTests.java
│       └── resources/
│           └── test-schema.sql       # 测试环境重建脚本
├── pom.xml
└── target/                           # 构建产物目录
```

### 用户中心结构

- `controller/`：认证接口与用户中心接口
- `service/`：登录、权限、用户查询等业务逻辑
- `repository/`：JDBC 仓库实现与数据库种子初始化
- `security/`：Sa-Token 角色权限桥接
- `dto/`：请求和响应对象
- `model/`：领域模型
- `config/`：Sa-Token 拦截器配置

## 快速开始

### 1. 克隆并进入项目

```bash
git clone https://github.com/InvolutionHell/involutionhell-backend.git
cd backend
```

### 2. 启动 PostgreSQL 18 与 Redis 7

复制环境变量模板：

```bash
cp .env.example .env
```

根据你的环境修改 `.env`，然后启动中间件：

```bash
docker compose up -d redis
```

默认会启动一套联调基线：

- Redis

### 3. 启动后端服务

JVM 模式：

```bash
./mvnw spring-boot:run
```

Docker + Caddy 模式：

```bash
mvn -Pnative spring-boot:build-image \
  -DskipTests \
  -Dspring-boot.build-image.imageName=backend:native \
  -Dspring-boot.build-image.environment.BP_JVM_VERSION=25 \
  -Dspring-boot.build-image.imagePlatform=linux/arm64

./compose-pre-up.sh
docker compose up -d --build
```

这一模式默认会启动 `Redis 7 + Caddy + backend`。其中 `backend` 由 Spring Boot `build-image` 生成原生镜像。

默认入口为：

```text
http://127.0.0.1:8080/api/v1
```

如果你是直接以 JVM 或单独应用容器方式运行后端，请改用：

```text
http://127.0.0.1:8080/api/v1
```

仅启动应用容器：

```bash
mvn -Pnative spring-boot:build-image \
  -DskipTests \
  -Dspring-boot.build-image.imageName=backend:native \
  -Dspring-boot.build-image.environment.BP_JVM_VERSION=25 \
  -Dspring-boot.build-image.imagePlatform=linux/arm64

docker run --rm --platform linux/arm64 -p 8080:8080 --env-file .env backend:native
```

### 4. 调用示例接口

默认种子账号如下：

- `admin / Admin@123456`
- `alice / Alice@123456`
- `auditor / Audit@123456`

登录：

```bash
curl -X POST http://127.0.0.1/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "username": "admin",
    "password": "Admin@123456"
  }'
```

返回结果中的 `data.tokenValue` 即为登录令牌，请在后续请求中通过请求头 `satoken` 传递：

```bash
curl http://127.0.0.1/api/auth/me \
  -H "satoken: <your-token>"
```

查询用户列表：

```bash
curl http://127.0.0.1/api/user-center/users \
  -H "satoken: <your-token>"
```

### 5. 配置 OpenAI 流式接口

在 `.env` 中补齐以下参数：

```bash
OPENAI_API_URL=https://api.openai.com/v1/responses
OPENAI_API_KEY=<your-openai-api-key>
OPENAI_MODEL=gpt-4.1
```

然后调用 SSE 接口：

```bash
curl -N -X POST http://127.0.0.1/api/openai/responses/stream \
  -H "Content-Type: application/json" \
  -H "satoken: <your-token>" \
  -d '{
    "message": "请介绍一下内卷地狱后端服务",
    "instructions": "请使用简洁的中文回答"
  }'
```

该接口会直接转发 OpenAI Responses API 的流式事件，常见事件名包括：

- `response.output_text.delta`
- `response.completed`
- `error`

## 测试方法

### 运行单元测试与集成测试

```bash
./mvnw test
```

### 运行完整校验

```bash
./mvnw clean verify
```

### Native Image 编译验证

```bash
./mvnw -DskipTests native:compile-no-fork
```

编译成功后，原生二进制产物位于：

```text
target/backend
```

你也可以直接运行：

```bash
./target/backend
```

测试报告默认输出到：

```text
target/surefire-reports/
```

## 生产部署

### 中间件基线

生产环境建议至少准备以下服务：

- Redis：Token 会话、热点缓存、限流、分布式锁
- RocketMQ：异步事件、消息通知、统计任务解耦
- Caddy：反向代理、TLS 终止、访问日志、安全响应头、压缩

仓库已提供一个可用的中间件联调示例：

```bash
docker compose -f docker-compose.yml up -d
```

请注意：

- `docker-compose.middleware.yml` 更适合作为开发、测试、联调基线
- 生产环境应改用独立存储卷、专用网络、受控账号与更严格的监控告警

### JVM 部署

打包：

```bash
./mvnw -DskipTests package
```

启动：

```bash
java -jar target/backend-0.0.1-SNAPSHOT.jar
```

### Docker 部署

先构建后端原生镜像：

```bash
mvn -Pnative spring-boot:build-image \
  -DskipTests \
  -Dspring-boot.build-image.imageName=backend:native \
  -Dspring-boot.build-image.environment.BP_JVM_VERSION=25 \
  -Dspring-boot.build-image.imagePlatform=linux/arm64
```

再启动应用与 Caddy：

```bash
./compose-pre-up.sh
docker compose up -d --build
```

默认情况下，`docker-compose.yml` 会直接使用 `.env` 中的 `BACKEND_IMAGE_NAME`，默认值是 `backend:native`，并固定以 `linux/amd64` 运行 `backend` 服务。
`./compose-pre-up.sh` 会在重新拉起前，按顺序优雅停止 `backend`、`caddy`、`redis`。当前编排中的 `postgres`、`redis`、`caddy`、`backend` 共享同一个 Docker 网络。

如果你想调整后端镜像名：

```bash
mvn -Pnative spring-boot:build-image \
  -DskipTests \
  -Dspring-boot.build-image.imageName=<your-image-name> \
  -Dspring-boot.build-image.environment.BP_JVM_VERSION=25 \
  -Dspring-boot.build-image.imagePlatform=linux/arm64
```

如果你只想构建 Caddy 代理镜像：

```bash
docker build -t involution-hell-caddy .
```

默认通过以下环境变量驱动容器运行参数：

- `POSTGRES_DB`
- `POSTGRES_USER`
- `POSTGRES_PASSWORD`
- `SPRING_PROFILES_ACTIVE`
- `SERVER_PORT`
- `SPRING_APPLICATION_NAME`
- `BACKEND_IMAGE_NAME`
- `SPRING_DATASOURCE_URL`
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`
- `SPRING_SQL_INIT_MODE`
- `SA_TOKEN_TOKEN_NAME`
- `SA_TOKEN_TIMEOUT`
- `SA_TOKEN_ACTIVE_TIMEOUT`
- `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE`
- `MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED`
- `OPENAI_API_URL`
- `OPENAI_API_KEY`
- `OPENAI_MODEL`
- `CADDY_SITE_ADDRESS`
- `CADDY_HTTP_PORT`
- `CADDY_HTTPS_PORT`
- `CADDY_UPSTREAM`
- `CADDY_UPSTREAM_HEALTH_URI`
- `CADDY_MAX_REQUEST_BODY`

当 `CADDY_SITE_ADDRESS` 设置为域名，例如 `api.example.com` 时，Caddy 会在 80/443 端口可达的前提下自动申请和续签 HTTPS 证书。
如果你修改了应用容器内部的 `SERVER_PORT`，请同步修改 `CADDY_UPSTREAM`，否则 Caddy 会转发到错误端口。
当前原生镜像构建基于 Spring Boot Buildpacks 与 GraalVM Native Image 支持完成，后端镜像不再通过项目 `Dockerfile` 多阶段编译。

### Native 部署

编译：

```bash
./mvnw -DskipTests native:compile-no-fork
```

启动：

```bash
./target/backend
```

### 生产部署建议

- Redis 开启密码与网络访问控制
- RocketMQ 在公网环境下启用 ACL 2.0 与内网访问隔离
- 所有配置通过环境变量或密钥管理系统注入，不要把生产密钥写死到仓库
- Caddy 层启用自动 HTTPS、请求体限制、结构化访问日志与上游健康探测
- 为应用增加健康检查、监控和告警
- 发布前先执行 `./mvnw test` 与 `./mvnw -Pnative -DskipTests native:compile-no-fork`

## 贡献规范

欢迎通过 Issue 或 Pull Request 参与贡献。

### 分支命名建议

- `feature/<name>`：新功能
- `fix/<name>`：缺陷修复
- `refactor/<name>`：重构
- `docs/<name>`：文档调整

### 提交前要求

- 保持代码可编译
- 新增或修改逻辑后执行 `./mvnw test`
- 文档、接口、配置变更需要同步更新 README 或相关说明
- 对外暴露的方法、构造器尽量补齐 Javadoc 注释
- 不要提交 `target/`、IDE 临时文件或本地密钥

### 代码风格建议

- 优先保持包结构清晰，按业务域拆分模块
- 控制器只处理请求与响应，不堆积业务逻辑
- 服务层负责业务编排
- 仓库层后续替换为数据库实现时，保持接口语义稳定
- 新增中间件接入时，优先通过配置隔离环境差异


## 维护说明

当前项目仍处于基础能力建设阶段，README 中的生产建议已提前为后续业务扩展预留了路径。

如果后续你希望继续完善，我建议下一步优先处理这几件事：

1. 将内存用户仓库替换为持久化数据库实现
2. 将 Sa-Token 会话存储切换到 Redis
3. 引入配置中心或环境变量配置体系
4. 增加 Actuator、监控、链路追踪与审计日志
5. 接入 AI 服务网关与调用限流策略
