# Job Copilot V1 UI

Thin React client for the Job Copilot API. Workflow: resume upload → profile confirm → search preferences → resume routes → live opportunities → job detail / apply.

```powershell
npm install
npm run dev
```

The Vite dev server proxies `/api` to `http://localhost:8080`. Start the Spring Boot API first.

```powershell
npm test
npx playwright install chromium
npm run e2e
npm run build
```

`VITE_API_BASE_URL` is optional public configuration. Leave it empty for same-origin or proxy use. Never add secrets here.

Production packaging: copy `dist/` to the API host and serve it at the same origin over HTTPS. This workspace is unauthenticated and meant for private single-user use only.
