# ERGO Connect — Client Demo (frontend only)

Static React 19 + Vite + MUI demo of ERGO Connect. There is **no backend**: every screen is served from responses
recorded off the real, fully seeded system (`src/demo/fixtures`, one file per persona and role), so each of the
role cards on the login page opens a populated app. Changes you make in the demo (approve, create, comment) succeed
but are not stored.

## Run
```bash
npm install
npm run dev      # http://localhost:5173
npm run build    # type-check + production build into dist/
```

## Deploy (Vercel)
Import this repo, branch `demo`. Framework preset Vite is picked up from `vercel.json` (build `npm run build`,
output `dist`, SPA rewrite). No environment variables are needed.

## Notes
- Demo login/role switching: see `demoChanges.md`. Mock layer: `src/demo/mockBaseQuery.ts`, switched on in
  `src/services/baseApi.ts` (set `VITE_DEMO_MOCK=false` to talk to a real API again).
- Refreshing the data needs the full backend (not on this branch): `node scripts/capture-fixtures.mjs`.
- All 18 roles are recorded. Admin's 2FA code and the inactive `CUSTOMER_MANAGEMENT` role were handled on a local dev database only (nothing in the backend changed).
