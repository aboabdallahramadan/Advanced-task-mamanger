# Tmap.Api — backend (SP1)

.NET 10 + PostgreSQL backend for TMap. Vertical Slice Architecture, minimal APIs, EF Core 10 (Npgsql, snake_case).

## Prerequisites

- .NET 10 SDK (`dotnet --version` → `10.x`)
- `dotnet-ef` global tool (`dotnet tool install --global dotnet-ef`)
- Docker Desktop (local Postgres + integration tests via Testcontainers)

## Local Postgres

From the repo root:

```bash
docker compose up -d postgres
```

This starts Postgres 16 on `localhost:5432` (db `tmap`, user `tmap`, password `tmap_dev_pw`).

## Configuration & secrets

The connection string and JWT signing key are NOT in `appsettings.json`. They live in
.NET user-secrets for local dev (and a real secret manager in production). Set them once:

```bash
dotnet user-secrets set "ConnectionStrings:Postgres" "Host=localhost;Port=5432;Database=tmap;Username=tmap;Password=tmap_dev_pw" --project src/Tmap.Api
dotnet user-secrets set "Jwt:SigningKey" "<a real >=256-bit base64 secret>" --project src/Tmap.Api
```

The JWT signing key must be >= 256 bits, identical across all instances, and stable across
restarts (a per-process random key invalidates tokens across instances/restarts).

## Run

```bash
dotnet run --project src/Tmap.Api
# Health check:
curl http://localhost:5000/health   # -> {"status":"ok"}
```

## Migrations (from P1 onward)

```bash
dotnet ef migrations add <Name> -p src/Tmap.Api -s src/Tmap.Api
dotnet ef database update -p src/Tmap.Api -s src/Tmap.Api
```

## Tests

```bash
dotnet test            # spins up a throwaway Postgres 16 via Testcontainers
```

Docker must be running. Tests share one container per `db` collection (`PostgresFixture`).
