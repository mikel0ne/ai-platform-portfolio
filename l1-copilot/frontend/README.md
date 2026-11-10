# L1 Support Copilot (frontend)

Angular 22 chat for the L1 support agent: simulated login with the demo users, chat with order/ticket cards
and confirmation buttons, ticket detail and printable return label.

## Development

```bash
npm ci
npm start            # ng serve on http://localhost:4199, /api proxied to the backend on :8079
```

## Build and tests

```bash
npm run build
npx ng test --watch=false --browsers=ChromeHeadless
```

The `X-User-Id` header added by `core/auth.interceptor.ts` stands in for a real session token; the backend
rejects unknown users with 401.
