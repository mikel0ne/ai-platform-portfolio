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

Login calls `POST /api/copilot/login` and keeps the signed demo token in `sessionStorage` next to the user;
`core/auth.interceptor.ts` sends it as `Authorization: Bearer`. On a 401 from the API (for instance after a backend
restart with a random secret) the session is cleared and the app goes back to the login. Real authentication is
out of scope.
