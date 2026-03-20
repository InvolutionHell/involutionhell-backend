# 内卷地狱 (Involution Hell) Backend

## 项目简介

这是 **内卷地狱 (Involution Hell)** 的官方后端服务。本项目采用 **Java** 和 **Spring Boot** 构建，旨在为前端提供高性能、可扩展的 API 支持，包括但不限于用户管理、文档贡献统计、协作预览以及 AI 助手集成等功能。

当前仓库已经落地了以下基础能力：

- Spring Boot 4 Web 服务基线
- Sa-Token 用户登录态与权限控制
- 用户中心最小 RBAC 骨架
- GraalVM Native Image 原生编译支持
- Spring Boot 4 测试链路与接口权限测试

后续可以在此基础上继续接入数据库、缓存、消息队列、对象存储和 AI 服务能力。

## 技术栈

### 当前已接入

- Java 25
- Spring Boot 4.0.4
- Spring Web MVC
- Sa-Token 1.45.0
- Jakarta Validation
- OpenAI Responses API
- Maven Wrapper
- GraalVM Native Build Tools 0.11.3
- JUnit 5 / Spring Boot Test

### 生产推荐配套

- Redis 7
- Apache RocketMQ 5.3.x
- Caddy

说明：

- 当前代码仓库已经包含用户中心与权限控制骨架。
- 仓库已补充中间件编排示例与反向代理示例，便于本地联调和生产方案设计。
- 当前业务代码还没有把 `Redis`、`RocketMQ` 全部真正接入，只是提前提供了部署骨架。

## 环境要求

- macOS 或 Linux
- `zsh`、`bash` 等常见 shell 环境
- JDK 21
- GraalVM 21（仅在执行本地 `native:compile-no-fork` 时需要）
- Docker Engine 24+ 与 Docker Compose v2
- 建议内存：
  - JVM 模式开发：4GB 及以上
  - Native Image 编译：16GB 及以上

如果你使用 macOS ARM 设备，请优先验证目标中间件镜像是否适配 `arm64` 平台。
如果你使用 Spring Boot Buildpacks 构建原生镜像，请保证 Docker 可用，并按下文示例显式设置 `BP_JVM_VERSION=25` 与 `imagePlatform=linux/amd64`。

## 目录概览

```text
.
├── .mvn/                              # Maven Wrapper 配置
├── compose-pre-up.sh                 # compose up 前的优雅停机脚本
├── docker-compose.yml                # 后端应用 + Redis 7 + Caddy 编排
├── docker-compose.middleware.yml     # 本地/测试环境中间件编排
├── Dockerfile                        # Caddy 反向代理镜像定义
├── .env.example                      # 中间件环境变量示例
├── src/
│   ├── main/
│   │   ├── java/com/involutionhell/backend/
│   │   │   ├── BackendApplication.java
│   │   │   ├── common/               # 通用响应、异常处理、Native Hint
│   │   │   ├── openai/               # OpenAI SSE 接口与流式转发
│   │   │   └── usercenter/           # 用户中心、鉴权、RBAC 相关代码
│   │   └── resources/
│   │       └── application.properties
│   └── test/
│       └── java/com/involutionhell/backend/
│           └── BackendApplicationTests.java
├── pom.xml
└── target/                           # 构建产物目录，不建议提交
```

### 用户中心包结构

- `controller/`：认证接口与用户中心接口
- `service/`：登录、权限、用户查询等业务逻辑
- `repository/`：当前为内存仓库，后续可替换为 JPA / MyBatis
- `security/`：Sa-Token 角色权限桥接
- `dto/`：请求和响应对象
- `model/`：领域模型
- `config/`：Sa-Token 拦截器配置

## 快速开始

### 1. 克隆并进入项目

```bash
git clone https://github.com/InvolutionHell/backend.git
cd backend
```

### 2. 启动中间件

复制环境变量模板：

```bash
cp .env.example .env
```

根据你的环境修改 `.env`，然后启动中间件：

```bash
docker compose -f docker-compose.yml up -d
```

默认会启动一套联调基线：

- Redis
- RocketMQ NameServer
- RocketMQ Broker

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
  -Dspring-boot.build-image.imagePlatform=linux/amd64

