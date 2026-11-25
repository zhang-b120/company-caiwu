# company-caiwu

财务记账后端。覆盖账套、凭证、科目、出纳、工资、固定资产、发票、报税，以及从外部财务软件导入账套。

可运行入口是 `bs-server`。Spring 应用名 `financial-v3-web-server`，打包产物名 `financial-v4`，HTTP 端口 **8012**。

## 技术栈

| 项 | 版本 / 说明 |
|---|---|
| JDK | 17 |
| Gradle | 8.5（`gradle/wrapper/gradle-wrapper.properties`） |
| 数据库 | MySQL 8，库名 `financial` |
| 缓存 | Redis，逻辑库 `2`，无密码 |
| 持久化 | JPA / Hibernate + SQLToy + Flyway |
| 登录态 | Sa-Token，请求头名 `fv3token` |

本仓库只有三个子模块的 `build.gradle`，没有根 `settings.gradle`、根 `build.gradle`、`gradlew`。子模块依赖父工程提供 Spring Boot 插件，以及属性 `poiVer`、`poiSchemasVer`、`sqltoyVer`。把这三个目录挂进已有的 Gradle 多模块根工程后再编译。

## 项目结构

```
company-caiwu/
├── kernel/                         领域层，打成普通 jar，不单独启动
│   └── src/main/
│       ├── java/.../kernel/
│       │   ├── model/              entity、repo、dto、enums、mapper
│       │   ├── service/            账套、凭证、工资、发票等业务服务
│       │   ├── config/ controller/ tool/
│       └── resources/
│           ├── application.yml     数据源、Redis、Flyway、短信与模型的基础配置
│           ├── sqltoy/             SQLToy 映射
│           └── template/           Excel / 报表模板
├── sync/                           账套导入，编译期依赖 kernel
│   └── src/main/java/.../sync/
│       ├── ImportService.java      导入入口：科目、期初、凭证
│       ├── SyncType.java           来源枚举
│       └── service/                四个来源的解析实现
├── bs-server/                      Spring Boot 启动模块，依赖 kernel、sync
│   ├── src/main/java/.../financial/
│   │   ├── FinancialApplication.java
│   │   ├── controller/             页面接口；openapi/ 为 /openapi/v1/**
│   │   ├── account/ agent/ ai/ bank/ crm/ task/ ua/ …
│   │   └── security/ rpa/ job/
│   ├── src/main/resources/
│   │   ├── application-app.yml     端口、邮件、内部 Token、文件目录、模型 Key
│   │   ├── application-dev.yml     本地库与本地 SMTP 覆盖
│   │   ├── application-prod.yml    连接池与日志级别
│   │   └── logback-spring.xml
│   └── tools/local-dev-setup.md    本地邮件联调的三种做法
└── gradle/wrapper/                 Wrapper 分发包地址，仓库内没有 gradlew 脚本
```

模块依赖：`bs-server` → `kernel`、`sync`；`sync` → `kernel`（`compileOnly`）。

### kernel

共享领域与持久化。实体、仓储、业务服务、SQLToy、Excel 模板都在这里。`bootJar` 已关闭，只产出普通 jar。基础配置 `kernel/src/main/resources/application.yml` 会进入 `bs-server` 的 classpath，并固定 `spring.profiles.include: app`。

### sync

外部账套导入。`POST /sync/import`（权限 `setting-sync-canview`）接收科目文件、期初文件、凭证文件。`soft` 参数对应 `SyncType`：

| soft | 实现 |
|---|---|
| 柠檬云 | `SyncNmyService` |
| 用友好会计 | `SyncHkjService` |
| 金蝶精斗云 | `SyncJdyService` |
| 金蝶账无忧 | `SyncKdZwyService` |

### bs-server

Web 与定时任务入口。`FinancialApplication` 开启异步与调度。按包划分：

