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
  <img alt="Java" src="https://img.shields.io/badge/Java-7F52FF?style=for-the-badge&logo=java&logoColor=white" />
  <img alt="PostgreSQL" src="https://img.shields.io/badge/PostgreSQL-4169E1?style=for-the-badge&logo=postgresql&logoColor=white" />
  <img alt="Neon" src="https://img.shields.io/badge/Neon-00E599?style=for-the-badge&logo=neon&logoColor=black" />
</p>

---

## 📖 项目简介

这是 **[内卷地狱 (Involution Hell)](https://involutionhell.com)** 的官方后端服务。本项目采用 **Java 25** 和 **Spring Boot 4** 构建，旨在为前端提供高性能、可扩展的 API 支持，包括但不限于用户管理、文档贡献统计、协作预览以及 AI 助手集成等功能。

### 核心能力
- **Spring Boot 4**：基于最新 LTS 版本的 Web 服务基线。
- **安全中心**：集成 **Spring Security OAuth2 Client** 与 **Resource Server** 实现标准化认证。
- **持久化层**：采用 **PostgreSQL 18**，完美契合 Neon 数据库生态。
- **高性能编译**：原生支持 **GraalVM Native Image**，提供极速启动与极低内存占用。

> [!NOTE]
> 本仓库仅包含后端代码。关于整个项目的愿景、贡献指南及前端实现，请参阅 **[主项目 README](../frontend/README.md)**。

## 技术栈

- **语言 & 框架**: Java 25, Spring Boot 4.0.4
- **安全 & 认证**: Spring Security OAuth2 Client 6.x
- **数据库**: PostgreSQL 18 (与 NEON 环境一致)
- **AI 抽象**: OpenAI Responses API (支持流式转发)
- **中间件**: Redis 7, Caddy 2.10
- **构建工具**: Maven 3.9+, GraalVM Build Tools

## 目录概览

```text
.
├── .mvn/                             # Maven Wrapper 配置
├── docker-compose.yml                # 中间件编排 (Postgres, Redis, Caddy, Backend)
├── docker/init-db/init.sql           # 数据库初始化脚本 (含 Schema 与种子数据)
├── src/
│   ├── main/
│   │   ├── java/com/involutionhell/backend/
│   │   │   ├── usercenter/           # 用户中心与权限
│   │   │   └── openai/               # AI 接口逻辑
│   │   └── resources/
│   │       ├── application.properties
│   │       └── schema.sql            # 数据库建表脚本
│   └── test/                         # 单元测试与集成测试
├── pom.xml
└── target/                           # 构建产物
```

## 快速开始

### 1. 准备环境
确保已安装 JDK 25、Docker 以及 Maven。复制环境变量模板：

```bash
cp .env.example .env
```

### 2. 启动基础设施 (推荐)
为了避免繁琐的本地数据库安装和环境变量配置，推荐使用 Docker 一键运行：

```bash
# 启动 PostgreSQL 18 与 Redis 7
# 此操作会自动创建 Schema 并初始化种子数据 (一致性与 NEON 保持同步)
docker compose up -d postgres redis
```

### 3. 启动后端服务
在根目录下直接启动 JVM 模式：

```bash
./mvnw spring-boot:run
```

默认接口入口：`http://127.0.0.1:8080/api/v1`

### 4. 调用示例接口
内置种子账号：
- `admin / Admin@123456`
- `alice / Alice@123456`

登录示例：
```bash
curl -X POST http://127.0.0.1:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username": "admin", "password": "Admin@123456"}'
```

## 测试与构建

### 运行测试
```bash
./mvnw test
```

### Native Image 编译
```bash
./mvnw -DskipTests native:compile-no-fork
# 产物位于 target/backend
```

## CI/CD 自动部署

推送到 `main` 分支后，GitHub Actions 会自动触发部署流程：

```
push to main
  → SCP 同步源码到服务器
  → 服务器执行 docker build
  → docker compose up -d 重启服务
```

**生产环境健康检查端点：**

```
GET https://api.involutionhell.com/api/v1/actuator/health
```

返回 `{"status":"UP"}` 表示服务正常运行。

> [!IMPORTANT]
> **关于部署失败与宕机风险：**
>
> - **`docker build` 阶段失败** → 安全，旧容器不受影响，GitHub Actions 标红但服务继续运行。
> - **build 成功但新容器启动崩溃**（配置错误、数据库迁移异常等）→ 会短暂宕机。旧容器已被停止，新容器因 `restart: always` 持续重启但无法自愈，需人工介入。
>
> 合并前请确保本地执行 `./mvnw test` 通过，并确认 `.env` 所需的环境变量在服务器上已正确配置。

## 贡献规范
- 保持代码可编译，提交前执行 `./mvnw test`。
- 业务逻辑请放在 `service` 层，保持 `controller` 层简洁。
- 对外暴露的方法尽量补齐 Javadoc 注释。

---

## 维护说明
本项目由 **InvolutionHell** 社区维护，旨在打破内卷，共建透明的计算机学习生态。
