# compliance-dashboard-spa

Angular frontend for Pharos Compliance Operations. Provides the Batch View, Transaction View, and Report Config workspaces, with drill-down screens for batches and transaction evidence.

Companion backend repository: **`compliance-dashboard`**.

## Technology

- Angular, TypeScript, RxJS
- Node.js matching `.nvmrc`; npm with the committed `package-lock.json`

## Getting started

```bash
nvm use   # optional, if using nvm
npm ci
npm start
```

Open `http://localhost:4200`. The dev server proxies `/api`, `/dashboardDetails`, and `/actuator` to a backend running locally on `http://localhost:8085` (see `proxy.conf.json`).

## Build

```bash
npm run build
```

Deployable static assets are output to `dist/dashboard/browser/` — publish the contents of that directory, not the whole repository.

## Application routes

| Route | Screen |
| --- | --- |
| `/batches` | Batch View dashboard |
| `/batches/explorer` | Batch explorer and investigation |
| `/transaction-view` | Transaction View dashboard |
| `/transactions` | Transaction evidence list and details |
| `/report-config` | Report configuration workspace |

## Deployment

Built and deployed via GitLab CI/CD. See the deployment runbook for the current hosting model, API routing, and authentication (Okta OIDC) integration — those are environment-owned, not part of this repository.