| 包 | 职责 |
|---|---|
| `controller` | 账套、凭证、科目、出纳、工资、资产、报表、用户 |
| `controller.openapi` | 对外 `/openapi/v1/**`（开票、申报、账套、报表、出纳流水等） |
| `account` | 账套补全、期初余额导入 |
| `agent` | 期初导入工作流、电子税务局登录、工作台批处理 |
| `ai` | 通义千问填表、发票建议 |
| `task` / `timedTasks` / `job` | 任务工作台与定时任务 |
| `crm` / `ua` / `yingDao` / `rpa` | CRM、用户中心、影刀、RPA |
| `security` | 接口限流 |

## 环境与密码

配置按 profile 叠加，后加载的覆盖先加载的同名项。

| Profile | 文件 | 何时生效 |
|---|---|---|
| 基础 | `kernel/.../application.yml` | 始终加载，并 `include: app` |
| `app` | `bs-server/.../application-app.yml` | 随基础配置一起生效。端口 8012、邮件、Token、文件目录 |
| `dev` | `bs-server/.../application-dev.yml` | `--spring.profiles.active=app,dev`。改数据源和邮件，指向本机 |
| `prod` | `bs-server/.../application-prod.yml` | 只加大连接池、把 `cn.gson` 日志调到 info |
| `local` | `application-local.yml`（自行新建，不要提交） | 在 `app,dev,local` 上再覆盖个人账号 |

本地联调：

```bash
./gradlew :bs-server:bootRun --args="--spring.profiles.active=app,dev"
```

健康检查：`http://localhost:8012/actuator/health`（`dev` 下会展开 db / redis 明细）。

不叠加 `dev` 时，用的是 `application.yml` 里的远程库。

### 数据库

| 场景 | 地址 | 库 | 用户名 | 密码 | 配置位置 |
|---|---|---|---|---|---|
| 默认（含 `app`） | `150.139.141.142:3306` | `financial` | `qy` | `qykj@123` | `kernel/.../application.yml` |
| `dev` | `localhost:3306` | `financial` | `root` | `123` | `application-dev.yml` |

`dev` 的 JDBC 带 `createDatabaseIfNotExist=true`，本机第一次启动会建库。账号与默认值不一致时用环境变量，不必改已提交的 yml：

| 变量 | 默认 |
|---|---|
| `LOCAL_DB_HOST` | `localhost` |
| `LOCAL_DB_PORT` | `3306` |
| `LOCAL_DB_NAME` | `financial` |
| `LOCAL_DB_USER` | `root` |
| `LOCAL_DB_PASSWORD` | `123` |

PowerShell 示例：

```powershell
$env:LOCAL_DB_PASSWORD = "你的本机密码"
./gradlew :bs-server:bootRun --args="--spring.profiles.active=app,dev"
```

Flyway 当前在 `application.yml` 里关闭了 `validate-on-migrate`，并忽略 `missing` / `future`。这是为了本地版本号对不上时还能启动。连生产库之前要确认迁移历史，避免带着这组开关去改共享库结构。`ddl-auto` 为 `update`。

单独跑本地 Flyway（默认 `127.0.0.1:3306`、`root` / `123`，库名由任务默认值决定）可以用：

```bash
./gradlew :bs-server:runFlywayLocal
./gradlew :bs-server:repairFlyway
```

覆盖参数：`-PmysqlHost=`、`-PmysqlPort=`、`-PmysqlUser=`、`-PmysqlPassword=`、`-PmysqlDb=`。

### Redis

两套 profile 相同，无密码。

| 项 | 值 |
|---|---|
| 地址 | `redis://127.0.0.1:6379` |
| database | `2` |
| 缓存前缀 | 基础配置 `financial_v3_`；`app` profile 把前缀清空 |
| TTL | 30 分钟 |

本机需要先有 Redis。`dev` 不覆盖成嵌入式实例。

### 邮件 SMTP

