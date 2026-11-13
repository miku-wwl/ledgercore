# LedgerCore Web

LedgerCore is a responsive NZD banking simulator for learning full-stack engineering and double-entry accounting. It handles simulated money only.

## Local development

Start the Spring Boot API on port 8080 with the environment variables documented in the repository root README, then run:

```powershell
npm ci
npm run dev
```

Vite serves the browser app on port 5173 and proxies `/api` requests to `http://127.0.0.1:8080`. Authentication uses the API's HttpOnly session cookie and Spring Security CSRF token.

## Validation

```powershell
npm run build
npm run lint
npm run test:e2e
```

Playwright runs Chromium against the real API and isolated PostgreSQL database. The main flow covers registration, accounts, transfers, insufficient funds, recovery after a committed response is dropped, history and journal details, reconciliation, cross-customer access, session logout/login and mobile width.

## Implementation notes

- API requests and responses use generated TypeScript types in `src/api/schema.d.ts`; regenerate them from a running local API with `npm run generate:api`.
- Currency parsing, comparisons and display inputs use integer cents (`bigint`), not JavaScript floating point.
- A transfer intent and its idempotency key are stored in the current tab before submission. Unknown results keep that intent for retry after refresh.
- TanStack Query caches server state; the browser never supplies the customer ID used for authorization.