./compose-pre-up.sh
docker compose up -d --build
```

这一模式默认会启动 `Caddy + backend + Redis 7`。其中 `backend` 由 Spring Boot `build-image` 生成原生镜像，项目 `Dockerfile` 仅用于构建 `Caddy`。

默认入口为：

```text
http://127.0.0.1
```

如果你是直接以 JVM 或单独应用容器方式运行后端，请改用：

```text
http://127.0.0.1:8080
```

仅启动应用容器：

```bash
mvn -Pnative spring-boot:build-image \
  -DskipTests \
  -Dspring-boot.build-image.imageName=backend:native \
  -Dspring-boot.build-image.environment.BP_JVM_VERSION=25 \
  -Dspring-boot.build-image.imagePlatform=linux/amd64

docker run --rm --platform linux/amd64 -p 8080:8080 --env-file .env backend:native
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

### 部署模式建议

推荐两种部署方式：

1. JVM 包部署：适合传统 Java 应用服务器环境。
2. GraalVM Native 部署：适合追求更低启动时间和更低内存占用的场景。

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
  -Dspring-boot.build-image.imagePlatform=linux/amd64
```

再启动应用与 Caddy：

```bash
docker compose up -d --build
```

默认情况下，`docker-compose.yml` 会直接使用 `.env` 中的 `BACKEND_IMAGE_NAME`，默认值是 `backend:native`，并固定以 `linux/amd64` 运行 `backend` 服务。
`./compose-pre-up.sh` 会在重新拉起前，按顺序优雅停止 `backend`、`caddy`、`redis`。当前编排中的三个服务共享同一个 Docker 网络。

如果你想调整后端镜像名：

```bash
mvn -Pnative spring-boot:build-image \
  -DskipTests \
  -Dspring-boot.build-image.imageName=<your-image-name> \
  -Dspring-boot.build-image.environment.BP_JVM_VERSION=25 \
  -Dspring-boot.build-image.imagePlatform=linux/amd64
```

如果你只想构建 Caddy 代理镜像：

```bash
docker build -t involution-hell-caddy .
```

默认通过以下环境变量驱动容器运行参数：

- `SPRING_PROFILES_ACTIVE`
- `SERVER_PORT`
- `SPRING_APPLICATION_NAME`
- `BACKEND_IMAGE_NAME`
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

### Caddy 反向代理

仓库提供了示例配置：

```text
deploy/caddy/Caddyfile
```

这一版 Caddy 配置默认包含以下能力：

- 上游主动健康检查
- `zstd/gzip` 压缩
- 基础安全响应头
- 屏蔽 `TRACE/TRACK`
- JSON 访问日志
- 基于环境变量的站点地址和上游目标配置

### 生产部署建议

- Redis 开启密码与网络访问控制
- RocketMQ 在公网环境下启用 ACL 2.0 与内网访问隔离
- 所有配置通过环境变量或密钥管理系统注入，不要把生产密钥写死到仓库
- Caddy 层启用自动 HTTPS、请求体限制、结构化访问日志与上游健康探测
- 为应用增加健康检查、监控和告警
- 发布前先执行 `./mvnw test` 与 `./mvnw -DskipTests native:compile-no-fork`

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

## 协议版权

当前仓库**尚未附带独立的 LICENSE 文件**。

因此，在仓库正式声明开源协议之前：

- 代码版权默认归项目维护者或所属组织所有
- 未经授权，不建议将本仓库内容用于再分发或商业发布
- 如计划对外开源，请尽快补充明确的 `LICENSE` 文件，并同步更新本节内容

如果你准备将项目开源，常见可选协议包括：

- MIT：更宽松
- Apache-2.0：宽松且带专利授权条款
- GPL/AGPL：更强调衍生作品的开源要求

## 维护说明

当前项目仍处于基础能力建设阶段，README 中的生产建议已提前为后续业务扩展预留了路径。

如果后续你希望继续完善，我建议下一步优先处理这几件事：

1. 将内存用户仓库替换为持久化数据库实现
2. 将 Sa-Token 会话存储切换到 Redis
3. 引入配置中心或环境变量配置体系
4. 增加 Actuator、监控、链路追踪与审计日志
5. 接入 AI 服务网关与调用限流策略
