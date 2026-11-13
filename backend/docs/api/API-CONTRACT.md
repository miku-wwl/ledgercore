# LedgerCore HTTP API 合同（P4 路由；P5 验证与错误处理）

下列银行业务路由已由 Spring MVC 实现，并通过 OpenAPI 与真实 PostgreSQL HTTP 集成测试验证。`GET /actuator/health`、`GET /v3/api-docs`、`GET /api/auth/csrf`、注册与登录可匿名访问；其他业务路由需要服务端会话。写请求都需要有效 CSRF token。

## 身份与会话

| 方法与路径 | 成功结果 | 访问规则 |
|---|---|---|
| GET /api/auth/csrf | `{ "token": "…" }` | 匿名；建立/读取当前 CSRF 会话 |
| POST /api/auth/register | 201，客户展示信息与会话 Cookie | 匿名；邮箱去空白并转小写；密码 12–128 字符；不接受客户 ID 或密码哈希 |
| POST /api/auth/login | 200，客户展示信息与会话 Cookie | 匿名；登录失败统一 401 |
| GET /api/auth/me | 当前客户 ID、邮箱和展示名 | 已认证 |
| POST /api/auth/logout | 204 | 已认证并验证 CSRF |

Cookie 使用 HttpOnly、SameSite=Lax；`LEDGERCORE_COOKIE_SECURE` 控制 Secure 标记，生产 HTTPS 环境应启用。登录与注册将认证写入服务端 SecurityContext，并轮换已有会话 ID。

## 账户、历史与对账

| 方法与路径 | 成功结果 | 访问规则 |
|---|---|---|
| GET /api/accounts | 当前客户账户列表，含状态、币种、余额和创建时间 | 已认证；只列本人账户 |
| POST /api/accounts | 201 与新账户表示；同一事务创建模拟开户资金 | 已认证并验证 CSRF |
| GET /api/accounts/{accountId} | 本人账户详情 | 已认证；无权与不存在统一 404 |
| GET /api/accounts/{accountId}/transactions?limit=20&cursor=… | 按创建时间与 UUID 稳定倒序的历史页 | 已认证且拥有账户；limit 限制在 1–100；cursor 为不透明 URL-safe 值 |
| GET /api/accounts/{accountId}/transactions/{transferId} | 本账户视角、金额、方向和安全的分录教学视图 | 已认证且拥有账户和交易；对方只显示 `Counterparty`，不暴露其账户 ID |
| GET /api/accounts/{accountId}/reconciliation | Posting 汇总、余额投影与 `matches` 结果 | 已认证且拥有账户；只读 |

账户 HTTP API 目前只提供列出、创建和查看。领域服务实现了暂停、恢复及关闭规则，但这些转换没有暴露为客户可调用的路由。

余额仍以规范 NZD 十进制字符串返回。账本 Posting 是权威数据；余额投影仅用于快速显示。未实现单独的 `/balance` 或 `/api/transfers/{transferId}` 路由。

## 转账

`POST /api/transfers` 要求认证会话、有效 CSRF token 和 `Idempotency-Key` 请求头。请求示意：

```json
{
  "sourceAccountId": "uuid",
  "targetAccountId": "uuid",
  "amount": "120.00"
}
```

成功返回 200 和持久化回执（transferId、源/目标账户 ID、POSTED 状态、规范金额、币种和时间）。不得提交 `ownerCustomerId`、Posting、余额或换汇参数。服务端从当前认证 principal 取得客户 ID；资金操作、投影更新与成功回执在一个 PostgreSQL 事务提交。

同键同意图返回原回执；同键不同意图返回 409。账户不存在、非本人账户或不可读取的转账均统一 404；账户未开放或键冲突为 409；余额不足为 422。客户端若提交后连接中断，应保留原请求与原键，并重试原意图以取得原结果。

## 格式与安全

- JSON 金额采用恰好两位小数的十进制字符串；不使用二进制浮点数。只接受正 NZD 金额。
- Spring Security 使用服务端会话 Cookie；Spring Security CSRF token 通过 `X-CSRF-TOKEN` 请求头发送。
- 未认证返回 401；CSRF 缺失或无效返回 403；校验失败返回 400；无权资源统一返回 404。
- 业务错误与 MVC 异常使用 RFC 9457 Problem Details，并包含 `correlationId`；身份/转账错误另含稳定 `code`。
- `X-Correlation-ID` 若缺失或格式不合规，服务器生成新的 ID，并在响应头和错误详情中使用该 ID。
- 所有权只从服务端认证主体派生，不信任请求体、query string 或浏览器缓存中的客户 ID。