| Profile | host | port | 用户名 | 密码 |
|---|---|---|---|---|
| `app` | `smtp.exmail.qq.com` | 465（SSL） | 空 | 空 |
| `dev` | `localhost`（可用 `SPRING_MAIL_HOST` 覆盖） | 1025（可用 `SPRING_MAIL_PORT` 覆盖） | 空 | 空 |

用户名或密码为空时不会真正发信。发件人显示名在 `app.mail.from-name`，当前为「财务系统通知」。

本地如何看信、如何填企业邮授权码，见 [bs-server/tools/local-dev-setup.md](bs-server/tools/local-dev-setup.md)。企业邮填的是客户端授权码，放在 `spring.mail.password`。

### 大模型与短信

通义千问（DashScope OpenAI 兼容接口）Key 写在两处，值相同，改一处要同时改另一处：

| 配置键 | 文件 | 当前值 |
|---|---|---|
| `agent.deepseek.api-key` | `kernel/.../application.yml` | `sk-417bca9e130f4f53998bc45e7fb42611` |
| `qwen.api-key` | `application-app.yml` | 同上 |

`agent.deepseek` 实际请求的是 DashScope：`https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions`，模型 `qwen-plus`。`ai.qwen.api-key` 引用 `qwen.api-key`；Key 为空时智能填表降级，不阻断开票。视觉模型 `qwen-vl-plus`。

阿里云短信在 `application.yml` 的 `app.aliyun`：`accessKeyId`、`accessKeySecret` 目前为空。签名「青艺科技」，模板 `SMS_175580136`（验证码）、`SMS_217436843`（注册）。要用短信再填 Key。

期初导入工作流地址：`agent.workflow.base-url` 为 `http://127.0.0.1:8000`（kernel），`agent.workflow.import-url` 为 `http://localhost:8081`（app）。

### 内部 Token 与登录

| 项 | 配置键 | 当前值 |
|---|---|---|
| 开票内部接口 | `financial.scan-direct.internal-token` | `financial-bt-internal-2026` |
| OpenClaw 回调 | `oc.callback.token` | 空（未填时按配置注释视为未约定） |
| Sa-Token 头名 | `sa-token.token-name` | `fv3token` |

开票 claim / result / tax-credentials 要求请求头 `X-Internal-Token` 等于 `internal-token`，否则 403。值为空时这些内部接口一律 403。

系统用户登录密码不在 yml 里，存在用户表，校验为 SHA-256 或 bcrypt（见 `UserService`）。

### 本机文件目录

`application-app.yml` 里的路径按当前 Windows 开发机写死。换机器时改成自己的目录，否则截图、Excel、发票文件会写到不存在的盘符。

| 配置键 | 当前路径 |
|---|---|
| `customize-config.base-path` | `D:\financialExcel` |
| `customize-config.screen-img-path` | `E:\qykj\file\imgs` |
| `customize-config.pay-img-path` | `E:\qykj\file\payImgs` |
| `customize-config.screen-bank-path` | `E:\qykj\file\financialBank` |
| `oc.screenshot-root` | `E:\qykj\file\oc-screenshots` |
| `oc.output-root` | `D:\tax_output` |
| `invoice-files.root` | `E:\qykj\file\invoice-files` |
| `oc.screenshot-base-url` / `invoice-files.base-url` | `http://150.139.141.142:8888/api` |
| `static-url.base-url` | `https://www.qingyicloud.com/` |

### 个人覆盖

在 `bs-server/src/main/resources/application-local.yml` 写只属于自己的账号（该文件按约定不进版本库），启动加 profile `local`：

```yaml
spring:
  datasource:
    username: root
    password: 你的本机密码
  mail:
    username: notify@example.com
    password: 邮箱授权码
qwen:
  api-key: 你的 DashScope Key
```

```bash
./gradlew :bs-server:bootRun --args="--spring.profiles.active=app,dev,local"
```

## 2026-11-25 17:07:56
- 例行仓库维护与状态更新
- 处理待回应的Issue与PR
