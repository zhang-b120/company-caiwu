# financial 本地开发栈搭建（无 docker 版本，三期决策 2026-05-28）

> 目标：开发者一台 Windows / Linux / macOS 机器，本地已有 MySQL，**不需要 docker**，
> 即可起 financial bs-server 跑端到端联调。
>
> 邮件链路用 **GreenMail JVM 嵌入式 SMTP**（集成测试内启动），无需安装外部 SMTP 服务。

## 前置依赖

| 组件 | 来源 | 备注 |
|---|---|---|
| JDK 17 | Eclipse Temurin / Oracle | `java -version` 校验 |
| MySQL 8.0 | 本机已有 / 远程库 | 默认 127.0.0.1:3306，root/123；可改 |
| Redis | 本机已有（**可选**） | 生产 + dev profile 需要；集成测试在 application-test.yml 关掉 |
| Gradle 8.5 | 项目自带 wrapper | `./gradlew --version` |

**关键：本期不需要 docker / mailhog / mailtrap。** 邮件验证全部用集成测试内 GreenMail。

## 启动 financial bs-server（本地手动联调）

```bash
# 1. 编译
./gradlew :bs-server:bootJar

# 2. 启动（app profile = 生产配置；如果要 dev 邮件 host 改写，叠加 dev profile）
./gradlew :bs-server:bootRun --args='--spring.profiles.active=app'

# 3. 健康检查（如果开了 actuator）
curl http://localhost:8011/actuator/health
```

服务端口 8011。OpenAPI 全部 `@SaIgnore`，路径前缀 `/openapi/v1/invoice-task`。

## 真发邮件联调（3 种方案）

### 方案 1（推荐）：集成测试 + GreenMail 嵌入式 SMTP

最快验证。无需起任何外部进程，所有 SMTP 协议交互在 JVM 内完成；
测试代码直接读 GreenMail 收件箱 + 断言邮件内容。

```bash
./gradlew :bs-server:test --tests "*InvoiceTaskNotifyIntegration*"
```

跑通后 6 个 case 全过即说明 OpenAPI → InvoiceNotificationService → EmailService → JavaMailSender → SMTP → 邮件正文断言全链路。

实现见 [src/test/java/cn/gson/financial/integration/InvoiceTaskNotifyIntegrationTest.java](../src/test/java/cn/gson/financial/integration/InvoiceTaskNotifyIntegrationTest.java)（FN3-T2）。

### 方案 2：本机 Python smtpd 模拟（手测 + 看 SMTP 包）

如果想手动调 OpenAPI 端点看邮件被发出去，但不想引入第三方服务：

```bash
# 开一个终端跑 Python SMTP debug server（任何 Python 3.x 自带，无第三方包）
python -m smtpd -n -c DebuggingServer localhost:1025

# 另一个终端起 financial（dev profile 把 mail host 指到 1025）
./gradlew :bs-server:bootRun --args='--spring.profiles.active=app,dev'

# 创建一条任务 + 触发 notify
curl -X POST http://localhost:8011/openapi/v1/invoice-task -H "Content-Type: application/json" -d '{
  "accountSetsId": 1, "customerId": 1,
  "applicantEmail": "alice@example.com",
  "applicantName": "张三"
}'
# 假设返回 id=12345, 推到 SUCCESS 状态后：
curl -X POST http://localhost:8011/openapi/v1/invoice-task/12345/notify -H "Content-Type: application/json" -d '{}'
```

Python smtpd 终端会打印接收到的 SMTP 包（含完整 RFC 822 邮件正文）。

### 方案 3：真 SMTP（上线前最终验证）

在 `application-prod.yml`（或环境变量）填真 SMTP 凭证：

```yaml
spring:
  mail:
    host: smtp.qq.com         # 或阿里云 / 腾讯 / 网易企业邮箱
    port: 465
    username: notify@yourdomain.com
    password: ${SMTP_APP_PASSWORD}   # QQ 用 16 位授权码、非邮箱密码
    properties:
      mail:
        smtp:
          ssl:
            enable: true
            required: true
```

也可以用 mailtrap.io 免费档（host=sandbox.smtp.mailtrap.io, port=2525，邮件不真投递只进 mailtrap 收件箱）做受控环境验证。

## 触发通知的 3 种路径（与 invoice-print 对接关心的）

| 路径 | 谁触发 | 端点 |
|---|---|---|
| 主路径 | invoice-print 后端 SUCCESS 后主动调 | `POST /openapi/v1/invoice-task/{id}/notify` |
| 副路径 | financial 内部事件总线 | `TaskNotificationListener` 监听 `InvoiceTaskCompletedEvent` |
| 重发 | 运营手动 | `POST /openapi/v1/invoice-task/{id}/notify` body `{"force":true}` |

三条路径共用 `InvoiceNotificationService.notifyApplicant(taskId)`，避免重复实现。

## FAQ

**Q：为什么不用 docker / mailhog？**
A：三期决策（2026-05-28）：开发者机器异构（Win/Mac/Linux），docker 装机门槛 + 资源开销 + 网络代理问题大；
GreenMail 是纯 JVM 库，跟着 gradle test 自动跑，零额外配置。

**Q：怎么改 SMTP 凭证不污染 dev 环境？**
A：`application-app.yml` 是 dev/默认 profile，host 写真 SMTP；`application-prod.yml` 上线时填真凭证；
`application-test.yml` 是集成测试用，host 指 GreenMail 嵌入式 server。三个 profile 互不干扰。

**Q：生产 SMTP 怎么排错？**
A：开 `logging.level.org.springframework.mail=debug` 看 spring 拼装；
开 `mail.debug=true` 看 SMTP 协议交互；
失败抛 `EmailSendException`，message 含 SMTP error code。
