# P5 Security Review

## Scope

This is a source-and-test review of the local NZD simulation application. It records the controls and negative cases actually present in the repository. It is not an external penetration test, a production security certification, or evidence for real-money banking use.

## Reviewed boundaries

| Area | Observed control | Evidence and limit |
|---|---|---|
| Authentication | Passwords are encoded with BCrypt strength 12. Registration validates input, and registration/login rotate an existing session ID. Login failures use a uniform unauthorized response. | `SecurityConfiguration`, `IdentityService`, `AuthenticationController`, and `FullStackApiPostgresIntegrationTests`. No MFA, account recovery, or rate-limiting control is claimed. |
| Account ownership | Controllers take the customer ID from the authenticated principal. Services check ownership for account data, history, reconciliation, and transfer source accounts. Unauthorized account/history access returns the same 404 shape as a missing resource. | Full-stack PostgreSQL HTTP tests cover cross-customer denial. Idempotent transfer replay still checks source ownership before returning a stored receipt. |
| CSRF and session cookie | Spring Security CSRF protects writes; the frontend sends the session token in `X-CSRF-TOKEN`. Cookies are HttpOnly and SameSite=Lax. `LEDGERCORE_COOKIE_SECURE` defaults to true; local HTTP tests and the HTTP-only CI service explicitly set it false. | `SecurityConfiguration`, `application.properties`, `application-test.properties`, and the full-stack browser flow. An HTTPS deployment is not part of this local acceptance. |
| Request validation | JSON binding, UUID path parsing, bean validation, amount parsing, and the 1–128 visible-ASCII Idempotency-Key rule reject malformed inputs. Request-binding failures return generic 400 details; invalid cursor data is not echoed. | `ApiExceptionHandler`, `TransferService`, and negative HTTP integration cases. |
| Error disclosure | Business errors expose stable codes. Generic invalid arguments do not echo internal IDs or client values. Unexpected errors return a generic 500; the log event keeps correlation ID and exception type without the exception message or stack. | `ApiExceptionHandler` and full-stack HTTP tests. Framework and database logs outside this handler are not claimed to be fully redacted. |
| Sensitive logging | Transfer events contain `outcome`, `error.code`, elapsed time, and correlation ID. They omit account IDs, amounts, request bodies, and idempotency keys. `CustomerPrincipal.toString()` omits email and password hash. | `TransferController`, `CustomerPrincipal`, and `CustomerPrincipalTests`; Spring Boot Logstash structured console output is enabled. |
| Credential handling | Application database passwords are read from environment variables. README examples use placeholders. The GitHub Actions PostgreSQL password is a fixed test-only value for its disposable service container. | `application.properties`, `application-test.properties`, README examples, and `.github/workflows/ci.yml`. No real user or database credential is part of the reviewed configuration. |
| Retry authorization | The idempotency scope includes authenticated customer and transfer operation. The server canonicalizes the transfer fingerprint and checks source ownership before replay; same intent returns the stored receipt, while a different intent conflicts. | `TransferService`, PostgreSQL idempotency integration tests, and the Playwright lost-response/reload case. The browser must retain the original intent and key for safe recovery. |

## P5 changes from review

- Redacted the record-generated `CustomerPrincipal.toString()` output.
- Changed generic request and unexpected-error responses/logging to avoid echoing raw values or exception details.
- Made Secure cookies the application default, while keeping explicit HTTP-only overrides for tests and CI.
- Added structured transfer outcome, error classification, duration, and correlation fields without financial identifiers or values.

The 42-test PostgreSQL-backed Maven suite, package startup, generated OpenAPI type check, and Chromium flow passed locally. The verified source commit `484b9ad1c70ba052849ee1a40e15e35b6d888c12` also passed the GitHub `verify` and dependency-scan jobs in [run 43377985041](https://github.com/miku-wwl/ledgercore/actions/runs/43377985041).
