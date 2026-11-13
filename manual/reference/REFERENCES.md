# 参考资料与版本范围

本书的实现证据来自 LedgerCore 固定源码提交 484b9ad1c70ba052849ee1a40e15e35b6d888c12。框架解释使用项目声明的版本或相应版本线官方资料。链接访问日期：2026-11-13。

## Java 与 Spring

- Java 21 API：<https://docs.oracle.com/en/java/javase/21/docs/api/>。
- Spring Boot 4.0 Reference：<https://docs.spring.io/spring-boot/4.0/reference/>。项目使用 4.0.9；书中只陈述源码或运行观察到的配置。
- Spring Modulith 2.0 Reference：<https://docs.spring.io/spring-modulith/docs/2.0.x/reference/html/>。用于解释模块验证思路；本项目通过 ModuleArchitectureTests 是单独的项目证据。
- Spring Security CSRF：<https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html>。
- Spring Security 会话管理：<https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html>。
- Spring Data JPA：<https://docs.spring.io/spring-data/jpa/reference/>。项目使用 Hibernate 作为 JPA provider；账户实体的版本字段和写入路径见固定源码。

## PostgreSQL

- PostgreSQL 18 事务隔离：<https://www.postgresql.org/docs/18/transaction-iso.html>。
- PostgreSQL 18 显式锁：<https://www.postgresql.org/docs/18/explicit-locking.html>。
- PostgreSQL 18 延迟约束：<https://www.postgresql.org/docs/18/sql-set-constraints.html>。
- PostgreSQL 18 触发器：<https://www.postgresql.org/docs/18/trigger-definition.html>。
- PostgreSQL 18 EXPLAIN：<https://www.postgresql.org/docs/18/sql-explain.html> 与 <https://www.postgresql.org/docs/18/using-explain.html>。

## Web 与前端

- React 19 文档：<https://react.dev/learn>。
- TanStack Query v5 文档：<https://tanstack.com/query/v5/docs/framework/react/overview>。
- OpenAPI Specification 3.1：<https://spec.openapis.org/oas/v3.1.1.html>。
- Playwright 文档：<https://playwright.dev/docs/intro>。
- RFC 9457 Problem Details：<https://www.rfc-editor.org/rfc/rfc9457.html>。

## 版本边界

官方网站可能持续更新。本文引用稳定版本入口并注明主要版本线；任何与本项目行为有关的结论仍以固定源码、运行证据和测试为准。仓库提交历史、CI 成功和性能实验各自只支持其实际覆盖范围。
