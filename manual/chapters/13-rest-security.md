# 第 13 章　REST、安全与账户所有权

有效登录只说明“请求代表某位客户”，不说明“这位客户可见任意账户”。每个业务查询还要把账户 ID 与服务端 principal 关联。若账户不存在与账户属于他人返回不同响应，攻击者就能用状态码探测资源，因此 LedgerCore 在读取路径对两者统一返回 404。

![认证、CSRF 与资源所有权分层](manual/figures/13-security-boundary.svg)

应用使用服务端会话 Cookie、登录后 Session ID 轮换、BCrypt strength 12 和 CSRF 写请求保护。Cookie 设置 HttpOnly、SameSite=Lax；Secure 标记受 LEDGERCORE_COOKIE_SECURE 控制，生产 HTTPS 应启用。CSRF 解决浏览器自动附带 Cookie 的跨站请求风险，不代替认证、授权或输入校验。

请求和响应遵循显式 HTTP 合同。未认证通常是 401，CSRF 错误是 403，格式错误是 400，余额不足为 422；权限隐藏为 404。错误体使用 Problem Details 和 correlationId。日志避免账户、金额和幂等键，异常消息也不原样泄露给浏览器。

安全边界是端到端的：前端按钮隐藏不能代替服务端检查；校验通过不代表所有权；有效 CSRF 不能代替权限；对账只读端点也需要账户授权。Playwright 的负向案例用另一位客户的账户 ID 调用详情路由，断言 404。

![本地实际运行的登录界面](manual/assets/screenshots/login.png)

登录页说明该应用只用于练习，并提供注册入口；它是身份交互的前端，不负责决定账户所有权。页面显示“已登录”仍不能授权读取他人的账户。

![本地实际运行的账户详情授权视图](manual/assets/screenshots/account-details.png)

已有 42 项 Maven 测试及浏览器流程为实现提供回归证据；依赖审计显示 0 个 high/critical，但仍有 4 个 moderate、2 个 low。这里没有外部渗透测试、合规认证或生产环境安全结论。

**审查题：** 为什么把客户 ID 放在请求 URL 中仍不够？为什么服务器必须用认证主体重新做授权判断？分别指出 CSRF token、session cookie 和账户所属关系解决的威胁。
