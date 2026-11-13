# LedgerCore HTTP API 合同草案

本文件中的银行业务路由仍是目标合同。P1 已提供实际 OpenAPI 端点和相关 ID；当前 OpenAPI 尚无业务路由。`GET /actuator/health` 与 `GET /v3/api-docs` 公开，其余请求需认证；身份模块完成前认证使用默认拒绝策略。安全拒绝和 MVC 异常返回 RFC 9457 Problem Details 并附 correlationId。

## 身份与账户

| 方法与路径 | 用途 |
|---|---|
| POST /api/auth/register | 创建客户身份；不接受客户 ID 或密码哈希 |
| POST /api/auth/login | 建立服务端会话 Cookie |
| POST /api/auth/logout | 使当前会话失效 |
| GET /api/auth/me | 获取当前客户展示信息 |
| GET /api/auth/csrf | 获取当前会话写请求所需的 CSRF token |
| GET /api/accounts | 只列出当前客户账户 |
| POST /api/accounts | 创建模拟账户并在同事务中写入模拟开户资金 |
| GET /api/accounts/{accountId} | 只读本人账户详情；无权和不存在统一 404 |
| GET /api/accounts/{accountId}/balance | 返回规范金额字符串、NZD 和投影更新时间 |
| GET /api/accounts/{accountId}/transactions?cursor=&limit= | 对本人账户按稳定游标分页 |

## 转账

POST /api/transfers 要求认证会话、有效 CSRF token 和 Idempotency-Key。请求示意：{sourceAccountId, targetAccountId, amount: "120.00"}。不接受 ownerCustomerId、ledger postings、客户端计算的余额或货币转换参数。

首次成功返回 Transfer ID、状态 POSTED、规范金额、币种、时间以及便于重复提交的稳定回执。余额不足为明确业务错误，不写入成功 Transfer。相同键相同请求返回原始回执；相同键不同请求返回 409。账户授权失败以 404 响应。未知网络结果是浏览器状态，不是服务端断言成功或失败。

GET /api/transfers/{transferId} 只返回已认证客户有权查看的转账。教学视图可以解释本人交易的借贷方向，但不暴露其他客户的身份、账户 ID 或可遍历账本。

## 格式与安全

- JSON 金额采用有两位小数的十进制字符串；超过精度、非法字符、零、负数和 long 溢出均拒绝。
- 单币种为 NZD。服务器不接受浏览器传来的 FX rate 或外部账户标识。
- API 由 Spring Security 服务端会话认证；修改请求验证 CSRF。
- 账户所有权在服务端每个资源入口及幂等回执重放时重新检查。
- 未认证返回 401；格式/金额错误返回 400；已认证但无权资源返回 404；键冲突返回 409；余额不足使用明确的业务错误映射。
- 关联请求使用 X-Correlation-ID；若客户端未提供或格式不合法，服务器产生新的安全 ID，并在响应与结构化日志中使用同一值。
