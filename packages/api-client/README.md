# @tmap/api-client

Typed TypeScript client for the TMap API. Types are generated from the backend's
OpenAPI 3.1 document with `openapi-typescript`; runtime calls use `openapi-fetch`.

## Regenerate after backend API changes

```bash
npm run gen --workspace @tmap/api-client
```

This builds `backend/src/Tmap.Api` (emitting `Tmap.Api.json` via
`Microsoft.Extensions.ApiDescription.Server`), copies it to `openapi.json`,
and regenerates `src/schema.d.ts`.

## Typecheck

```bash
npm run typecheck --workspace @tmap/api-client
```

## Usage

```ts
import { createTmapClient } from '@tmap/api-client';

const api = createTmapClient({
  baseUrl: 'https://api.tmap.app',
  getAccessToken: () => store.accessToken,
});

const { data, error } = await api.GET('/api/v1/tasks', {
  params: { query: { status: 'planned' } },
});
```
