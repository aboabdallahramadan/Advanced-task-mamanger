# SP1 — Backend + Database + Email Auth — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the SP1 foundation — a .NET 10 + PostgreSQL backend with email/password auth and the full per-user, sync-ready TMap data model behind a versioned REST API, plus the monorepo skeleton — with no client behavior change (the existing desktop app only relocates).

**Architecture:** ASP.NET Core 10 minimal APIs in a Vertical Slice layout; EF Core 10 + Npgsql with snake_case mapping. Per-user isolation is defense-in-depth: Postgres Row-Level Security (the backstop) + EF Core 10 named query filters (Tenant + SoftDelete) + write-time ownership stamping. A single global `change_seq` sequence filled by a DB trigger, plus soft-delete tombstones and fractional `rank` ordering, make the schema offline-sync-ready (SP3) with no later migration. Auth is ASP.NET Core Identity (`IdentityUser<Guid>`) + custom JWT (short access token + rotating, reuse-detected refresh token).

**Tech Stack:** .NET 10 / C# 14 · ASP.NET Core minimal APIs · EF Core 10 · Npgsql · PostgreSQL 16 · ASP.NET Core Identity · FluentValidation · Serilog · JWT bearer · xUnit + Testcontainers + WebApplicationFactory · openapi-typescript + openapi-fetch · npm workspaces.

**Source spec:** `docs/superpowers/specs/2026-06-01-sp1-backend-auth-design.md` · **Roadmap:** `docs/superpowers/specs/2026-06-01-online-multiplatform-roadmap.md`

**Phase order & dependencies:** P0 (skeleton) → P1 (persistence core) → P2 (auth) → P3 (validation infra + Tasks exemplar) → P4 (Projects/NoteGroups/Notes) → P5 (Recurrence/FocusSessions/DailyPlans/Settings/Reports) → P6 (OpenAPI/CORS/client-gen) → **P7 (monorepo move — run last, after backend is green, so a broken move is isolated)**.

---

## Cross-phase corrections (authoritative — overrides any individual phase where they differ)

The phases below were authored in parallel against the shared contract. The assembly review found a few divergences; **these rules win** wherever a phase's code disagrees. Apply them as you implement.

1. **Rank generation is shared and consistent.** Promote P3's private `RankAfter` into a single public helper **`Tmap.Api.Common.Ranking`** with `static string RankAfter(string? prev)` and `static string RankBetween(string? prev, string? next)` (midpoint/append lexicographic scheme). Create it in P3 (the first slice that needs it) in `backend/src/Tmap.Api/Common/Ranking.cs`, and **every** create handler that assigns an order (Tasks, Projects, NoteGroups, Notes) uses it the same way:
   - `Rank` is **optional** on every `Create*Request`. When the client omits it, the server assigns `Ranking.RankAfter(maxRankForThatUserAndContainer)` (append to end). When the client supplies it, persist it verbatim.
   - Validators: `RuleFor(x => x.Rank).NotEmpty().MaximumLength(255).When(x => x.Rank is not null);` — **max length 255 everywhere** (P4's `MaximumLength(64)` is overridden to 255 to match the `varchar(255)` column).
   - `reorder` endpoints persist the client-provided `rank` verbatim (no server generation).
   So P4's "client must supply Rank on create" is changed to match P3's optional-with-server-append behavior.

2. **Subtasks use `SortOrder int`, never a rank string.** The `Subtask` entity has `SortOrder int` (per the contract). `CreateSubtaskRequest` takes `Title` only (server appends `SortOrder = max+1`); `UpdateSubtaskRequest` exposes `Title string?`, `Completed bool?`, `SortOrder int?`. Remove any `Rank` field from subtask DTOs and the opaque `int.TryParse(req.Rank, …)` mapping in P3 — use a real `int? SortOrder` field directly.

3. **Client-generated IDs are UUIDv7 and optional on create.** Every `Create*Request` may include a client-supplied `Id` (Guid, UUIDv7) for sync-readiness; when omitted the server generates one. This already holds for Tasks (P3); apply the same to Projects/NoteGroups/Notes/Recurrence/FocusSessions create handlers.

---

## Phase P0 — Monorepo skeleton + API bootstrap + test harness

This phase stands up the monorepo scaffolding **alongside** the existing Electron app (which stays at the repo root untouched until P7) and bootstraps the .NET 10 backend so every later phase has a place to land. It creates: `docker-compose.yml`, root `package.json` (npm workspaces), `.editorconfig`, `.gitignore` additions; `backend/Tmap.sln`; `backend/src/Tmap.Api/` (`Tmap.Api.csproj`, `Program.cs` ending in `public partial class Program {}`, `appsettings.json`, `appsettings.Development.json`, `Features/Health/HealthEndpoints.cs`, a placeholder `Infrastructure/AppDbContext.cs` + `Common/ICurrentUser.cs`/`CurrentUser.cs`/`SystemCurrentUser.cs` so the harness compiles and migrations can run); `backend/tests/Tmap.Api.Tests/` (`Tmap.Api.Tests.csproj`, `PostgresFixture.cs`, `DbCollection.cs`, `TmapApiFactory.cs`, `IntegrationTestBase.cs` + `AuthedClient`, `HealthTests.cs`); and a `packages/api-client/package.json` placeholder so the workspace resolves. It also documents the dev connection string + JWT key via `dotnet user-secrets` in `backend/README.md`.

> **Contract notes (gaps surfaced, not invented):**
> - This phase creates **minimal stubs** for `AppDbContext`, `ICurrentUser`/`CurrentUser`/`SystemCurrentUser`, and the entity base types so the solution compiles and the test harness can run `MigrateAsync()`. **Phase P1 owns the authoritative versions** (full canonical `SyncEntity`/`ISyncable`/`IOwnedByUser`, entities, RLS, triggers, query filters, interceptors). P0's stubs must be replaced/extended by P1 — they are intentionally empty-schema (`DbSet`s land in P1).
> - `IntegrationTestBase.RegisterAsync` / `AuthedClient` are defined here with the **exact contract signatures** but a `NotImplementedException` body; **Phase P2 fills the implementation** once the `/auth/register` endpoint exists. The shape (types, member names) is frozen here so P3+ slice tests written against it compile.
> - The first migration is created in **P1**; until then the harness calls `MigrateAsync()` against a DbContext with no migrations (a no-op), which is valid.

---

### Task P0-1: Repo prerequisites & .NET 10 SDK sanity check

- [ ] Confirm the .NET 10 SDK and EF tooling are available before writing any project files.
- [ ] Run: `dotnet --info` — expect a `10.0.x` SDK listed under "SDKs installed". If not present, STOP and install .NET 10 SDK; do not proceed.
- [ ] Run: `dotnet --version` — expect output starting with `10.`.
- [ ] Run: `dotnet tool install --global dotnet-ef` (if it prints "already installed", that is fine; otherwise expect "successfully installed"). Then `dotnet ef --version` — expect a `10.x` version.
- [ ] Run: `docker --version` — expect a Docker version string (Testcontainers needs a running Docker daemon). Run `docker info` — expect server info, not "Cannot connect to the Docker daemon".
- [ ] No code is written in this task. Commit nothing.

---

### Task P0-2: Monorepo root scaffolding (workspaces, editorconfig, gitignore, compose)

This task creates root-level files only. It must **not** modify the existing app's root `package.json` for TMap (which stays as the Electron app's manifest until P7). Because npm workspaces require a single root `package.json` and the existing app already owns the root one, P0 introduces the workspace manifest in a way that does not break the existing build: the existing root `package.json` gains a `"workspaces"` array pointing only at the new `packages/*` (and `backend` is .NET, not a JS workspace). The existing app config is otherwise untouched.

- [ ] Write `C:/Users/aboab/Desktop/Projects/sunsama clone/.editorconfig`:

```ini
root = true

[*]
charset = utf-8
end_of_line = lf
insert_final_newline = true
trim_trailing_whitespace = true
indent_style = space
indent_size = 2

[*.{cs,csproj,props,targets}]
indent_size = 4

# .NET formatting & analyzer rules
[*.cs]
dotnet_sort_system_directives_first = true
dotnet_style_namespace_match_folder = true
csharp_using_directive_placement = outside_namespace:warning
csharp_style_namespace_declarations = file_scoped:warning
dotnet_diagnostic.CA2007.severity = none
dotnet_style_prefer_conditional_expression_over_assignment = true:suggestion
csharp_prefer_braces = true:warning

[*.{json,yml,yaml}]
indent_size = 2

[Makefile]
indent_style = tab
```

- [ ] Write `C:/Users/aboab/Desktop/Projects/sunsama clone/docker-compose.yml`:

```yaml
services:
  postgres:
    image: postgres:16
    container_name: tmap-postgres
    environment:
      POSTGRES_USER: tmap
      POSTGRES_PASSWORD: tmap_dev_pw
      POSTGRES_DB: tmap
    ports:
      - '5432:5432'
    volumes:
      - tmap_pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ['CMD-SHELL', 'pg_isready -U tmap -d tmap']
      interval: 5s
      timeout: 5s
      retries: 10

volumes:
  tmap_pgdata:
```

- [ ] Create `C:/Users/aboab/Desktop/Projects/sunsama clone/packages/api-client/package.json` (placeholder so the workspace resolves; the real generated client lands in P6/the OpenAPI phase):

```json
{
  "name": "@tmap/api-client",
  "version": "0.0.0",
  "private": true,
  "description": "Generated OpenAPI types + openapi-fetch wrapper for the TMap API (populated in a later phase).",
  "type": "module",
  "main": "index.ts",
  "scripts": {
    "generate": "echo \"openapi-typescript generation wired in a later phase\" && exit 0"
  }
}
```

- [ ] Create `C:/Users/aboab/Desktop/Projects/sunsama clone/packages/api-client/index.ts` with a single placeholder line so the file exists:

```ts
export {};
```

- [ ] Read the existing root `package.json` first (required before edit), then add a top-level `"workspaces"` key (insert it directly after the `"main"` line). The exact text to add:

```json
  "workspaces": [
    "packages/*"
  ],
```

- [ ] Read the existing root `.gitignore`, then append the .NET / backend ignore block (do not remove existing entries):

```gitignore

# --- .NET backend (P0) ---
backend/**/bin/
backend/**/obj/
backend/**/*.user
backend/.vs/
backend/**/appsettings.*.local.json
```

- [ ] Verify the existing app is unaffected: run `npm install` at the repo root — expect it to succeed and create/refresh `node_modules` with the workspace symlink for `@tmap/api-client`. (No new runtime deps were added to the existing app.)
- [ ] Run `npm ls @tmap/api-client` — expect it to resolve the local workspace package at `packages/api-client`.
- [ ] Commit:
  - `git checkout -b sp1-p0-monorepo-skeleton` (do not work on `main`)
  - `git add .editorconfig docker-compose.yml packages/api-client package.json .gitignore`
  - Commit message:
    ```
    chore: scaffold monorepo root (workspaces, editorconfig, docker-compose)

    Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
    ```

---

### Task P0-3: Create the .NET solution and Tmap.Api web project (build green, no tests yet)

- [ ] Create the backend folder tree and solution. Run from the repo root:
  - `dotnet new sln -n Tmap -o backend`
  - `dotnet new web -n Tmap.Api -o backend/src/Tmap.Api --framework net10.0`
  - `dotnet sln backend/Tmap.sln add backend/src/Tmap.Api/Tmap.Api.csproj`
- [ ] Overwrite `C:/Users/aboab/Desktop/Projects/sunsama clone/backend/src/Tmap.Api/Tmap.Api.csproj` with the full project file (pins versions used across all phases; nullable + implicit usings on):

```xml
<Project Sdk="Microsoft.NET.Sdk.Web">

  <PropertyGroup>
    <TargetFramework>net10.0</TargetFramework>
    <LangVersion>14.0</LangVersion>
    <Nullable>enable</Nullable>
    <ImplicitUsings>enable</ImplicitUsings>
    <RootNamespace>Tmap.Api</RootNamespace>
    <AssemblyName>Tmap.Api</AssemblyName>
    <TreatWarningsAsErrors>false</TreatWarningsAsErrors>
    <GenerateDocumentationFile>true</GenerateDocumentationFile>
    <NoWarn>$(NoWarn);CS1591</NoWarn>
  </PropertyGroup>

  <ItemGroup>
    <PackageReference Include="Microsoft.EntityFrameworkCore" Version="10.0.0" />
    <PackageReference Include="Microsoft.EntityFrameworkCore.Design" Version="10.0.0">
      <PrivateAssets>all</PrivateAssets>
      <IncludeAssets>runtime; build; native; contentfiles; analyzers; buildtransitive</IncludeAssets>
    </PackageReference>
    <PackageReference Include="Npgsql.EntityFrameworkCore.PostgreSQL" Version="10.0.0" />
    <PackageReference Include="EFCore.NamingConventions" Version="10.0.0" />
    <PackageReference Include="Serilog.AspNetCore" Version="9.0.0" />
  </ItemGroup>

</Project>
```

> If exact `10.0.0` package versions are not yet published when this runs, use the latest stable `10.0.*` from `dotnet add package <id>` and record the resolved version in the commit body. Do not downgrade to a 9.x EF Core.

- [ ] Replace `C:/Users/aboab/Desktop/Projects/sunsama clone/backend/src/Tmap.Api/Program.cs` entirely with the bootstrap below. It wires Serilog, ProblemDetails, registers the placeholder `AppDbContext` + `ICurrentUser` (created in P0-4), maps `/health`, and ends with `public partial class Program {}` so tests can reference it:

```csharp
using Microsoft.EntityFrameworkCore;
using Serilog;
using Tmap.Api.Common;
using Tmap.Api.Features.Health;
using Tmap.Api.Infrastructure;

var builder = WebApplication.CreateBuilder(args);

builder.Host.UseSerilog(
    (context, services, configuration) =>
        configuration.ReadFrom.Configuration(context.Configuration).ReadFrom.Services(services)
);

builder.Services.AddProblemDetails();

// HTTP-scoped current-user accessor (reads the 'sub' claim). P1 hardens the fail-closed
// behavior; P2 makes auth actually populate the claim.
builder.Services.AddHttpContextAccessor();
builder.Services.AddScoped<ICurrentUser, CurrentUser>();

// AppDbContext is Scoped. Connection string comes from configuration / user-secrets in dev
// and is overridden by the test harness (P0-8). Placeholder context defined in P0-4; P1
// adds the real entities, RLS, triggers, and query filters.
builder.Services.AddDbContext<AppDbContext>(
    (sp, options) =>
    {
        var connectionString = builder.Configuration.GetConnectionString("Postgres");
        options.UseNpgsql(connectionString).UseSnakeCaseNamingConvention();
    }
);

var app = builder.Build();

app.UseSerilogRequestLogging();
app.UseExceptionHandler();
app.UseStatusCodePages();

app.MapHealthEndpoints();

app.Run();

// Required so WebApplicationFactory<Program> in the test project can reference the entry point.
public partial class Program { }
```

- [ ] Create the appsettings files. Write `C:/Users/aboab/Desktop/Projects/sunsama clone/backend/src/Tmap.Api/appsettings.json`:

```json
{
  "ConnectionStrings": {
    "Postgres": ""
  },
  "Serilog": {
    "MinimumLevel": {
      "Default": "Information",
      "Override": {
        "Microsoft.AspNetCore": "Warning",
        "Microsoft.EntityFrameworkCore": "Warning"
      }
    },
    "WriteTo": [{ "Name": "Console" }],
    "Enrich": ["FromLogContext"]
  },
  "AllowedHosts": "*"
}
```

- [ ] Write `C:/Users/aboab/Desktop/Projects/sunsama clone/backend/src/Tmap.Api/appsettings.Development.json` (no secrets here — the connection string + JWT key come from user-secrets, documented in P0-9):

```json
{
  "Serilog": {
    "MinimumLevel": {
      "Default": "Debug",
      "Override": {
        "Microsoft.AspNetCore": "Information"
      }
    }
  }
}
```

- [ ] Do NOT build yet — `Program.cs` references `AppDbContext`, `ICurrentUser`, `CurrentUser`, and `MapHealthEndpoints` which are created in P0-4 and P0-5. Proceed to those tasks, then build in P0-6.

---

### Task P0-4: Minimal placeholder Infrastructure + Common (so the solution compiles; P1 replaces)

These are intentionally minimal: just enough for `Program.cs` to compile and for `MigrateAsync()` to run a no-op. **P1 owns the real versions** (canonical `SyncEntity`, entities, RLS, triggers, named filters, ownership/connection interceptors).

- [ ] Create `C:/Users/aboab/Desktop/Projects/sunsama clone/backend/src/Tmap.Api/Common/ICurrentUser.cs` (the canonical contract interface — copied verbatim from the shared contract):

```csharp
namespace Tmap.Api.Common;

public interface ICurrentUser
{
    bool IsAuthenticated { get; }

    /// <summary>The authenticated user's id. THROWS if !IsAuthenticated (fail-closed).</summary>
    Guid Id { get; }
}
```

- [ ] Create `C:/Users/aboab/Desktop/Projects/sunsama clone/backend/src/Tmap.Api/Common/CurrentUser.cs` (HTTP impl reading the `sub` claim; fail-closed `Id`):

```csharp
using System.Security.Claims;
using Microsoft.AspNetCore.Http;

namespace Tmap.Api.Common;

public sealed class CurrentUser(IHttpContextAccessor httpContextAccessor) : ICurrentUser
{
    private ClaimsPrincipal? Principal => httpContextAccessor.HttpContext?.User;

    public bool IsAuthenticated => TryGetId(out _);

    public Guid Id =>
        TryGetId(out var id)
            ? id
            : throw new InvalidOperationException("No authenticated user on the current request.");

    private bool TryGetId(out Guid id)
    {
        id = Guid.Empty;
        var sub =
            Principal?.FindFirstValue(ClaimTypes.NameIdentifier)
            ?? Principal?.FindFirstValue("sub");
        return sub is not null && Guid.TryParse(sub, out id);
    }
}
```

- [ ] Create `C:/Users/aboab/Desktop/Projects/sunsama clone/backend/src/Tmap.Api/Common/SystemCurrentUser.cs` (elevated context used by migrations/seed/sync/tests; not HTTP-bound):

```csharp
namespace Tmap.Api.Common;

/// <summary>
/// Non-HTTP <see cref="ICurrentUser"/> for migrations, seeding, sync, and test arrange/assert.
/// Constructed with an explicit user id (or <see cref="Guid.Empty"/> for system-only work).
/// </summary>
public sealed class SystemCurrentUser(Guid userId) : ICurrentUser
{
    public bool IsAuthenticated => userId != Guid.Empty;

    public Guid Id =>
        userId != Guid.Empty
            ? userId
            : throw new InvalidOperationException("SystemCurrentUser has no user id set.");
}
```

- [ ] Create `C:/Users/aboab/Desktop/Projects/sunsama clone/backend/src/Tmap.Api/Infrastructure/AppDbContext.cs` (placeholder — no `DbSet`s yet; P1 adds entities, filters, and interceptor wiring):

```csharp
using Microsoft.EntityFrameworkCore;
using Tmap.Api.Common;

namespace Tmap.Api.Infrastructure;

/// <summary>
/// Application database context. Scoped lifetime. Placeholder in P0 (no entities yet);
/// P1 adds Identity, the synced entities, RLS, the change_seq trigger, named query filters,
/// and the ownership/connection interceptors. Do not add domain logic here in P0.
/// </summary>
public class AppDbContext(DbContextOptions<AppDbContext> options, ICurrentUser currentUser)
    : DbContext(options)
{
    // Retained so P1's tenant filter and ownership interceptor can read the current user at
    // query/save time. Suppress the unused warning until P1 consumes it.
#pragma warning disable IDE0052
    private readonly ICurrentUser _currentUser = currentUser;
#pragma warning restore IDE0052

    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        base.OnModelCreating(modelBuilder);
        // Entities, conversions, filters, and indexes are configured in P1.
    }
}
```

- [ ] Do NOT build yet — proceed to P0-5 (health endpoint), then build in P0-6.

---

### Task P0-5: Health endpoint feature slice

- [ ] Create `C:/Users/aboab/Desktop/Projects/sunsama clone/backend/src/Tmap.Api/Features/Health/HealthEndpoints.cs`:

```csharp
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;

namespace Tmap.Api.Features.Health;

public static class HealthEndpoints
{
    public static IEndpointRouteBuilder MapHealthEndpoints(this IEndpointRouteBuilder app)
    {
        // Liveness probe. Anonymous on purpose. Not under /api/v1 — it is an ops endpoint.
        app.MapGet("/health", () => TypedResults.Ok(new HealthResponse("ok")))
            .WithName("Health")
            .AllowAnonymous();

        return app;
    }
}

public sealed record HealthResponse(string Status);
```

- [ ] Proceed to P0-6 to build.

---

### Task P0-6: First build — solution compiles green

- [ ] Run: `dotnet build backend/Tmap.sln`
- [ ] Expect: `Build succeeded` with `0 Error(s)`. (Analyzer warnings from `.editorconfig` are acceptable; errors are not.)
- [ ] If it fails with `error CS0246: The type or namespace name 'AppDbContext'/'ICurrentUser'/'CurrentUser' could not be found`, the file from P0-4 is missing or the namespace is wrong — fix and rebuild before continuing.
- [ ] If it fails with package-restore errors on the `10.0.0` versions, run `dotnet add backend/src/Tmap.Api/Tmap.Api.csproj package <id>` for each to pull the latest stable `10.0.*`, then rebuild.
- [ ] Commit:
  - `git add backend/Tmap.sln backend/src/Tmap.Api`
  - Commit message:
    ```
    feat(api): bootstrap Tmap.Api web project with Serilog and /health

    Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
    ```

---

### Task P0-7: Create the test project and the failing health test (RED)

- [ ] Create the xUnit test project and add it to the solution. Run from the repo root:
  - `dotnet new xunit -n Tmap.Api.Tests -o backend/tests/Tmap.Api.Tests --framework net10.0`
  - `dotnet sln backend/Tmap.sln add backend/tests/Tmap.Api.Tests/Tmap.Api.Tests.csproj`
  - `dotnet add backend/tests/Tmap.Api.Tests/Tmap.Api.Tests.csproj reference backend/src/Tmap.Api/Tmap.Api.csproj`
- [ ] Overwrite `C:/Users/aboab/Desktop/Projects/sunsama clone/backend/tests/Tmap.Api.Tests/Tmap.Api.Tests.csproj` with the full file (pins the test stack: Mvc.Testing, Testcontainers.PostgreSql, FluentAssertions, and EF Core for the elevated context):

```xml
<Project Sdk="Microsoft.NET.Sdk">

  <PropertyGroup>
    <TargetFramework>net10.0</TargetFramework>
    <LangVersion>14.0</LangVersion>
    <Nullable>enable</Nullable>
    <ImplicitUsings>enable</ImplicitUsings>
    <RootNamespace>Tmap.Api.Tests</RootNamespace>
    <IsPackable>false</IsPackable>
    <IsTestProject>true</IsTestProject>
  </PropertyGroup>

  <ItemGroup>
    <PackageReference Include="Microsoft.NET.Test.Sdk" Version="17.12.0" />
    <PackageReference Include="xunit" Version="2.9.2" />
    <PackageReference Include="xunit.runner.visualstudio" Version="2.8.2">
      <PrivateAssets>all</PrivateAssets>
      <IncludeAssets>runtime; build; native; contentfiles; analyzers; buildtransitive</IncludeAssets>
    </PackageReference>
    <PackageReference Include="Microsoft.AspNetCore.Mvc.Testing" Version="10.0.0" />
    <PackageReference Include="Testcontainers.PostgreSql" Version="4.0.0" />
    <PackageReference Include="FluentAssertions" Version="7.0.0" />
    <PackageReference Include="Microsoft.EntityFrameworkCore" Version="10.0.0" />
    <PackageReference Include="Npgsql.EntityFrameworkCore.PostgreSQL" Version="10.0.0" />
    <PackageReference Include="EFCore.NamingConventions" Version="10.0.0" />
  </ItemGroup>

  <ItemGroup>
    <ProjectReference Include="..\..\src\Tmap.Api\Tmap.Api.csproj" />
  </ItemGroup>

</Project>
```

> Use the latest stable versions resolvable by `dotnet add package` if a pinned version above is unavailable; keep the test SDK and the `10.0.*` ASP.NET/EF packages aligned with the API project. Record resolved versions in the commit body.

- [ ] Delete the template `UnitTest1.cs` so it does not interfere: remove `C:/Users/aboab/Desktop/Projects/sunsama clone/backend/tests/Tmap.Api.Tests/UnitTest1.cs`.
- [ ] Write the failing health test FIRST (the harness files it references — `PostgresFixture`, `DbCollection`, `IntegrationTestBase` — are created in P0-8). Create `C:/Users/aboab/Desktop/Projects/sunsama clone/backend/tests/Tmap.Api.Tests/HealthTests.cs`:

```csharp
using System.Net;
using System.Net.Http.Json;
using FluentAssertions;
using Xunit;

namespace Tmap.Api.Tests;

[Collection(DbCollection.Name)]
public sealed class HealthTests(PostgresFixture fixture) : IntegrationTestBase(fixture)
{
    [Fact]
    public async Task Get_Health_Returns200_WithOkStatus()
    {
        var response = await Client.GetAsync("/health");

        response.StatusCode.Should().Be(HttpStatusCode.OK);

        var body = await response.Content.ReadFromJsonAsync<HealthResponseDto>();
        body.Should().NotBeNull();
        body!.Status.Should().Be("ok");
    }

    private sealed record HealthResponseDto(string Status);
}
```

- [ ] Run: `dotnet build backend/tests/Tmap.Api.Tests/Tmap.Api.Tests.csproj`
- [ ] Expect FAIL with errors like `error CS0246: The type or namespace name 'PostgresFixture' could not be found` and `'DbCollection'`/`'IntegrationTestBase' could not be found`. This is the RED state — the harness does not exist yet. Proceed to P0-8.

---

### Task P0-8: Build the test harness — PostgresFixture, DbCollection, TmapApiFactory, IntegrationTestBase, AuthedClient (GREEN)

This task creates the exact harness surface from the shared contract. After it, the health test from P0-7 passes.

- [ ] Create `C:/Users/aboab/Desktop/Projects/sunsama clone/backend/tests/Tmap.Api.Tests/PostgresFixture.cs` — one Testcontainers Postgres 16 container shared across the `db` collection:

```csharp
using Testcontainers.PostgreSql;
using Xunit;

namespace Tmap.Api.Tests;

public sealed class PostgresFixture : IAsyncLifetime
{
    private readonly PostgreSqlContainer _container = new PostgreSqlBuilder()
        .WithImage("postgres:16")
        .WithDatabase("tmap_test")
        .WithUsername("tmap")
        .WithPassword("tmap_test_pw")
        .Build();

    /// <summary>Connection string for the running container, with Npgsql multiplexing off so
    /// RLS <c>SET LOCAL app.user_id</c> (added in P1) stays pinned to the request connection.</summary>
    public string ConnectionString { get; private set; } = string.Empty;

    public async Task InitializeAsync()
    {
        await _container.StartAsync();
        ConnectionString = _container.GetConnectionString();
    }

    public async Task DisposeAsync()
    {
        await _container.DisposeAsync();
    }
}
```

- [ ] Create `C:/Users/aboab/Desktop/Projects/sunsama clone/backend/tests/Tmap.Api.Tests/DbCollection.cs` — the `[CollectionDefinition("db")]` so all slice tests share the single container:

```csharp
using Xunit;

namespace Tmap.Api.Tests;

[CollectionDefinition(Name)]
public sealed class DbCollection : ICollectionFixture<PostgresFixture>
{
    public const string Name = "db";
}
```

- [ ] Create `C:/Users/aboab/Desktop/Projects/sunsama clone/backend/tests/Tmap.Api.Tests/TmapApiFactory.cs` — the `WebApplicationFactory<Program>` that points the API at the fixture's container and runs migrations on startup:

```csharp
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Tmap.Api.Infrastructure;

namespace Tmap.Api.Tests;

public sealed class TmapApiFactory(string connectionString) : WebApplicationFactory<Program>
{
    protected override void ConfigureWebHost(IWebHostBuilder builder)
    {
        builder.UseEnvironment("Testing");

        builder.ConfigureAppConfiguration(
            (_, config) =>
            {
                config.AddInMemoryCollection(
                    new Dictionary<string, string?>
                    {
                        ["ConnectionStrings:Postgres"] = connectionString,
                    }
                );
            }
        );

        builder.ConfigureServices(services =>
        {
            // Apply migrations against the Testcontainers database on startup.
            // In P0 there are no migrations yet, so this is a no-op; P1 adds the first migration.
            using var scope = services.BuildServiceProvider().CreateScope();
            var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();
            db.Database.Migrate();
        });
    }
}
```

> Note for P1: once the first migration exists, `Migrate()` here creates the full schema (and runs the raw-SQL trigger/RLS migration). The factory body does not change; only migrations are added.

- [ ] Create `C:/Users/aboab/Desktop/Projects/sunsama clone/backend/tests/Tmap.Api.Tests/IntegrationTestBase.cs` — the exact base surface from the contract (`Client`, `RegisterAsync` → `AuthedClient`, `NewElevatedDbContext`). `RegisterAsync` carries the frozen signature with a `NotImplementedException` body that **P2 fills** once `/auth/register` exists:

```csharp
using Microsoft.EntityFrameworkCore;
using Tmap.Api.Common;
using Tmap.Api.Infrastructure;
using Xunit;

namespace Tmap.Api.Tests;

/// <summary>
/// Base class for slice integration tests. Lives in the "db" collection so it shares the
/// single Testcontainers Postgres instance. Owns one <see cref="TmapApiFactory"/> per test
/// class instance (xUnit creates a fresh instance per test method).
/// </summary>
[Collection(DbCollection.Name)]
public abstract class IntegrationTestBase : IAsyncLifetime
{
    private readonly PostgresFixture _fixture;
    private TmapApiFactory _factory = null!;

    protected IntegrationTestBase(PostgresFixture fixture)
    {
        _fixture = fixture;
    }

    /// <summary>Unauthenticated HTTP client against the in-memory test server.</summary>
    protected HttpClient Client { get; private set; } = null!;

    public Task InitializeAsync()
    {
        _factory = new TmapApiFactory(_fixture.ConnectionString);
        Client = _factory.CreateClient();
        return Task.CompletedTask;
    }

    public Task DisposeAsync()
    {
        Client.Dispose();
        _factory.Dispose();
        return Task.CompletedTask;
    }

    /// <summary>
    /// Registers a new user and returns an <see cref="AuthedClient"/> whose
    /// <see cref="AuthedClient.Client"/> carries the Bearer access token and whose
    /// <see cref="AuthedClient.UserId"/> is the new user's id.
    /// CONTRACT SIGNATURE — frozen in P0; implemented in P2 once POST /api/v1/auth/register exists.
    /// </summary>
    protected Task<AuthedClient> RegisterAsync(
        string? email = null,
        string password = "Password123!x"
    )
    {
        throw new NotImplementedException(
            "RegisterAsync is implemented in Phase P2 once POST /api/v1/auth/register exists."
        );
    }

    /// <summary>
    /// A DbContext bound to an elevated <see cref="SystemCurrentUser"/> for arrange/assert in
    /// tests. Query filters (added in P1) are available; the caller passes the owning user id so
    /// the tenant filter resolves. Caller disposes the returned context.
    /// </summary>
    protected AppDbContext NewElevatedDbContext(Guid? userId = null)
    {
        var options = new DbContextOptionsBuilder<AppDbContext>()
            .UseNpgsql(_fixture.ConnectionString)
            .UseSnakeCaseNamingConvention()
            .Options;

        return new AppDbContext(options, new SystemCurrentUser(userId ?? Guid.Empty));
    }
}

/// <summary>
/// An authenticated client bundle: an <see cref="HttpClient"/> with the Bearer access token
/// already attached, plus the registered user's id. CONTRACT TYPE — shape frozen in P0.
/// </summary>
public sealed record AuthedClient(HttpClient Client, Guid UserId);
```

- [ ] Make the test project see `internal` types of the API if needed later (forward-compat; harmless now). Add to `C:/Users/aboab/Desktop/Projects/sunsama clone/backend/src/Tmap.Api/Tmap.Api.csproj` inside a new `<ItemGroup>` (so `RefreshToken` and other `internal` infrastructure types are testable in P2):

```xml
  <ItemGroup>
    <InternalsVisibleTo Include="Tmap.Api.Tests" />
  </ItemGroup>
```

- [ ] Ensure Docker Desktop is running (Testcontainers needs it). Verify with `docker info` — expect server info.
- [ ] Run the health test only: `dotnet test backend/tests/Tmap.Api.Tests/Tmap.Api.Tests.csproj --filter "FullyQualifiedName~HealthTests.Get_Health_Returns200_WithOkStatus"`
- [ ] Expect PASS: `Passed!  - Failed: 0, Passed: 1`. The test spins up the Postgres container, the factory runs `Migrate()` (no-op), the in-memory server answers `GET /health` with `200 {"status":"ok"}`.
- [ ] If it fails with a Testcontainers/Docker connection error, confirm Docker is running and re-run. If it fails because `Migrate()` throws (e.g. provider mismatch), confirm the API's `appsettings.json` has an empty `Postgres` connection string (so the in-memory test config override wins) and that `UseNpgsql` + `UseSnakeCaseNamingConvention` match the factory.
- [ ] Commit:
  - `git add backend/tests/Tmap.Api.Tests backend/src/Tmap.Api/Tmap.Api.csproj`
  - Commit message:
    ```
    test(api): add Testcontainers harness and green GET /health integration test

    PostgresFixture + DbCollection + TmapApiFactory<Program> + IntegrationTestBase
    (Client, RegisterAsync stub frozen for P2, NewElevatedDbContext) and AuthedClient.

    Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
    ```

---

### Task P0-9: Full-solution build + test + dev connection-string / secrets documentation

- [ ] Run the full solution build: `dotnet build backend/Tmap.sln` — expect `Build succeeded`, `0 Error(s)`.
- [ ] Run the full test suite: `dotnet test backend/Tmap.sln` — expect `Passed!` with `Passed: 1, Failed: 0` (only the health test exists). This is the phase's green gate.
- [ ] Initialize user-secrets for the API project so the dev connection string + JWT key (used by later phases) are never committed. Run:
  - `dotnet user-secrets init --project backend/src/Tmap.Api`
  - `dotnet user-secrets set "ConnectionStrings:Postgres" "Host=localhost;Port=5432;Database=tmap;Username=tmap;Password=tmap_dev_pw" --project backend/src/Tmap.Api`
  - (JWT key placeholder so P2 can rely on it being present; a real ≥256-bit key — P2/P5 set the production value): `dotnet user-secrets set "Jwt:SigningKey" "dev-only-change-me-0123456789abcdef0123456789abcdef" --project backend/src/Tmap.Api`
- [ ] Verify secrets are set: `dotnet user-secrets list --project backend/src/Tmap.Api` — expect the three keys listed. Confirm `<UserSecretsId>` was added to `Tmap.Api.csproj` by `user-secrets init` (do NOT commit any secret values — only the `UserSecretsId` element is committed).
- [ ] Write `C:/Users/aboab/Desktop/Projects/sunsama clone/backend/README.md` documenting local dev:

```markdown
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
```

- [ ] Sanity-check the dev run path manually (optional but recommended): with `docker compose up -d postgres` running, `dotnet run --project backend/src/Tmap.Api`, then in another shell `curl http://localhost:5000/health` — expect `{"status":"ok"}`. Stop the app (Ctrl+C).
- [ ] Commit:
  - `git add backend/README.md backend/src/Tmap.Api/Tmap.Api.csproj`
  - Commit message:
    ```
    chore: document dev connection string + JWT key via user-secrets; backend README

    Adds UserSecretsId to Tmap.Api.csproj (no secret values committed) and a backend
    README covering docker-compose Postgres, user-secrets, run, migrations, and tests.

    Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
    ```

---

### Task P0-10: Phase exit verification (definition of done for P0)

- [ ] `dotnet build backend/Tmap.sln` → `Build succeeded`, `0 Error(s)`.
- [ ] `dotnet test backend/Tmap.sln` → `Passed: 1, Failed: 0` (the health test is green; container starts, migrations no-op, `/health` returns `200 {"status":"ok"}`).
- [ ] `Program.cs` ends with `public partial class Program { }` (verified by the fact that `WebApplicationFactory<Program>` compiles and runs).
- [ ] The harness surface matches the contract exactly: `IntegrationTestBase` exposes `HttpClient Client`, `Task<AuthedClient> RegisterAsync(string? email = null, string password = "Password123!x")` (stub, P2 fills), and `AppDbContext NewElevatedDbContext(...)`; `AuthedClient` is a record with `HttpClient Client` + `Guid UserId`.
- [ ] The existing Electron app is **untouched**: `git status` shows no changes under `src/`, `electron/`, `vite.config.ts`, etc. — only root scaffolding files and the new `backend/` + `packages/` trees. Confirm the existing app still builds: `npm run build` at the repo root → succeeds.
- [ ] `git log --oneline` shows the four P0 commits on branch `sp1-p0-monorepo-skeleton`. Do NOT push or open a PR unless explicitly asked.
- [ ] Hand-off note for P1: replace the placeholder `AppDbContext`/`SyncEntity`/entity stubs with the canonical contract versions, add the first EF migration (entities + raw-SQL `global_change_seq`/`bump_change_seq` trigger + RLS), wire the connection + ownership + SaveChanges interceptors, and add the named `Tenant`/`SoftDelete` query filters. The factory's `Migrate()` call already runs migrations, so P1's first migration will create the full schema with no harness change.
```

Files this phase creates/modifies (all absolute paths):
- `C:/Users/aboab/Desktop/Projects/sunsama clone/.editorconfig` (new)
- `C:/Users/aboab/Desktop/Projects/sunsama clone/docker-compose.yml` (new)
- `C:/Users/aboab/Desktop/Projects/sunsama clone/.gitignore` (modified — append backend ignores)
- `C:/Users/aboab/Desktop/Projects/sunsama clone/package.json` (modified — add `workspaces`)
- `C:/Users/aboab/Desktop/Projects/sunsama clone/packages/api-client/package.json` + `index.ts` (new placeholders)
- `C:/Users/aboab/Desktop/Projects/sunsama clone/backend/Tmap.sln` (new)
- `C:/Users/aboab/Desktop/Projects/sunsama clone/backend/src/Tmap.Api/` — `Tmap.Api.csproj`, `Program.cs`, `appsettings.json`, `appsettings.Development.json`, `Common/ICurrentUser.cs`, `Common/CurrentUser.cs`, `Common/SystemCurrentUser.cs`, `Infrastructure/AppDbContext.cs`, `Features/Health/HealthEndpoints.cs` (all new)
- `C:/Users/aboab/Desktop/Projects/sunsama clone/backend/tests/Tmap.Api.Tests/` — `Tmap.Api.Tests.csproj`, `PostgresFixture.cs`, `DbCollection.cs`, `TmapApiFactory.cs`, `IntegrationTestBase.cs`, `HealthTests.cs` (all new; template `UnitTest1.cs` deleted)
- `C:/Users/aboab/Desktop/Projects/sunsama clone/backend/README.md` (new)

Key contract gaps surfaced (for the plan author to reconcile across phases):
1. P0 ships **minimal stubs** for `AppDbContext`, `ICurrentUser`/`CurrentUser`/`SystemCurrentUser` — P1 owns the canonical entity/RLS/trigger/filter/interceptor versions and must replace/extend them.
2. `IntegrationTestBase.RegisterAsync`/`AuthedClient` are frozen-signature stubs (`NotImplementedException`); **P2 must fill `RegisterAsync`** once `/auth/register` exists.
3. The first EF migration is a **P1** deliverable; P0's `TmapApiFactory.Migrate()` is a no-op until then (documented inline).
4. The existing Electron app currently owns the repo-root `package.json`; P0 adds only a `workspaces` array pointing at `packages/*` and does not relocate the app (that is P7). If the plan prefers a dedicated root workspace manifest separate from the Electron app manifest, that is a structural decision to confirm — P0 chose the non-invasive path to honor "do not touch the current app here."

---

## Phase P1 — Persistence core: AppDbContext, entities, change_seq trigger, RLS, isolation

This phase builds the entire persistence foundation that every later slice depends on. It creates the canonical shared abstractions (`Common/IOwnedByUser.cs`, `Common/ISyncable.cs`, `Common/SyncEntity.cs`, `Common/ICurrentUser.cs`, `Common/CurrentUser.cs`, `Common/SystemCurrentUser.cs`), every domain entity (`Infrastructure/Entities/Enums.cs`, `TaskItem.cs`, `Subtask.cs`, `Project.cs`, `NoteGroup.cs`, `Note.cs`, `RecurrenceRule.cs`, `RecurrenceException.cs`, `FocusSession.cs`, `DailyPlan.cs`, `UserSetting.cs`, `ApplicationUser.cs`, `RefreshToken.cs`), the EF Core `Infrastructure/AppDbContext.cs` with snake_case naming, jsonb columns, enum-as-text conversions, composite keys, FKs, partial unique indexes, named query filters (`Tenant` + `SoftDelete`), and `change_seq` mapped `ValueGeneratedOnAddOrUpdate()`. It adds the two interceptors (`Infrastructure/Persistence/UserIdConnectionInterceptor.cs`, `Infrastructure/Persistence/OwnershipSaveChangesInterceptor.cs`) and DI wiring in `Infrastructure/Persistence/PersistenceExtensions.cs`. It produces two EF migrations: `InitialSchema` (tables/keys/indexes) and `SyncTriggersAndRls` (raw SQL: `global_change_seq` sequence, `bump_change_seq()` function, `BEFORE INSERT OR UPDATE` triggers on every synced table, `ENABLE ROW LEVEL SECURITY` + per-table `USING (user_id = current_setting('app.user_id', true)::uuid)` policies). All of it is driven by the five critical isolation/sync integration tests in `backend/tests/Tmap.Api.Tests/PersistenceTests.cs`. **Prerequisite:** Phase P0 has created the solution, the `Tmap.Api` project (with `public partial class Program {}`), the test project, `PostgresFixture`, `IntegrationTestBase`, the `[CollectionDefinition("db")]`, and the `WebApplicationFactory<Program>` that runs `MigrateAsync()` — this phase fills in the EF model those harness pieces compile against.

> **Contract-gap notes (new shared symbols this phase introduces; no conflict with the contract — flagged per instructions):**
> - `Infrastructure/Persistence/UserIdConnectionInterceptor.cs` (class `UserIdConnectionInterceptor : DbConnectionInterceptor`) — the §"Tenant isolation" connection interceptor the contract describes but does not name.
> - `Infrastructure/Persistence/OwnershipSaveChangesInterceptor.cs` (class `OwnershipSaveChangesInterceptor : SaveChangesInterceptor`) — the write-time ownership stamper the contract describes but does not name.
> - `Infrastructure/Persistence/PersistenceExtensions.cs` (`AddPersistence(this IServiceCollection, string connectionString)`) — DI registration helper; Phase P0's `Program.cs`/`WebApplicationFactory` calls it.
> - Named query filter string constants live as `public const string` on `AppDbContext` (`AppDbContext.TenantFilter = "Tenant"`, `AppDbContext.SoftDeleteFilter = "SoftDelete"`) so callers (sync in SP3) reference `IgnoreQueryFilters([AppDbContext.SoftDeleteFilter])` without magic strings. The filter **names** themselves remain exactly `"Tenant"` and `"SoftDelete"` per the contract.

---

### Task P1-1: Canonical shared interfaces & base entity (`IOwnedByUser`, `ISyncable`, `SyncEntity`)

- [ ] **Write failing test.** Create `backend/tests/Tmap.Api.Tests/PersistenceTests.cs` with a compile-only structural test that asserts the contract shapes exist. This drives creation of the shared types.

  ```csharp
  using System;
  using FluentAssertions;
  using Tmap.Api.Common;
  using Xunit;

  namespace Tmap.Api.Tests;

  public class SharedContractTests
  {
      [Fact]
      public void SyncEntity_Implements_Both_Contracts_And_Has_Required_Members()
      {
          typeof(IOwnedByUser).IsAssignableFrom(typeof(SyncEntity)).Should().BeTrue();
          typeof(ISyncable).IsAssignableFrom(typeof(SyncEntity)).Should().BeTrue();

          // SyncEntity is the single-uuid-PK base with all six fields.
          var t = typeof(SyncEntity);
          t.GetProperty(nameof(SyncEntity.Id))!.PropertyType.Should().Be(typeof(Guid));
          t.GetProperty(nameof(SyncEntity.UserId))!.PropertyType.Should().Be(typeof(Guid));
          t.GetProperty(nameof(SyncEntity.CreatedAt))!.PropertyType.Should().Be(typeof(DateTimeOffset));
          t.GetProperty(nameof(SyncEntity.UpdatedAt))!.PropertyType.Should().Be(typeof(DateTimeOffset));
          t.GetProperty(nameof(SyncEntity.DeletedAt))!.PropertyType.Should().Be(typeof(DateTimeOffset?));
          t.GetProperty(nameof(SyncEntity.ChangeSeq))!.PropertyType.Should().Be(typeof(long));
      }
  }
  ```

- [ ] **Run, expect FAIL (compile error).** `dotnet build`
  Expect: `error CS0234: The type or namespace name 'IOwnedByUser' does not exist in the namespace 'Tmap.Api.Common'` (and the same for `ISyncable`, `SyncEntity`).

- [ ] **Minimal impl (full code).** Create `backend/src/Tmap.Api/Common/IOwnedByUser.cs`:

  ```csharp
  namespace Tmap.Api.Common;

  public interface IOwnedByUser
  {
      Guid UserId { get; set; }
  }
  ```

  Create `backend/src/Tmap.Api/Common/ISyncable.cs`:

  ```csharp
  namespace Tmap.Api.Common;

  public interface ISyncable
  {
      DateTimeOffset CreatedAt { get; set; }
      DateTimeOffset UpdatedAt { get; set; }
      DateTimeOffset? DeletedAt { get; set; }
      long ChangeSeq { get; set; }
  }
  ```

  Create `backend/src/Tmap.Api/Common/SyncEntity.cs`:

  ```csharp
  namespace Tmap.Api.Common;

  public abstract class SyncEntity : IOwnedByUser, ISyncable
  {
      public Guid Id { get; set; }
      public Guid UserId { get; set; }
      public DateTimeOffset CreatedAt { get; set; }
      public DateTimeOffset UpdatedAt { get; set; }
      public DateTimeOffset? DeletedAt { get; set; }
      public long ChangeSeq { get; set; }
  }
  ```

- [ ] **Run, expect PASS.** `dotnet test --filter "FullyQualifiedName~SharedContractTests.SyncEntity_Implements_Both_Contracts_And_Has_Required_Members"`

- [ ] **Commit.**
  ```
  feat(api): add IOwnedByUser, ISyncable, SyncEntity canonical base types

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  ```

---

### Task P1-2: `ICurrentUser` + fail-closed `CurrentUser` (HTTP) + `SystemCurrentUser`

- [ ] **Write failing test.** Append to `backend/tests/Tmap.Api.Tests/PersistenceTests.cs` a new test class verifying the fail-closed contract (`Id` throws when not authenticated) and the system identity.

  ```csharp
  using System.Security.Claims;
  using Microsoft.AspNetCore.Http;
  using Tmap.Api.Common;

  public class CurrentUserTests
  {
      private static CurrentUser MakeHttp(ClaimsPrincipal? principal)
      {
          var accessor = new HttpContextAccessor
          {
              HttpContext = principal is null ? null : new DefaultHttpContext { User = principal }
          };
          return new CurrentUser(accessor);
      }

      [Fact]
      public void CurrentUser_Unauthenticated_IsAuthenticated_False_And_Id_Throws()
      {
          var sut = MakeHttp(null);
          sut.IsAuthenticated.Should().BeFalse();
          var act = () => sut.Id;
          act.Should().Throw<InvalidOperationException>();
      }

      [Fact]
      public void CurrentUser_Reads_Sub_Claim_As_Id()
      {
          var id = Guid.NewGuid();
          var principal = new ClaimsPrincipal(new ClaimsIdentity(
              new[] { new Claim("sub", id.ToString()) }, authenticationType: "TestAuth"));
          var sut = MakeHttp(principal);
          sut.IsAuthenticated.Should().BeTrue();
          sut.Id.Should().Be(id);
      }

      [Fact]
      public void SystemCurrentUser_Is_Authenticated_With_Fixed_Id()
      {
          var sut = new SystemCurrentUser();
          sut.IsAuthenticated.Should().BeTrue();
          sut.Id.Should().Be(SystemCurrentUser.SystemUserId);
      }
  }
  ```

- [ ] **Run, expect FAIL (compile error).** `dotnet build`
  Expect: `error CS0246: The type or namespace name 'CurrentUser' could not be found` and `SystemCurrentUser` likewise.

- [ ] **Minimal impl (full code).** Create `backend/src/Tmap.Api/Common/ICurrentUser.cs`:

  ```csharp
  namespace Tmap.Api.Common;

  public interface ICurrentUser
  {
      bool IsAuthenticated { get; }
      Guid Id { get; } // THROWS InvalidOperationException if !IsAuthenticated (fail-closed)
  }
  ```

  Create `backend/src/Tmap.Api/Common/CurrentUser.cs`. The `sub` claim is mapped by `JwtBearer` config in Phase P2; we read both the raw `"sub"` and the framework-mapped `ClaimTypes.NameIdentifier` so the impl is robust regardless of claim-mapping settings.

  ```csharp
  using System.Security.Claims;
  using Microsoft.AspNetCore.Http;

  namespace Tmap.Api.Common;

  public sealed class CurrentUser(IHttpContextAccessor accessor) : ICurrentUser
  {
      private Guid? TryResolve()
      {
          var principal = accessor.HttpContext?.User;
          if (principal?.Identity?.IsAuthenticated != true)
              return null;

          var raw = principal.FindFirstValue("sub")
                    ?? principal.FindFirstValue(ClaimTypes.NameIdentifier);

          return Guid.TryParse(raw, out var id) ? id : null;
      }

      public bool IsAuthenticated => TryResolve() is not null;

      public Guid Id =>
          TryResolve()
          ?? throw new InvalidOperationException(
              "No authenticated user is available. ICurrentUser is fail-closed for data operations.");
  }
  ```

  Create `backend/src/Tmap.Api/Common/SystemCurrentUser.cs` (used by migrations/seed/sync/tests via `NewElevatedDbContext`):

  ```csharp
  namespace Tmap.Api.Common;

  public sealed class SystemCurrentUser : ICurrentUser
  {
      // Fixed, well-known identity for system/background/test contexts.
      public static readonly Guid SystemUserId = new("00000000-0000-0000-0000-000000000001");

      public bool IsAuthenticated => true;
      public Guid Id => SystemUserId;
  }
  ```

- [ ] **Run, expect PASS.** `dotnet test --filter "FullyQualifiedName~CurrentUserTests"`

- [ ] **Commit.**
  ```
  feat(api): add ICurrentUser with fail-closed CurrentUser and SystemCurrentUser

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  ```

---

### Task P1-3: Enums and all domain entities

- [ ] **Write failing test.** Append to `PersistenceTests.cs` a structural test that pins every entity name, base type, key shape, and the contract-critical field/property names so a rename is caught. This is the guard that the entities match the contract exactly.

  ```csharp
  using Tmap.Api.Infrastructure.Entities;

  public class EntityShapeTests
  {
      [Fact]
      public void Enums_Have_Exact_Members()
      {
          Enum.GetNames<TaskStatus>().Should().Equal(
              "Inbox", "Backlog", "Planned", "Scheduled", "Done", "Archived");
          Enum.GetNames<RecurrenceFrequency>().Should().Equal("Daily", "Weekly");
          Enum.GetNames<RecurrenceEndType>().Should().Equal("Never", "Count", "Date");
      }

      [Fact]
      public void SyncEntities_Derive_From_SyncEntity()
      {
          foreach (var t in new[]
          {
              typeof(TaskItem), typeof(Subtask), typeof(Project), typeof(NoteGroup),
              typeof(Note), typeof(RecurrenceRule), typeof(RecurrenceException), typeof(FocusSession)
          })
          {
              t.BaseType.Should().Be(typeof(Tmap.Api.Common.SyncEntity), $"{t.Name} must be a SyncEntity");
          }
      }

      [Fact]
      public void Composite_Key_Entities_Implement_Sync_Interfaces_But_Not_SyncEntity()
      {
          // DailyPlan and UserSetting have composite keys and NO Id.
          typeof(DailyPlan).BaseType.Should().Be(typeof(object));
          typeof(UserSetting).BaseType.Should().Be(typeof(object));
          typeof(Tmap.Api.Common.IOwnedByUser).IsAssignableFrom(typeof(DailyPlan)).Should().BeTrue();
          typeof(Tmap.Api.Common.ISyncable).IsAssignableFrom(typeof(DailyPlan)).Should().BeTrue();
          typeof(Tmap.Api.Common.IOwnedByUser).IsAssignableFrom(typeof(UserSetting)).Should().BeTrue();
          typeof(Tmap.Api.Common.ISyncable).IsAssignableFrom(typeof(UserSetting)).Should().BeTrue();
          typeof(DailyPlan).GetProperty("Id").Should().BeNull("DailyPlan has no Id (composite key)");
          typeof(UserSetting).GetProperty("Id").Should().BeNull("UserSetting has no Id (composite key)");
      }

      [Fact]
      public void TaskItem_Has_Contract_Fields()
      {
          var t = typeof(TaskItem);
          t.GetProperty("Title")!.PropertyType.Should().Be(typeof(string));
          t.GetProperty("ProjectId")!.PropertyType.Should().Be(typeof(Guid?));
          t.GetProperty("Labels")!.PropertyType.Should().Be(typeof(List<string>));
          t.GetProperty("Status")!.PropertyType.Should().Be(typeof(TaskStatus));
          t.GetProperty("PlannedDate")!.PropertyType.Should().Be(typeof(DateOnly?));
          t.GetProperty("Rank")!.PropertyType.Should().Be(typeof(string));
          t.GetProperty("DueDate")!.PropertyType.Should().Be(typeof(DateOnly?));
          t.GetProperty("Subtasks")!.PropertyType.Should().Be(typeof(List<Subtask>));
      }
  }
  ```

- [ ] **Run, expect FAIL (compile error).** `dotnet build`
  Expect: `error CS0234: The type or namespace name 'Entities' does not exist in the namespace 'Tmap.Api.Infrastructure'`.

- [ ] **Minimal impl (full code).** Create `backend/src/Tmap.Api/Infrastructure/Entities/Enums.cs`:

  ```csharp
  namespace Tmap.Api.Infrastructure.Entities;

  public enum TaskStatus
  {
      Inbox,
      Backlog,
      Planned,
      Scheduled,
      Done,
      Archived
  }

  public enum RecurrenceFrequency
  {
      Daily,
      Weekly
  }

  public enum RecurrenceEndType
  {
      Never,
      Count,
      Date
  }
  ```

  Create `backend/src/Tmap.Api/Infrastructure/Entities/TaskItem.cs` (NAME IS `TaskItem`, never `Task`):

  ```csharp
  using Tmap.Api.Common;

  namespace Tmap.Api.Infrastructure.Entities;

  public class TaskItem : SyncEntity
  {
      public string Title { get; set; } = string.Empty;
      public string Notes { get; set; } = string.Empty;
      public Guid? ProjectId { get; set; }
      public List<string> Labels { get; set; } = [];
      public string Source { get; set; } = "local";
      public TaskStatus Status { get; set; } = TaskStatus.Inbox;
      public DateOnly? PlannedDate { get; set; }
      public DateTimeOffset? ScheduledStart { get; set; }
      public DateTimeOffset? ScheduledEnd { get; set; }
      public int DurationMinutes { get; set; } = 30;
      public int ActualTimeMinutes { get; set; }
      public int? Priority { get; set; }
      public int? ReminderMinutes { get; set; }
      public string Rank { get; set; } = string.Empty;
      public DateOnly? DueDate { get; set; } // legacy, carried for round-trip fidelity
      public Guid? RecurrenceRuleId { get; set; }
      public bool IsRecurrenceTemplate { get; set; }
      public bool RecurrenceDetached { get; set; }
      public DateOnly? RecurrenceOriginalDate { get; set; }
      public DateTimeOffset? CompletedAt { get; set; }

      public Project? Project { get; set; }
      public List<Subtask> Subtasks { get; set; } = [];
  }
  ```

  Create `backend/src/Tmap.Api/Infrastructure/Entities/Subtask.cs`:

  ```csharp
  using Tmap.Api.Common;

  namespace Tmap.Api.Infrastructure.Entities;

  public class Subtask : SyncEntity
  {
      public Guid TaskId { get; set; }
      public string Title { get; set; } = string.Empty;
      public bool Completed { get; set; }
      public int SortOrder { get; set; }

      public TaskItem? Task { get; set; }
  }
  ```

  Create `backend/src/Tmap.Api/Infrastructure/Entities/Project.cs`:

  ```csharp
  using Tmap.Api.Common;

  namespace Tmap.Api.Infrastructure.Entities;

  public class Project : SyncEntity
  {
      public string Name { get; set; } = string.Empty;
      public string Color { get; set; } = "#6366f1";
      public string Emoji { get; set; } = "📁";
      public string Rank { get; set; } = string.Empty;
      public int ActualTimeMinutes { get; set; }
  }
  ```

  Create `backend/src/Tmap.Api/Infrastructure/Entities/NoteGroup.cs`:

  ```csharp
  using Tmap.Api.Common;

  namespace Tmap.Api.Infrastructure.Entities;

  public class NoteGroup : SyncEntity
  {
      public string Name { get; set; } = string.Empty;
      public string Emoji { get; set; } = "📝";
      public Guid? ProjectId { get; set; }
      public string Rank { get; set; } = string.Empty;
  }
  ```

  Create `backend/src/Tmap.Api/Infrastructure/Entities/Note.cs`:

  ```csharp
  using Tmap.Api.Common;

  namespace Tmap.Api.Infrastructure.Entities;

  public class Note : SyncEntity
  {
      public Guid? GroupId { get; set; }
      public Guid? ProjectId { get; set; }
      public string Title { get; set; } = "Untitled";
      public string Content { get; set; } = string.Empty;
      public string Rank { get; set; } = string.Empty;
  }
  ```

  Create `backend/src/Tmap.Api/Infrastructure/Entities/RecurrenceRule.cs`:

  ```csharp
  using Tmap.Api.Common;

  namespace Tmap.Api.Infrastructure.Entities;

  public class RecurrenceRule : SyncEntity
  {
      public RecurrenceFrequency Frequency { get; set; }
      public int IntervalValue { get; set; } = 1;
      public List<int> DaysOfWeek { get; set; } = [];
      public RecurrenceEndType EndType { get; set; } = RecurrenceEndType.Never;
      public int? EndCount { get; set; }
      public DateOnly? EndDate { get; set; }
      public DateOnly? GeneratedUntil { get; set; }
  }
  ```

  Create `backend/src/Tmap.Api/Infrastructure/Entities/RecurrenceException.cs`:

  ```csharp
  using Tmap.Api.Common;

  namespace Tmap.Api.Infrastructure.Entities;

  // insert/tombstone-only
  public class RecurrenceException : SyncEntity
  {
      public Guid RecurrenceRuleId { get; set; }
      public DateOnly ExceptionDate { get; set; }
  }
  ```

  Create `backend/src/Tmap.Api/Infrastructure/Entities/FocusSession.cs`:

  ```csharp
  using Tmap.Api.Common;

  namespace Tmap.Api.Infrastructure.Entities;

  // append-only
  public class FocusSession : SyncEntity
  {
      public Guid? TaskId { get; set; }
      public string Project { get; set; } = string.Empty; // NAME snapshot, not an FK
      public DateTimeOffset StartedAt { get; set; }
      public DateTimeOffset EndedAt { get; set; }
      public int Minutes { get; set; }
      public DateOnly Date { get; set; }
  }
  ```

  Create `backend/src/Tmap.Api/Infrastructure/Entities/DailyPlan.cs` (composite key `(UserId, Date)`, NO `Id`):

  ```csharp
  using Tmap.Api.Common;

  namespace Tmap.Api.Infrastructure.Entities;

  public class DailyPlan : IOwnedByUser, ISyncable
  {
      public Guid UserId { get; set; }
      public DateOnly Date { get; set; }
      public DateTimeOffset CommittedAt { get; set; }
      public List<Guid> PlannedTaskIds { get; set; } = [];
      public int PlannedMinutes { get; set; }

      // sync columns
      public DateTimeOffset CreatedAt { get; set; }
      public DateTimeOffset UpdatedAt { get; set; }
      public DateTimeOffset? DeletedAt { get; set; }
      public long ChangeSeq { get; set; }
  }
  ```

  Create `backend/src/Tmap.Api/Infrastructure/Entities/UserSetting.cs` (composite key `(UserId, Key)`, NO `Id`):

  ```csharp
  using Tmap.Api.Common;

  namespace Tmap.Api.Infrastructure.Entities;

  public class UserSetting : IOwnedByUser, ISyncable
  {
      public Guid UserId { get; set; }
      public string Key { get; set; } = string.Empty;
      public string Value { get; set; } = string.Empty; // jsonb text

      // sync columns
      public DateTimeOffset CreatedAt { get; set; }
      public DateTimeOffset UpdatedAt { get; set; }
      public DateTimeOffset? DeletedAt { get; set; }
      public long ChangeSeq { get; set; }
  }
  ```

  Create `backend/src/Tmap.Api/Infrastructure/Entities/ApplicationUser.cs`:

  ```csharp
  using Microsoft.AspNetCore.Identity;

  namespace Tmap.Api.Infrastructure.Entities;

  public class ApplicationUser : IdentityUser<Guid>
  {
      public string TimeZoneId { get; set; } = "UTC"; // IANA tz, for report bucketing
  }
  ```

  Create `backend/src/Tmap.Api/Infrastructure/Entities/RefreshToken.cs` (internal, NOT synced — no sync cols, no `IOwnedByUser`/`ISyncable`):

  ```csharp
  namespace Tmap.Api.Infrastructure.Entities;

  public class RefreshToken
  {
      public Guid Id { get; set; }
      public Guid UserId { get; set; }
      public string TokenHash { get; set; } = string.Empty;
      public DateTimeOffset ExpiresAt { get; set; }
      public DateTimeOffset CreatedAt { get; set; }
      public DateTimeOffset? RevokedAt { get; set; }
      public string? ReplacedByTokenHash { get; set; }
      public string? DeviceInfo { get; set; }
  }
  ```

- [ ] **Run, expect PASS.** `dotnet test --filter "FullyQualifiedName~EntityShapeTests"`

- [ ] **Commit.**
  ```
  feat(api): add all domain entities, enums, ApplicationUser and RefreshToken

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  ```

---

### Task P1-4: `AppDbContext` skeleton + DI wiring (`PersistenceExtensions`) so the harness boots

This task creates the `AppDbContext` shell with snake_case + Identity, registers it via `AddPersistence`, and lets `WebApplicationFactory<Program>` migrate. The interceptors and full mapping arrive in P1-7/P1-8; here we get a green end-to-end "the test harness connects to Postgres and migrates" baseline (without a migration there is nothing to apply yet — so this task ends by *adding* the first migration in P1-6's slot is deferred; instead this task only asserts the context constructs). To keep TDD honest, the test here is a pure DI/construction test using the fixture connection — no migration required.

- [ ] **Write failing test.** Append to `PersistenceTests.cs` (in the `db` collection, using the harness). This proves `AddPersistence` registers a Scoped `AppDbContext` that can open a connection against the Testcontainers Postgres.

  ```csharp
  using Microsoft.EntityFrameworkCore;
  using Microsoft.Extensions.DependencyInjection;
  using Tmap.Api.Infrastructure;

  [Collection("db")]
  public class AppDbContextWiringTests(PostgresFixture fixture)
  {
      [Fact]
      public void AddPersistence_Registers_Scoped_AppDbContext_That_Can_Connect()
      {
          var services = new ServiceCollection();
          services.AddHttpContextAccessor();
          services.AddScoped<Tmap.Api.Common.ICurrentUser, Tmap.Api.Common.SystemCurrentUser>();
          services.AddPersistence(fixture.ConnectionString);

          using var provider = services.BuildServiceProvider();
          using var scope = provider.CreateScope();
          var ctx = scope.ServiceProvider.GetRequiredService<AppDbContext>();

          ctx.Database.CanConnect().Should().BeTrue();
      }
  }
  ```

- [ ] **Run, expect FAIL (compile error).** `dotnet build`
  Expect: `error CS0246: The type or namespace name 'AppDbContext' could not be found` and `'AddPersistence'` not found.

- [ ] **Minimal impl (full code).** Create `backend/src/Tmap.Api/Infrastructure/AppDbContext.cs`. Include the `DbSet`s and the named-filter constants now; the full `OnModelCreating` mapping is filled in P1-5. Note: `IdentityDbContext` brings the Identity tables (`ApplicationUser` keyed by `Guid`).

  ```csharp
  using Microsoft.AspNetCore.Identity;
  using Microsoft.AspNetCore.Identity.EntityFrameworkCore;
  using Microsoft.EntityFrameworkCore;
  using Tmap.Api.Common;
  using Tmap.Api.Infrastructure.Entities;

  namespace Tmap.Api.Infrastructure;

  public class AppDbContext(DbContextOptions<AppDbContext> options, ICurrentUser currentUser)
      : IdentityDbContext<ApplicationUser, IdentityRole<Guid>, Guid>(options)
  {
      public const string TenantFilter = "Tenant";
      public const string SoftDeleteFilter = "SoftDelete";

      private readonly ICurrentUser _currentUser = currentUser;

      public DbSet<TaskItem> Tasks => Set<TaskItem>();
      public DbSet<Subtask> Subtasks => Set<Subtask>();
      public DbSet<Project> Projects => Set<Project>();
      public DbSet<NoteGroup> NoteGroups => Set<NoteGroup>();
      public DbSet<Note> Notes => Set<Note>();
      public DbSet<RecurrenceRule> RecurrenceRules => Set<RecurrenceRule>();
      public DbSet<RecurrenceException> RecurrenceExceptions => Set<RecurrenceException>();
      public DbSet<FocusSession> FocusSessions => Set<FocusSession>();
      public DbSet<DailyPlan> DailyPlans => Set<DailyPlan>();
      public DbSet<UserSetting> UserSettings => Set<UserSetting>();
      public DbSet<RefreshToken> RefreshTokens => Set<RefreshToken>();

      protected override void OnModelCreating(ModelBuilder modelBuilder)
      {
          base.OnModelCreating(modelBuilder);
          // Full entity configuration added in Task P1-5.
      }
  }
  ```

  Create `backend/src/Tmap.Api/Infrastructure/Persistence/PersistenceExtensions.cs`:

  ```csharp
  using Microsoft.EntityFrameworkCore;
  using Microsoft.Extensions.DependencyInjection;

  namespace Tmap.Api.Infrastructure.Persistence;

  public static class PersistenceExtensions
  {
      public static IServiceCollection AddPersistence(this IServiceCollection services, string connectionString)
      {
          services.AddDbContext<AppDbContext>(options =>
          {
              options.UseNpgsql(connectionString);
              options.UseSnakeCaseNamingConvention();
          });

          return services;
      }
  }
  ```

  > Add `using Tmap.Api.Infrastructure.Persistence;` to `Program.cs` and call `builder.Services.AddPersistence(connectionString)` (Phase P0 owns `Program.cs`; this phase only ensures the call exists — if P0 has not wired it, add the call now). Confirm the project references the packages: `Npgsql.EntityFrameworkCore.PostgreSQL`, `EFCore.NamingConventions`, `Microsoft.AspNetCore.Identity.EntityFrameworkCore`. If missing, add them:
  > ```
  > dotnet add src/Tmap.Api package Npgsql.EntityFrameworkCore.PostgreSQL
  > dotnet add src/Tmap.Api package EFCore.NamingConventions
  > dotnet add src/Tmap.Api package Microsoft.AspNetCore.Identity.EntityFrameworkCore
  > ```

- [ ] **Run, expect PASS.** `dotnet test --filter "FullyQualifiedName~AppDbContextWiringTests.AddPersistence_Registers_Scoped_AppDbContext_That_Can_Connect"`

- [ ] **Commit.**
  ```
  feat(api): add AppDbContext skeleton with snake_case naming and AddPersistence DI

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  ```

---

### Task P1-5: Full `OnModelCreating` mapping — jsonb, enum-as-text, composite keys, FKs, partial unique indexes, named query filters, `change_seq` mapping

- [ ] **Write failing test.** Append a model-metadata test to `PersistenceTests.cs`. It uses the EF model (no DB round-trip) to assert every contract-mandated mapping is configured. This forces the entire mapping into existence.

  ```csharp
  using Microsoft.EntityFrameworkCore;
  using Microsoft.EntityFrameworkCore.Metadata;
  using Microsoft.Extensions.DependencyInjection;
  using Tmap.Api.Infrastructure;
  using Tmap.Api.Infrastructure.Entities;

  [Collection("db")]
  public class AppDbContextModelTests(PostgresFixture fixture)
  {
      private AppDbContext NewContext()
      {
          var services = new ServiceCollection();
          services.AddHttpContextAccessor();
          services.AddScoped<Tmap.Api.Common.ICurrentUser, Tmap.Api.Common.SystemCurrentUser>();
          services.AddDbContext<AppDbContext>(o =>
          {
              o.UseNpgsql(fixture.ConnectionString);
              o.UseSnakeCaseNamingConvention();
          });
          var provider = services.BuildServiceProvider();
          return provider.GetRequiredService<AppDbContext>();
      }

      [Fact]
      public void ChangeSeq_Is_ValueGeneratedOnAddOrUpdate_And_Not_Concurrency_Token()
      {
          using var ctx = NewContext();
          var prop = ctx.Model.FindEntityType(typeof(TaskItem))!.FindProperty(nameof(TaskItem.ChangeSeq))!;
          prop.ValueGenerated.Should().Be(ValueGenerated.OnAddOrUpdate);
          prop.IsConcurrencyToken.Should().BeFalse();
          prop.GetBeforeSaveBehavior().Should().Be(PropertySaveBehavior.Ignore);
          prop.GetAfterSaveBehavior().Should().Be(PropertySaveBehavior.Save);
      }

      [Fact]
      public void Labels_DaysOfWeek_PlannedTaskIds_Are_Jsonb()
      {
          using var ctx = NewContext();
          ctx.Model.FindEntityType(typeof(TaskItem))!
             .FindProperty(nameof(TaskItem.Labels))!.GetColumnType().Should().Be("jsonb");
          ctx.Model.FindEntityType(typeof(RecurrenceRule))!
             .FindProperty(nameof(RecurrenceRule.DaysOfWeek))!.GetColumnType().Should().Be("jsonb");
          ctx.Model.FindEntityType(typeof(DailyPlan))!
             .FindProperty(nameof(DailyPlan.PlannedTaskIds))!.GetColumnType().Should().Be("jsonb");
          ctx.Model.FindEntityType(typeof(UserSetting))!
             .FindProperty(nameof(UserSetting.Value))!.GetColumnType().Should().Be("jsonb");
      }

      [Fact]
      public void Status_And_Frequency_Are_Stored_As_Text()
      {
          using var ctx = NewContext();
          ctx.Model.FindEntityType(typeof(TaskItem))!
             .FindProperty(nameof(TaskItem.Status))!.GetColumnType().Should().Be("text");
          ctx.Model.FindEntityType(typeof(RecurrenceRule))!
             .FindProperty(nameof(RecurrenceRule.Frequency))!.GetColumnType().Should().Be("text");
          ctx.Model.FindEntityType(typeof(RecurrenceRule))!
             .FindProperty(nameof(RecurrenceRule.EndType))!.GetColumnType().Should().Be("text");
      }

      [Fact]
      public void Composite_Keys_Are_Configured()
      {
          using var ctx = NewContext();
          var dp = ctx.Model.FindEntityType(typeof(DailyPlan))!.FindPrimaryKey()!;
          dp.Properties.Select(p => p.Name).Should().Equal(nameof(DailyPlan.UserId), nameof(DailyPlan.Date));
          var us = ctx.Model.FindEntityType(typeof(UserSetting))!.FindPrimaryKey()!;
          us.Properties.Select(p => p.Name).Should().Equal(nameof(UserSetting.UserId), nameof(UserSetting.Key));
      }

      [Fact]
      public void Named_Query_Filters_Tenant_And_SoftDelete_Exist_On_TaskItem()
      {
          using var ctx = NewContext();
          var filters = ctx.Model.FindEntityType(typeof(TaskItem))!.GetDeclaredQueryFilters();
          filters.Select(f => f.Key).Should().Contain(new[] { AppDbContext.TenantFilter, AppDbContext.SoftDeleteFilter });
      }

      [Fact]
      public void TaskItem_To_Project_FK_And_Subtask_To_Task_FK_Configured()
      {
          using var ctx = NewContext();
          var taskType = ctx.Model.FindEntityType(typeof(TaskItem))!;
          taskType.GetForeignKeys()
              .Should().Contain(fk => fk.PrincipalEntityType.ClrType == typeof(Project)
                  && fk.Properties.Any(p => p.Name == nameof(TaskItem.ProjectId)));
          var subtaskType = ctx.Model.FindEntityType(typeof(Subtask))!;
          subtaskType.GetForeignKeys()
              .Should().Contain(fk => fk.PrincipalEntityType.ClrType == typeof(TaskItem)
                  && fk.Properties.Any(p => p.Name == nameof(Subtask.TaskId)));
      }
  }
  ```

  > **Note on `GetDeclaredQueryFilters()`:** this is the EF Core 10 named-filter introspection API. If the installed EF Core 10 build exposes named filters under a different read accessor, adapt only the *assertion* read; the production `HasQueryFilter(name, expr)` calls below are the contract surface.

- [ ] **Run, expect FAIL.** `dotnet test --filter "FullyQualifiedName~AppDbContextModelTests"`
  Expect failures such as `Expected prop.ValueGenerated to be OnAddOrUpdate, but found Never` and `Expected column type to be "jsonb" but found "text"` etc. (the default mapping does none of this yet).

- [ ] **Minimal impl (full code).** Replace the `OnModelCreating` body in `backend/src/Tmap.Api/Infrastructure/AppDbContext.cs` with the full configuration. EF Core 10 named query filters use the `HasQueryFilter(string name, expression)` overload; `_currentUser.Id` is read at query time inside the tenant expression. A private helper applies the sync-column mapping uniformly.

  ```csharp
  protected override void OnModelCreating(ModelBuilder modelBuilder)
  {
      base.OnModelCreating(modelBuilder);

      // ---- TaskItem ----
      modelBuilder.Entity<TaskItem>(b =>
      {
          b.ToTable("tasks");
          b.HasKey(x => x.Id);
          b.Property(x => x.Status).HasConversion<string>().HasColumnType("text");
          b.Property(x => x.Labels).HasColumnType("jsonb");
          b.Property(x => x.Rank).IsRequired();
          b.HasOne(x => x.Project)
              .WithMany()
              .HasForeignKey(x => x.ProjectId)
              .OnDelete(DeleteBehavior.Restrict); // soft-delete only; never cascade-DELETE user data
          b.HasMany(x => x.Subtasks)
              .WithOne(s => s.Task!)
              .HasForeignKey(s => s.TaskId)
              .OnDelete(DeleteBehavior.Restrict);
          b.HasIndex(x => new { x.UserId, x.Status });
          b.HasIndex(x => new { x.UserId, x.PlannedDate });
          ConfigureSyncEntity(b);
          ApplyTenantAndSoftDeleteFilters(b);
      });

      // ---- Subtask ----
      modelBuilder.Entity<Subtask>(b =>
      {
          b.ToTable("subtasks");
          b.HasKey(x => x.Id);
          b.HasIndex(x => new { x.UserId, x.TaskId });
          ConfigureSyncEntity(b);
          ApplyTenantAndSoftDeleteFilters(b);
      });

      // ---- Project ----
      modelBuilder.Entity<Project>(b =>
      {
          b.ToTable("projects");
          b.HasKey(x => x.Id);
          b.Property(x => x.Rank).IsRequired();
          // partial unique index: per-user name uniqueness among live rows only
          b.HasIndex(x => new { x.UserId, x.Name })
              .IsUnique()
              .HasFilter("deleted_at IS NULL");
          ConfigureSyncEntity(b);
          ApplyTenantAndSoftDeleteFilters(b);
      });

      // ---- NoteGroup ----
      modelBuilder.Entity<NoteGroup>(b =>
      {
          b.ToTable("note_groups");
          b.HasKey(x => x.Id);
          b.Property(x => x.Rank).IsRequired();
          b.HasIndex(x => new { x.UserId, x.ProjectId });
          ConfigureSyncEntity(b);
          ApplyTenantAndSoftDeleteFilters(b);
      });

      // ---- Note ----
      modelBuilder.Entity<Note>(b =>
      {
          b.ToTable("notes");
          b.HasKey(x => x.Id);
          b.Property(x => x.Rank).IsRequired();
          b.HasIndex(x => new { x.UserId, x.GroupId });
          b.HasIndex(x => new { x.UserId, x.ProjectId });
          ConfigureSyncEntity(b);
          ApplyTenantAndSoftDeleteFilters(b);
      });

      // ---- RecurrenceRule ----
      modelBuilder.Entity<RecurrenceRule>(b =>
      {
          b.ToTable("recurrence_rules");
          b.HasKey(x => x.Id);
          b.Property(x => x.Frequency).HasConversion<string>().HasColumnType("text");
          b.Property(x => x.EndType).HasConversion<string>().HasColumnType("text");
          b.Property(x => x.DaysOfWeek).HasColumnType("jsonb");
          ConfigureSyncEntity(b);
          ApplyTenantAndSoftDeleteFilters(b);
      });

      // ---- RecurrenceException ----
      modelBuilder.Entity<RecurrenceException>(b =>
      {
          b.ToTable("recurrence_exceptions");
          b.HasKey(x => x.Id);
          // partial unique index among live rows only
          b.HasIndex(x => new { x.RecurrenceRuleId, x.ExceptionDate })
              .IsUnique()
              .HasFilter("deleted_at IS NULL");
          ConfigureSyncEntity(b);
          ApplyTenantAndSoftDeleteFilters(b);
      });

      // ---- FocusSession ----
      modelBuilder.Entity<FocusSession>(b =>
      {
          b.ToTable("focus_sessions");
          b.HasKey(x => x.Id);
          b.HasIndex(x => new { x.UserId, x.Date });
          ConfigureSyncEntity(b);
          ApplyTenantAndSoftDeleteFilters(b);
      });

      // ---- DailyPlan (composite key (UserId, Date), no Id) ----
      modelBuilder.Entity<DailyPlan>(b =>
      {
          b.ToTable("daily_plans");
          b.HasKey(x => new { x.UserId, x.Date });
          b.Property(x => x.PlannedTaskIds).HasColumnType("jsonb");
          ConfigureSyncColumns(b);
          ApplyTenantAndSoftDeleteFilters(b);
      });

      // ---- UserSetting (composite key (UserId, Key), no Id) ----
      modelBuilder.Entity<UserSetting>(b =>
      {
          b.ToTable("user_settings");
          b.HasKey(x => new { x.UserId, x.Key });
          b.Property(x => x.Value).HasColumnType("jsonb");
          ConfigureSyncColumns(b);
          ApplyTenantAndSoftDeleteFilters(b);
      });

      // ---- RefreshToken (internal, NOT synced) ----
      modelBuilder.Entity<RefreshToken>(b =>
      {
          b.ToTable("refresh_tokens");
          b.HasKey(x => x.Id);
          b.Property(x => x.TokenHash).IsRequired();
          b.HasIndex(x => x.TokenHash).IsUnique();
          b.HasIndex(x => x.UserId);
          // no sync columns, no query filters
      });

      // ---- ApplicationUser extra column ----
      modelBuilder.Entity<ApplicationUser>(b =>
      {
          b.Property(x => x.TimeZoneId).IsRequired().HasDefaultValue("UTC");
      });
  }

  // change_seq + updated_at are written by the DB trigger, never by EF.
  // change_seq: ValueGeneratedOnAddOrUpdate, BeforeSave=Ignore, AfterSave=Save. NOT a concurrency token.
  private static void ConfigureSyncColumns<TEntity>(EntityTypeBuilder<TEntity> b) where TEntity : class
  {
      b.Property(nameof(ISyncable.ChangeSeq))
          .ValueGeneratedOnAddOrUpdate()
          .Metadata.SetAfterSaveBehavior(PropertySaveBehavior.Save);
      b.Property(nameof(ISyncable.ChangeSeq))
          .Metadata.SetBeforeSaveBehavior(PropertySaveBehavior.Ignore);

      // updated_at is also trigger-authoritative: never written by EF, always read back.
      b.Property(nameof(ISyncable.UpdatedAt))
          .ValueGeneratedOnAddOrUpdate()
          .Metadata.SetAfterSaveBehavior(PropertySaveBehavior.Save);
      b.Property(nameof(ISyncable.UpdatedAt))
          .Metadata.SetBeforeSaveBehavior(PropertySaveBehavior.Ignore);
  }

  private static void ConfigureSyncEntity<TEntity>(EntityTypeBuilder<TEntity> b) where TEntity : SyncEntity
  {
      b.Property(x => x.UserId).IsRequired();
      ConfigureSyncColumns(b);
  }

  // EF Core 10 NAMED query filters: "Tenant" (UserId == currentUser.Id) and "SoftDelete" (DeletedAt == null).
  // _currentUser.Id is read AT QUERY TIME inside the expression (context is Scoped).
  private void ApplyTenantAndSoftDeleteFilters<TEntity>(EntityTypeBuilder<TEntity> b)
      where TEntity : class, IOwnedByUser, ISyncable
  {
      b.HasQueryFilter(TenantFilter, e => e.UserId == _currentUser.Id);
      b.HasQueryFilter(SoftDeleteFilter, e => e.DeletedAt == null);
  }
  ```

  Add the required usings at the top of `AppDbContext.cs`:

  ```csharp
  using Microsoft.EntityFrameworkCore.Metadata;
  using Microsoft.EntityFrameworkCore.Metadata.Builders;
  ```

  > **Why the helper reads `_currentUser.Id` inside the lambda:** the expression tree captures the instance member access, so EF re-evaluates `_currentUser.Id` each query against the Scoped context's injected `ICurrentUser` — exactly the contract requirement "filter reads ICurrentUser.Id at query time."

- [ ] **Run, expect PASS.** `dotnet test --filter "FullyQualifiedName~AppDbContextModelTests"`

- [ ] **Commit.**
  ```
  feat(api): configure AppDbContext mapping — jsonb, enum text, composite keys, FKs, partial unique indexes, named filters, change_seq

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  ```

---

### Task P1-6: Initial EF migration (`InitialSchema`)

- [ ] **Write failing test.** Append to `PersistenceTests.cs` a test (in the `db` collection) that the migrated database has all the expected tables. Until the migration exists, `MigrateAsync` creates nothing, so the table count assertion fails.

  ```csharp
  using Microsoft.EntityFrameworkCore;
  using Microsoft.Extensions.DependencyInjection;
  using Tmap.Api.Infrastructure;

  [Collection("db")]
  public class MigrationSchemaTests(PostgresFixture fixture) : IntegrationTestBase(fixture)
  {
      [Fact]
      public async Task Migration_Creates_All_Core_Tables()
      {
          await using var ctx = NewElevatedDbContext();
          var tables = new[]
          {
              "tasks", "subtasks", "projects", "note_groups", "notes",
              "recurrence_rules", "recurrence_exceptions", "focus_sessions",
              "daily_plans", "user_settings", "refresh_tokens"
          };

          foreach (var table in tables)
          {
              var exists = await ctx.Database
                  .SqlQuery<bool>($"SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = {table})")
                  .SingleAsync();
              exists.Should().BeTrue($"table {table} should exist after migration");
          }
      }
  }
  ```

- [ ] **Run, expect FAIL.** `dotnet test --filter "FullyQualifiedName~MigrationSchemaTests.Migration_Creates_All_Core_Tables"`
  Expect: the assertion `table tasks should exist after migration` fails (no migration → empty schema), or `MigrateAsync` reports "No migrations were applied."

- [ ] **Minimal impl.** Generate the migration. Run from `backend/`:

  ```
  dotnet ef migrations add InitialSchema -p src/Tmap.Api -s src/Tmap.Api
  ```

  This produces `backend/src/Tmap.Api/Infrastructure/Migrations/<timestamp>_InitialSchema.cs` and the model snapshot. **Do not hand-edit the generated table DDL.** Verify the generated `Up` includes: all eleven domain tables plus the Identity tables, the `jsonb` columns, the `text` enum columns, the composite PKs for `daily_plans`/`user_settings`, the `tasks.project_id` and `subtasks.task_id` FKs, and the partial unique indexes with `filter: "deleted_at IS NULL"` for `projects (user_id, name)` and `recurrence_exceptions (recurrence_rule_id, exception_date)`.

  > If `dotnet ef` is not installed: `dotnet tool install --global dotnet-ef` (or `dotnet tool restore` if a tool manifest exists).

- [ ] **Run, expect PASS.** `dotnet test --filter "FullyQualifiedName~MigrationSchemaTests.Migration_Creates_All_Core_Tables"`

- [ ] **Commit.**
  ```
  feat(api): add InitialSchema EF migration

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  ```

---

### Task P1-7: Second migration (raw SQL) — `global_change_seq`, `bump_change_seq()`, BEFORE INSERT OR UPDATE triggers, RLS enable + policies

This is the **authoritative** sync/isolation machinery. Two tests drive it: (a) insert auto-assigns `change_seq` and update bumps it; (b) an `ExecuteUpdate` bumps `change_seq` (proves the trigger, not an interceptor, is authoritative).

- [ ] **Write failing test (a) — trigger assigns/bumps on tracked writes.** Append to `PersistenceTests.cs`:

  ```csharp
  using Microsoft.EntityFrameworkCore;
  using Tmap.Api.Infrastructure;
  using Tmap.Api.Infrastructure.Entities;

  [Collection("db")]
  public class ChangeSeqTriggerTests(PostgresFixture fixture) : IntegrationTestBase(fixture)
  {
      [Fact]
      public async Task Insert_Assigns_ChangeSeq_And_Update_Bumps_It()
      {
          var user = await RegisterAsync();

          await using var arrange = NewElevatedDbContext();
          var project = new Project
          {
              Id = Guid.NewGuid(),
              UserId = user.UserId,
              Name = "P-" + Guid.NewGuid().ToString("N"),
              Rank = "a0",
              CreatedAt = DateTimeOffset.UtcNow
          };
          arrange.Projects.Add(project);
          await arrange.SaveChangesAsync();

          project.ChangeSeq.Should().BeGreaterThan(0, "the trigger assigns change_seq on INSERT");
          var afterInsert = project.ChangeSeq;
          var insertedUpdatedAt = project.UpdatedAt;

          project.Name = "renamed-" + Guid.NewGuid().ToString("N");
          await arrange.SaveChangesAsync();

          project.ChangeSeq.Should().BeGreaterThan(afterInsert, "the trigger bumps change_seq on UPDATE");
          project.UpdatedAt.Should().BeAfter(insertedUpdatedAt, "the trigger refreshes updated_at on UPDATE");
      }
  }
  ```

- [ ] **Write failing test (b) — ExecuteUpdate bumps `change_seq` (proves trigger, not interceptor).** Append:

  ```csharp
  [Fact]
  public async Task ExecuteUpdate_Bumps_ChangeSeq_Proving_Trigger_Not_Interceptor()
  {
      var user = await RegisterAsync();

      await using var ctx = NewElevatedDbContext();
      var task = new TaskItem
      {
          Id = Guid.NewGuid(),
          UserId = user.UserId,
          Title = "T",
          Rank = "a0",
          CreatedAt = DateTimeOffset.UtcNow
      };
      ctx.Tasks.Add(task);
      await ctx.SaveChangesAsync();
      var seqBefore = task.ChangeSeq;

      // Bulk update bypasses the ChangeTracker (and any SaveChanges interceptor).
      await ctx.Tasks
          .Where(t => t.Id == task.Id)
          .ExecuteUpdateAsync(s => s.SetProperty(t => t.DurationMinutes, 45));

      var seqAfter = await ctx.Tasks
          .Where(t => t.Id == task.Id)
          .Select(t => t.ChangeSeq)
          .SingleAsync();

      seqAfter.Should().BeGreaterThan(seqBefore,
          "ExecuteUpdate bypasses the tracker, so only a DB trigger can advance change_seq");
  }
  ```

- [ ] **Run, expect FAIL.** `dotnet test --filter "FullyQualifiedName~ChangeSeqTriggerTests"`
  Expect: test (a) fails with `Expected project.ChangeSeq to be greater than 0, but found 0` (no trigger → column stays 0/default); test (b) similarly `seqAfter` not greater than `seqBefore`.

- [ ] **Minimal impl.** Create an empty migration and fill its `Up`/`Down` with raw SQL. From `backend/`:

  ```
  dotnet ef migrations add SyncTriggersAndRls -p src/Tmap.Api -s src/Tmap.Api
  ```

  Replace the generated body of `backend/src/Tmap.Api/Infrastructure/Migrations/<timestamp>_SyncTriggersAndRls.cs` with this full code. The synced-tables list is the single source of truth for both triggers and RLS; `refresh_tokens` and the Identity tables are intentionally excluded (not user-synced data).

  ```csharp
  using Microsoft.EntityFrameworkCore.Migrations;

  #nullable disable

  namespace Tmap.Api.Infrastructure.Migrations;

  /// <inheritdoc />
  public partial class SyncTriggersAndRls : Migration
  {
      private static readonly string[] SyncedTables =
      [
          "tasks", "subtasks", "projects", "note_groups", "notes",
          "recurrence_rules", "recurrence_exceptions", "focus_sessions",
          "daily_plans", "user_settings"
      ];

      /// <inheritdoc />
      protected override void Up(MigrationBuilder migrationBuilder)
      {
          // 1) One global sequence feeding change_seq across all synced tables.
          migrationBuilder.Sql("CREATE SEQUENCE IF NOT EXISTS global_change_seq AS bigint START WITH 1 INCREMENT BY 1;");

          // 2) Trigger function: assign next change_seq and refresh updated_at on every write.
          migrationBuilder.Sql(@"
  CREATE OR REPLACE FUNCTION bump_change_seq() RETURNS trigger AS $$
  BEGIN
      NEW.change_seq := nextval('global_change_seq');
      NEW.updated_at := now();
      RETURN NEW;
  END;
  $$ LANGUAGE plpgsql;");

          foreach (var table in SyncedTables)
          {
              // 3) BEFORE INSERT OR UPDATE trigger on every synced table — authoritative for change_seq + updated_at.
              migrationBuilder.Sql($@"
  DROP TRIGGER IF EXISTS trg_bump_change_seq ON ""{table}"";
  CREATE TRIGGER trg_bump_change_seq
      BEFORE INSERT OR UPDATE ON ""{table}""
      FOR EACH ROW EXECUTE FUNCTION bump_change_seq();");

              // 4) Enable RLS + per-table policy: user_id = current_setting('app.user_id', true)::uuid.
              //    'true' (missing_ok) means an unset GUC yields NULL -> no rows (fail-closed), not an error.
              migrationBuilder.Sql($@"ALTER TABLE ""{table}"" ENABLE ROW LEVEL SECURITY;");
              migrationBuilder.Sql($@"ALTER TABLE ""{table}"" FORCE ROW LEVEL SECURITY;");
              migrationBuilder.Sql($@"
  DROP POLICY IF EXISTS tenant_isolation ON ""{table}"";
  CREATE POLICY tenant_isolation ON ""{table}""
      USING (user_id = current_setting('app.user_id', true)::uuid)
      WITH CHECK (user_id = current_setting('app.user_id', true)::uuid);");
          }
      }

      /// <inheritdoc />
      protected override void Down(MigrationBuilder migrationBuilder)
      {
          foreach (var table in SyncedTables)
          {
              migrationBuilder.Sql($@"DROP POLICY IF EXISTS tenant_isolation ON ""{table}"";");
              migrationBuilder.Sql($@"ALTER TABLE ""{table}"" NO FORCE ROW LEVEL SECURITY;");
              migrationBuilder.Sql($@"ALTER TABLE ""{table}"" DISABLE ROW LEVEL SECURITY;");
              migrationBuilder.Sql($@"DROP TRIGGER IF EXISTS trg_bump_change_seq ON ""{table}"";");
          }
          migrationBuilder.Sql("DROP FUNCTION IF EXISTS bump_change_seq();");
          migrationBuilder.Sql("DROP SEQUENCE IF EXISTS global_change_seq;");
      }
  }
  ```

  > **`FORCE ROW LEVEL SECURITY` is required:** the migration/test connection role is the table owner, and Postgres exempts table owners from RLS unless `FORCE` is set. Without `FORCE`, the RLS cross-tenant test (P1-9) would pass spuriously because the owner bypasses the policy. The application/test DB role must therefore **not** be a superuser (superusers bypass RLS even with `FORCE`); Phase P0's Testcontainers/connection uses the default non-superuser app role — confirm it is not `postgres`-superuser, or create a dedicated app role. The elevated test context (`NewElevatedDbContext`, `SystemCurrentUser`) sets `app.user_id` to `SystemUserId`; arrange/assert data must therefore be **owned by the user under test** and the test sets `app.user_id` appropriately when probing RLS (see P1-9).

  > **Critical RLS-vs-elevated-context note for the test harness:** because `FORCE ROW LEVEL SECURITY` applies to *everyone* including `SystemCurrentUser`, `NewElevatedDbContext` arranging two different users' rows will hit the RLS policy keyed on whatever `app.user_id` the connection interceptor set. To let arrange/assert span multiple users, the elevated path must run with RLS effectively open. **Resolution adopted here:** the connection interceptor (P1-8) sets `app.user_id` to the `SystemUserId` for `SystemCurrentUser`, and the `SyncTriggersAndRls` policy additionally allows the system id. Add this OR-clause to each policy (replace the policy creation block above with the version below):
  >
  > ```sql
  > CREATE POLICY tenant_isolation ON "{table}"
  >     USING (
  >         current_setting('app.user_id', true)::uuid = '00000000-0000-0000-0000-000000000001'::uuid
  >         OR user_id = current_setting('app.user_id', true)::uuid)
  >     WITH CHECK (
  >         current_setting('app.user_id', true)::uuid = '00000000-0000-0000-0000-000000000001'::uuid
  >         OR user_id = current_setting('app.user_id', true)::uuid);
  > ```
  >
  > This keeps real (HTTP) requests strictly tenant-scoped while letting the elevated system context arrange/assert any user's data — exactly the role the contract gives `SystemCurrentUser` ("used by migrations/seed/sync"). The RLS cross-tenant test in P1-9 deliberately sets `app.user_id` to userA's id (NOT the system id) to prove DB-level isolation.

- [ ] **Run, expect PASS.** `dotnet test --filter "FullyQualifiedName~ChangeSeqTriggerTests"`

- [ ] **Commit.**
  ```
  feat(api): add SyncTriggersAndRls migration — global_change_seq, bump_change_seq trigger, RLS policies

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  ```

---

### Task P1-8: `UserIdConnectionInterceptor` (sets `app.user_id`) + `OwnershipSaveChangesInterceptor` (stamps/validates `UserId`), wired into `AddPersistence`

- [ ] **Write failing test — write-time ownership (the contract's "(e)" test).** Append to `PersistenceTests.cs`. With a real HTTP-authenticated context (userA), a client-supplied foreign `UserId` on insert must be overridden to the current user.

  ```csharp
  using Microsoft.EntityFrameworkCore;
  using Tmap.Api.Infrastructure;
  using Tmap.Api.Infrastructure.Entities;

  [Collection("db")]
  public class OwnershipInterceptorTests(PostgresFixture fixture) : IntegrationTestBase(fixture)
  {
      [Fact]
      public async Task ClientSupplied_Foreign_UserId_Is_Overridden_On_Insert()
      {
          var userA = await RegisterAsync();
          var foreignUserId = Guid.NewGuid();

          // Resolve a request-scoped AppDbContext bound to userA's HTTP identity.
          await using var ctx = NewScopedDbContextFor(userA);

          var project = new Project
          {
              Id = Guid.NewGuid(),
              UserId = foreignUserId,            // hostile/incorrect client value
              Name = "owned-" + Guid.NewGuid().ToString("N"),
              Rank = "a0",
              CreatedAt = DateTimeOffset.UtcNow
          };
          ctx.Projects.Add(project);
          await ctx.SaveChangesAsync();

          project.UserId.Should().Be(userA.UserId, "the interceptor stamps UserId from ICurrentUser on insert");
          project.UserId.Should().NotBe(foreignUserId);
      }
  }
  ```

  > **Harness helper needed:** `NewScopedDbContextFor(AuthedClient)` resolves an `AppDbContext` whose injected `ICurrentUser` reports that authed user's id (i.e., an HTTP-style scope, not `SystemCurrentUser`). If Phase P0's `IntegrationTestBase` does not yet expose this, add it now as a protected method on `IntegrationTestBase` that builds a service scope with an `ICurrentUser` test double returning `client.UserId` and `IsAuthenticated = true`, configured exactly like `AddPersistence` (so the same interceptors run). Concretely add to `IntegrationTestBase`:
  >
  > ```csharp
  > protected AppDbContext NewScopedDbContextFor(AuthedClient client)
  > {
  >     var services = new ServiceCollection();
  >     services.AddSingleton<ICurrentUser>(new FixedCurrentUser(client.UserId));
  >     services.AddPersistence(Fixture.ConnectionString);
  >     var provider = services.BuildServiceProvider();
  >     // store provider on the base for disposal
  >     return provider.GetRequiredService<AppDbContext>();
  > }
  >
  > private sealed class FixedCurrentUser(Guid id) : ICurrentUser
  > {
  >     public bool IsAuthenticated => true;
  >     public Guid Id => id;
  > }
  > ```

- [ ] **Run, expect FAIL.** `dotnet test --filter "FullyQualifiedName~OwnershipInterceptorTests.ClientSupplied_Foreign_UserId_Is_Overridden_On_Insert"`
  Expect: `Expected project.UserId to be {userA}, but found {foreignUserId}` (no interceptor yet → the client value persists). *Or* an RLS `WITH CHECK` violation `new row violates row-level security policy` — both prove the stamp is missing; the interceptor fixes it by stamping before save.

- [ ] **Minimal impl (full code).** Create `backend/src/Tmap.Api/Infrastructure/Persistence/OwnershipSaveChangesInterceptor.cs`:

  ```csharp
  using Microsoft.EntityFrameworkCore;
  using Microsoft.EntityFrameworkCore.Diagnostics;
  using Tmap.Api.Common;

  namespace Tmap.Api.Infrastructure.Persistence;

  // Stamps UserId from ICurrentUser on inserted IOwnedByUser entries; rejects a mismatched
  // client-supplied UserId on update (request body is never trusted for ownership).
  public sealed class OwnershipSaveChangesInterceptor(ICurrentUser currentUser) : SaveChangesInterceptor
  {
      public override InterceptionResult<int> SavingChanges(
          DbContextEventData eventData, InterceptionResult<int> result)
      {
          Stamp(eventData);
          return base.SavingChanges(eventData, result);
      }

      public override ValueTask<InterceptionResult<int>> SavingChangesAsync(
          DbContextEventData eventData, InterceptionResult<int> result, CancellationToken cancellationToken = default)
      {
          Stamp(eventData);
          return base.SavingChangesAsync(eventData, result, cancellationToken);
      }

      private void Stamp(DbContextEventData eventData)
      {
          var context = eventData.Context;
          if (context is null)
              return;

          var ownerId = currentUser.Id; // fail-closed: throws if unauthenticated

          foreach (var entry in context.ChangeTracker.Entries<IOwnedByUser>())
          {
              switch (entry.State)
              {
                  case EntityState.Added:
                      // Always stamp from the auth context, overriding any client-supplied value.
                      entry.Entity.UserId = ownerId;
                      break;

                  case EntityState.Modified:
                      // Ownership is immutable; reject any attempt to reassign it.
                      if (entry.Entity.UserId != ownerId)
                      {
                          throw new InvalidOperationException(
                              "Cannot change UserId of an owned entity; ownership is server-authoritative.");
                      }
                      break;
              }
          }
      }
  }
  ```

  Create `backend/src/Tmap.Api/Infrastructure/Persistence/UserIdConnectionInterceptor.cs`. This sets the `app.user_id` GUC on connection open from `ICurrentUser`; when not authenticated it clears it (fail-closed → RLS yields no rows).

  ```csharp
  using System.Data.Common;
  using Microsoft.EntityFrameworkCore.Diagnostics;
  using Tmap.Api.Common;

  namespace Tmap.Api.Infrastructure.Persistence;

  // Sets the Postgres 'app.user_id' setting per opened connection from ICurrentUser, so RLS
  // policies (user_id = current_setting('app.user_id', true)::uuid) scope every query — even
  // raw SQL or IgnoreQueryFilters paths. Cleared when not authenticated (RLS then returns no rows).
  public sealed class UserIdConnectionInterceptor(ICurrentUser currentUser) : DbConnectionInterceptor
  {
      public override async Task ConnectionOpenedAsync(
          DbConnection connection, ConnectionEndEventData eventData, CancellationToken cancellationToken = default)
      {
          await ApplyAsync(connection, cancellationToken);
          await base.ConnectionOpenedAsync(connection, eventData, cancellationToken);
      }

      public override void ConnectionOpened(DbConnection connection, ConnectionEndEventData eventData)
      {
          ApplyAsync(connection, CancellationToken.None).GetAwaiter().GetResult();
          base.ConnectionOpened(connection, eventData);
      }

      private async Task ApplyAsync(DbConnection connection, CancellationToken cancellationToken)
      {
          await using var cmd = connection.CreateCommand();
          if (currentUser.IsAuthenticated)
          {
              // set_config(name, value, is_local=false) — session-scoped; reset on next open below.
              cmd.CommandText = "SELECT set_config('app.user_id', @user_id, false);";
              var p = cmd.CreateParameter();
              p.ParameterName = "user_id";
              p.Value = currentUser.Id.ToString();
              cmd.Parameters.Add(p);
          }
          else
          {
              // Clear any value left on a pooled connection -> fail-closed (no rows visible).
              cmd.CommandText = "SELECT set_config('app.user_id', '', false);";
          }
          await cmd.ExecuteNonQueryAsync(cancellationToken);
      }
  }
  ```

  > **Pooling note:** the interceptor runs on **every** `ConnectionOpenedAsync`, and Npgsql resets session state when a pooled connection is returned (`Reset On Close` default), so a stale `app.user_id` cannot leak to the next request. Setting it unconditionally on open (authenticated → id, otherwise empty) guarantees fail-closed regardless of pool reuse.

  Wire both interceptors into `AddPersistence` in `backend/src/Tmap.Api/Infrastructure/Persistence/PersistenceExtensions.cs`:

  ```csharp
  using Microsoft.EntityFrameworkCore;
  using Microsoft.Extensions.DependencyInjection;
  using Tmap.Api.Common;

  namespace Tmap.Api.Infrastructure.Persistence;

  public static class PersistenceExtensions
  {
      public static IServiceCollection AddPersistence(this IServiceCollection services, string connectionString)
      {
          // Interceptors depend on the scoped ICurrentUser, so register them scoped and
          // resolve them when configuring the DbContext (which is itself scoped).
          services.AddScoped<UserIdConnectionInterceptor>();
          services.AddScoped<OwnershipSaveChangesInterceptor>();

          services.AddDbContext<AppDbContext>((sp, options) =>
          {
              options.UseNpgsql(connectionString);
              options.UseSnakeCaseNamingConvention();
              options.AddInterceptors(
                  sp.GetRequiredService<UserIdConnectionInterceptor>(),
                  sp.GetRequiredService<OwnershipSaveChangesInterceptor>());
          });

          return services;
      }
  }
  ```

- [ ] **Run, expect PASS.** `dotnet test --filter "FullyQualifiedName~OwnershipInterceptorTests.ClientSupplied_Foreign_UserId_Is_Overridden_On_Insert"`
  Then re-run P1-7's `ChangeSeqTriggerTests` to confirm no regression: `dotnet test --filter "FullyQualifiedName~ChangeSeqTriggerTests"`

- [ ] **Commit.**
  ```
  feat(api): add UserId connection interceptor and ownership SaveChanges interceptor; wire into AddPersistence

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  ```

---

### Task P1-9: Soft-delete behavior test (`SoftDelete` filter) — the contract's "(c)" test

- [ ] **Write failing test.** Append to `PersistenceTests.cs`. Soft-deleting sets `deleted_at` and the row disappears from normal reads but is visible when **only** the `SoftDelete` filter is disabled (the `Tenant` filter stays on).

  ```csharp
  using Microsoft.EntityFrameworkCore;
  using Tmap.Api.Infrastructure;
  using Tmap.Api.Infrastructure.Entities;

  [Collection("db")]
  public class SoftDeleteFilterTests(PostgresFixture fixture) : IntegrationTestBase(fixture)
  {
      [Fact]
      public async Task SoftDelete_Hides_From_Normal_Reads_But_Visible_With_SoftDelete_Filter_Disabled()
      {
          var user = await RegisterAsync();
          var id = Guid.NewGuid();

          await using (var ctx = NewScopedDbContextFor(user))
          {
              ctx.Projects.Add(new Project
              {
                  Id = id,
                  Name = "sd-" + Guid.NewGuid().ToString("N"),
                  Rank = "a0",
                  CreatedAt = DateTimeOffset.UtcNow
              });
              await ctx.SaveChangesAsync();
          }

          // Soft delete: set DeletedAt (never EF Remove for user data).
          await using (var ctx = NewScopedDbContextFor(user))
          {
              var p = await ctx.Projects.SingleAsync(x => x.Id == id);
              p.DeletedAt = DateTimeOffset.UtcNow;
              await ctx.SaveChangesAsync();
          }

          await using (var ctx = NewScopedDbContextFor(user))
          {
              // Normal read: both Tenant + SoftDelete filters on -> hidden.
              (await ctx.Projects.AnyAsync(x => x.Id == id)).Should().BeFalse();

              // Disable ONLY SoftDelete -> tombstone visible, tenant filter still applied.
              var tombstone = await ctx.Projects
                  .IgnoreQueryFilters([AppDbContext.SoftDeleteFilter])
                  .SingleOrDefaultAsync(x => x.Id == id);
              tombstone.Should().NotBeNull();
              tombstone!.DeletedAt.Should().NotBeNull();
          }
      }
  }
  ```

- [ ] **Run, expect FAIL.** `dotnet test --filter "FullyQualifiedName~SoftDeleteFilterTests.SoftDelete_Hides_From_Normal_Reads_But_Visible_With_SoftDelete_Filter_Disabled"`
  Expect: PASS if P1-5's named filters are correct. If the `IgnoreQueryFilters([name])` overload is unavailable in the installed EF Core 10 build, this fails to compile (`error CS1503` / no overload) — that is the signal to confirm the named-filter API version. (Named filters and selective `IgnoreQueryFilters([...])` are the EF Core 10 feature the contract mandates; do not fall back to a single anonymous filter.)

- [ ] **Minimal impl.** No new production code is expected — P1-5 already configured both named filters and P1-8 wired tenant scoping. If the test surfaces an API mismatch, the fix is confined to the exact `HasQueryFilter(name, expr)` / `IgnoreQueryFilters([name])` call shapes in `AppDbContext` (production) — adjust those, not the filter semantics.

- [ ] **Run, expect PASS.** `dotnet test --filter "FullyQualifiedName~SoftDeleteFilterTests"`

- [ ] **Commit.**
  ```
  test(api): verify soft-delete hides rows from normal reads but tombstone visible via SoftDelete filter bypass

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  ```

---

### Task P1-10: RLS cross-tenant test (the contract's "(d)" test) — DB-level isolation even when EF filters are bypassed

This is the security backstop test: with `app.user_id = userA`, a read of userB's row via raw SQL **and** via `IgnoreQueryFilters` (both EF tenant filter AND soft-delete bypassed) returns **EMPTY**, proving Postgres RLS — not EF — enforces isolation.

- [ ] **Write failing test.** Append to `PersistenceTests.cs`. Arrange two users' rows with `NewElevatedDbContext` (system context, RLS-open per the policy OR-clause), then probe as userA with a context whose connection sets `app.user_id = userA`.

  ```csharp
  using Microsoft.EntityFrameworkCore;
  using Tmap.Api.Infrastructure;
  using Tmap.Api.Infrastructure.Entities;

  [Collection("db")]
  public class RlsCrossTenantTests(PostgresFixture fixture) : IntegrationTestBase(fixture)
  {
      [Fact]
      public async Task UserA_Connection_Cannot_See_UserB_Rows_Even_With_IgnoreQueryFilters_Or_RawSql()
      {
          var userA = await RegisterAsync();
          var userB = await RegisterAsync();
          var userBProjectId = Guid.NewGuid();

          // Arrange userB's row using the elevated/system context (allowed by policy OR-clause).
          await using (var elevated = NewElevatedDbContext())
          {
              elevated.Projects.Add(new Project
              {
                  Id = userBProjectId,
                  UserId = userB.UserId,
                  Name = "B-secret-" + Guid.NewGuid().ToString("N"),
                  Rank = "a0",
                  CreatedAt = DateTimeOffset.UtcNow
              });
              await elevated.SaveChangesAsync();
          }

          // Probe as userA: the connection interceptor sets app.user_id = userA.
          await using var asUserA = NewScopedDbContextFor(userA);

          // (1) EF read with BOTH filters bypassed -> still empty because RLS (DB) blocks it.
          var viaEf = await asUserA.Projects
              .IgnoreQueryFilters([AppDbContext.TenantFilter, AppDbContext.SoftDeleteFilter])
              .Where(p => p.Id == userBProjectId)
              .ToListAsync();
          viaEf.Should().BeEmpty("RLS must block userB's row even when EF query filters are off");

          // (2) Raw SQL read -> still empty because RLS (DB) blocks it.
          var rawCount = await asUserA.Database
              .SqlQuery<int>($"SELECT COUNT(*)::int AS \"Value\" FROM projects WHERE id = {userBProjectId}")
              .SingleAsync();
          rawCount.Should().Be(0, "RLS must block userB's row even for raw SQL");
      }
  }
  ```

  > **Why arrange uses the elevated context but probe does not:** the policy OR-clause (P1-7) lets `app.user_id = SystemUserId` see/write any row — that is `NewElevatedDbContext`'s purpose (arrange/assert across users). The probe context (`NewScopedDbContextFor(userA)`) sets `app.user_id = userA.UserId` (a real, non-system id), so the OR-clause does **not** apply and the policy reduces to `user_id = userA` — userB's row is invisible at the DB layer. This is exactly the contract's "(d)" guarantee.

- [ ] **Run, expect FAIL — *then* PASS.** `dotnet test --filter "FullyQualifiedName~RlsCrossTenantTests.UserA_Connection_Cannot_See_UserB_Rows_Even_With_IgnoreQueryFilters_Or_RawSql"`
  - If RLS is correctly enabled with `FORCE` and a **non-superuser** app role (P1-7), this PASSES immediately.
  - It will **FAIL** (returns userB's row) only if: RLS not `FORCE`d, the app role is a superuser, or `app.user_id` is not being set per connection. Treat any such failure as a real isolation defect — fix `FORCE ROW LEVEL SECURITY` (P1-7) or the DB role (Phase P0 connection config), never by weakening the test.

- [ ] **Minimal impl.** No new production code beyond P1-7/P1-8. If the test reveals the app role is a superuser (RLS bypassed), coordinate the Phase P0 fix: the Testcontainers connection must use a non-superuser role (e.g., `CREATE ROLE app_user LOGIN NOSUPERUSER; GRANT ... ;` run as part of fixture setup, or connect as the default non-superuser). Document this requirement in the test file header comment.

- [ ] **Run, expect PASS.** `dotnet test --filter "FullyQualifiedName~RlsCrossTenantTests"`

- [ ] **Commit.**
  ```
  test(api): prove RLS enforces cross-tenant isolation even via raw SQL and IgnoreQueryFilters

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  ```

---

### Task P1-11: Full-phase green gate

- [ ] **Run the whole persistence suite.** `dotnet test --filter "FullyQualifiedName~Tmap.Api.Tests"` (or the project default). Confirm all P1 classes pass: `SharedContractTests`, `CurrentUserTests`, `EntityShapeTests`, `AppDbContextWiringTests`, `AppDbContextModelTests`, `MigrationSchemaTests`, `ChangeSeqTriggerTests`, `OwnershipInterceptorTests`, `SoftDeleteFilterTests`, `RlsCrossTenantTests`.

- [ ] **Build clean.** `dotnet build` — zero warnings/errors.

- [ ] **Verify migrations apply from scratch.** Drop and re-migrate against a fresh Testcontainers DB (the harness already does this per run); additionally confirm the migration list: `dotnet ef migrations list -p src/Tmap.Api -s src/Tmap.Api` shows exactly `InitialSchema` then `SyncTriggersAndRls`.

- [ ] **Commit (phase close).**
  ```
  chore(api): P1 persistence core green — entities, AppDbContext, change_seq trigger, RLS, isolation tests passing

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  ```

---

## Phase P2 — Auth: Identity, JWT access + refresh, rotation/reuse-detection, lockout, rate limiting

This phase adds authentication: ASP.NET Core Identity (`ApplicationUser : IdentityUser<Guid>`), the `RefreshToken` store, the JWT service (HS256 access token + rotating SHA-256-hashed refresh token with reuse-detection), the `/api/v1/auth/*` endpoints, rate limiting + lockout, security logging, and it completes `IntegrationTestBase.RegisterAsync` (stubbed in P0).

### Task P2-1: `ApplicationUser` + `RefreshToken` entities and EF mapping

- [ ] **Write failing test** — `backend/tests/Tmap.Api.Tests/AuthTests.cs` (new file), in the `"db"` collection:

```csharp
using FluentAssertions;
using Microsoft.EntityFrameworkCore;
using Tmap.Api.Infrastructure.Entities;
using Xunit;

namespace Tmap.Api.Tests;

[Collection("db")]
public class AuthTests(PostgresFixture fixture) : IntegrationTestBase(fixture)
{
    [Fact]
    public async Task RefreshTokens_table_exists_with_owner_and_hash_columns()
    {
        await using var db = NewElevatedDbContext();
        // Should not throw: table + columns mapped & migrated.
        var count = await db.Set<RefreshToken>().CountAsync();
        count.Should().Be(0);
    }

    [Fact]
    public async Task ApplicationUser_carries_TimeZoneId_defaulting_to_UTC()
    {
        await using var db = NewElevatedDbContext();
        var user = new ApplicationUser { Id = Guid.NewGuid(), UserName = "tz@x.io", Email = "tz@x.io" };
        db.Users.Add(user);
        await db.SaveChangesAsync();

        var loaded = await db.Users.AsNoTracking().SingleAsync(u => u.Id == user.Id);
        loaded.TimeZoneId.Should().Be("UTC");
    }
}
```

- [ ] **Run, expect FAIL** — `dotnet test --filter "FullyQualifiedName~AuthTests.RefreshTokens_table_exists_with_owner_and_hash_columns"`. Expected failure: compile error `The type or namespace name 'RefreshToken' could not be found` (and `ApplicationUser` does not contain `TimeZoneId`).
- [ ] **Minimal impl** — `backend/src/Tmap.Api/Infrastructure/Entities/ApplicationUser.cs`:

```csharp
using Microsoft.AspNetCore.Identity;

namespace Tmap.Api.Infrastructure.Entities;

public sealed class ApplicationUser : IdentityUser<Guid>
{
    // IANA tz used for report day-bucketing; never null.
    public string TimeZoneId { get; set; } = "UTC";
}
```

- [ ] **Minimal impl** — `backend/src/Tmap.Api/Infrastructure/Entities/RefreshToken.cs` (NOT synced — no sync columns, not a `SyncEntity`):

```csharp
namespace Tmap.Api.Infrastructure.Entities;

public sealed class RefreshToken
{
    public Guid Id { get; set; }
    public Guid UserId { get; set; }
    // SHA-256 hex of the raw token; raw token never persisted.
    public string TokenHash { get; set; } = "";
    public DateTimeOffset ExpiresAt { get; set; }
    public DateTimeOffset CreatedAt { get; set; }
    public DateTimeOffset? RevokedAt { get; set; }
    // Hash of the token that superseded this one (rotation chain); null until rotated.
    public string? ReplacedByTokenHash { get; set; }
    public string? DeviceInfo { get; set; }
}
```

- [ ] **Modify `AppDbContext`** — change the base type to `IdentityDbContext<ApplicationUser, IdentityRole<Guid>, Guid>` and add the refresh-token set + config. In `backend/src/Tmap.Api/Infrastructure/AppDbContext.cs`:

```csharp
// using Microsoft.AspNetCore.Identity;
// using Microsoft.AspNetCore.Identity.EntityFrameworkCore;
// using Tmap.Api.Infrastructure.Entities;

public sealed class AppDbContext(DbContextOptions<AppDbContext> options, ICurrentUser currentUser)
    : IdentityDbContext<ApplicationUser, IdentityRole<Guid>, Guid>(options)
{
    private readonly ICurrentUser _currentUser = currentUser;

    public DbSet<RefreshToken> RefreshTokens => Set<RefreshToken>();
    // ... existing synced DbSets stay ...

    protected override void OnModelCreating(ModelBuilder b)
    {
        base.OnModelCreating(b); // Identity tables FIRST.

        b.Entity<RefreshToken>(e =>
        {
            e.ToTable("refresh_tokens");
            e.HasKey(x => x.Id);
            e.Property(x => x.TokenHash).IsRequired();
            e.HasIndex(x => x.TokenHash).IsUnique();
            e.HasIndex(x => x.UserId);
            e.HasOne<ApplicationUser>().WithMany().HasForeignKey(x => x.UserId).OnDelete(DeleteBehavior.Cascade);
        });

        // ... existing synced-entity configuration / query filters / change_seq mapping stay ...
    }
}
```

> Note for P1 coordination: `IdentityDbContext`'s base `OnModelCreating` must run before synced-entity config; the snake_case naming convention (set in P1 `UseSnakeCaseNamingConvention()`) renames the `AspNet*` tables — that is acceptable and expected. `RefreshToken` is deliberately NOT an `IOwnedByUser`/`ISyncable`, so P1's ownership/RLS/query filters do **not** apply to it.

- [ ] **Add migration** — from `backend/`: `dotnet ef migrations add AddIdentityAndRefreshTokens -p src/Tmap.Api -s src/Tmap.Api`. Verify the generated migration contains the `asp_net_users` table with a `time_zone_id` text column (default `'UTC'`) and a `refresh_tokens` table with a unique index on `token_hash`.
- [ ] **Run, expect PASS** — `dotnet test --filter "FullyQualifiedName~AuthTests.RefreshTokens_table_exists_with_owner_and_hash_columns"` then `...~AuthTests.ApplicationUser_carries_TimeZoneId_defaulting_to_UTC"`.
- [ ] **Commit:**
```
feat(api): add ApplicationUser, RefreshToken entities and Identity DbContext

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P2-2: Identity registration with password policy (min length ≥ 10) and lockout

- [ ] **Write failing test** — append to `AuthTests.cs`:

```csharp
[Fact]
public async Task Register_rejects_password_shorter_than_10_chars_with_400()
{
    var res = await Client.PostAsJsonAsync("/api/v1/auth/register",
        new { email = $"short-{Guid.NewGuid():N}@x.io", password = "Ab1!xxxx" }); // 8 chars
    res.StatusCode.Should().Be(System.Net.HttpStatusCode.BadRequest);
}
```

- [ ] **Run, expect FAIL** — `dotnet test --filter "FullyQualifiedName~AuthTests.Register_rejects_password_shorter_than_10_chars_with_400"`. Expected failure: `404 Not Found` (endpoint not mapped yet) — assertion fails `Expected ... BadRequest, but found NotFound`.
- [ ] **Minimal impl** — `backend/src/Tmap.Api/Infrastructure/Identity/IdentityConfig.cs` (an extension that wires Identity onto `AppDbContext` with the SP1 policy):

```csharp
using Microsoft.AspNetCore.Identity;
using Tmap.Api.Infrastructure.Entities;

namespace Tmap.Api.Infrastructure.Identity;

public static class IdentityConfig
{
    public static IServiceCollection AddTmapIdentity(this IServiceCollection services)
    {
        services
            .AddIdentityCore<ApplicationUser>(o =>
            {
                // Password policy (SP1 §3.1): length over composition.
                o.Password.RequiredLength = 10;
                o.Password.RequireDigit = false;
                o.Password.RequireLowercase = false;
                o.Password.RequireUppercase = false;
                o.Password.RequireNonAlphanumeric = false;
                o.Password.RequiredUniqueChars = 1;

                o.User.RequireUniqueEmail = true;

                // Brute-force lockout (SP1 §3.2).
                o.Lockout.AllowedForNewUsers = true;
                o.Lockout.MaxFailedAccessAttempts = 5;
                o.Lockout.DefaultLockoutTimeSpan = TimeSpan.FromMinutes(15);
            })
            .AddRoles<IdentityRole<Guid>>()
            .AddEntityFrameworkStores<AppDbContext>()
            .AddDefaultTokenProviders();

        return services;
    }
}
```

> The validation here is enforced by Identity's `CreateAsync`; the FluentValidation request schema (Task P2-7) gives the *fast* 400 with structured ProblemDetails before hitting Identity. We surface the 400 from the endpoint in P2-7; this task only wires Identity and the policy. To make THIS test pass minimally, also do the next two steps (JWT + register endpoint) — they are split into P2-3/P2-4 below, so this test stays RED until P2-7. **Re-order note:** keep this test, but expect it to remain failing (404) until P2-4 maps the route; do not commit a green claim here. Commit the Identity wiring now:

- [ ] **Wire into `Program.cs`** — add `builder.Services.AddTmapIdentity();` after `AddDbContext`.
- [ ] **Run, expect FAIL still (404)** — confirm the test now fails only because the route is unmapped, not because Identity types are missing. `dotnet build` must succeed.
- [ ] **Commit:**
```
feat(api): wire ASP.NET Core Identity with min-length-10 policy and lockout

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P2-3: `IJwtService` — HS256 access token with kid + two-key rotation

- [ ] **Write failing test** — `backend/tests/Tmap.Api.Tests/JwtServiceTests.cs` (new file; pure unit test, no DB, no collection):

```csharp
using System.IdentityModel.Tokens.Jwt;
using System.Security.Claims;
using FluentAssertions;
using Microsoft.Extensions.Options;
using Microsoft.IdentityModel.Tokens;
using Tmap.Api.Infrastructure.Jwt;
using Xunit;

namespace Tmap.Api.Tests;

public class JwtServiceTests
{
    private static JwtService Make(out JwtOptions opts)
    {
        opts = new JwtOptions
        {
            Issuer = "tmap-test",
            Audience = "tmap-clients",
            AccessTokenMinutes = 15,
            RefreshTokenDays = 60,
            ActiveKeyId = "k1",
            SigningKeys = new()
            {
                ["k1"] = "0123456789ABCDEF0123456789ABCDEF",          // 32 bytes
                ["k2"] = "FEDCBA9876543210FEDCBA9876543210",          // second active key
            },
        };
        return new JwtService(Options.Create(opts));
    }

    [Fact]
    public void Access_token_has_kid_header_and_required_claims_no_email()
    {
        var svc = Make(out var opts);
        var userId = Guid.NewGuid();

        var (jwt, expiresAt) = svc.CreateAccessToken(userId);

        var token = new JwtSecurityTokenHandler().ReadJwtToken(jwt);
        token.Header.Kid.Should().Be("k1");
        token.Header.Alg.Should().Be("HS256");
        token.Subject.Should().Be(userId.ToString());
        token.Issuer.Should().Be("tmap-test");
        token.Audiences.Should().Contain("tmap-clients");
        token.Claims.Should().Contain(c => c.Type == "jti");
        token.Claims.Should().Contain(c => c.Type == JwtRegisteredClaimNames.Iat);
        token.Claims.Should().NotContain(c => c.Type == JwtRegisteredClaimNames.Email);
        expiresAt.Should().BeCloseTo(DateTimeOffset.UtcNow.AddMinutes(15), TimeSpan.FromSeconds(5));
    }

    [Fact]
    public void Token_signed_with_secondary_key_validates_against_both_keys()
    {
        var svc = Make(out var opts);
        // Build params that accept either key (kid-based resolution).
        var tvp = JwtBearerSetup.BuildValidationParameters(opts);

        // Force-sign with k2 to simulate post-rotation tokens still being accepted.
        var jwt = svc.CreateAccessTokenWithKey(Guid.NewGuid(), "k2");
        var handler = new JwtSecurityTokenHandler();

        var act = () => handler.ValidateToken(jwt, tvp, out _);
        act.Should().NotThrow();
    }

    [Fact]
    public void Alg_none_token_is_rejected_by_validation_parameters()
    {
        var svc = Make(out var opts);
        var tvp = JwtBearerSetup.BuildValidationParameters(opts);
        // Hand-craft an unsigned token: header alg=none, valid claims.
        var none =
            "eyJhbGciOiJub25lIiwidHlwIjoiSldUIn0." +
            System.Convert.ToBase64String(System.Text.Encoding.UTF8.GetBytes(
                $$"""{"sub":"{{Guid.NewGuid()}}","iss":"tmap-test","aud":"tmap-clients"}""")).TrimEnd('=').Replace('+','-').Replace('/','_') +
            ".";
        var handler = new JwtSecurityTokenHandler();
        var act = () => handler.ValidateToken(none, tvp, out _);
        act.Should().Throw<SecurityTokenException>();
    }
}
```

- [ ] **Run, expect FAIL** — `dotnet test --filter "FullyQualifiedName~JwtServiceTests"`. Expected failure: compile errors `JwtOptions`, `JwtService`, `JwtBearerSetup` not found.
- [ ] **Minimal impl** — `backend/src/Tmap.Api/Infrastructure/Jwt/JwtOptions.cs`:

```csharp
namespace Tmap.Api.Infrastructure.Jwt;

public sealed class JwtOptions
{
    public const string SectionName = "Jwt";

    public string Issuer { get; set; } = "";
    public string Audience { get; set; } = "";
    public int AccessTokenMinutes { get; set; } = 15;
    public int RefreshTokenDays { get; set; } = 60;

    // kid used to SIGN new tokens.
    public string ActiveKeyId { get; set; } = "";

    // kid -> raw secret (>=256-bit / >=32 chars). Two active keys allow rotation
    // without mass logout: sign with ActiveKeyId, validate against ALL listed keys.
    public Dictionary<string, string> SigningKeys { get; set; } = new();
}
```

- [ ] **Minimal impl** — `backend/src/Tmap.Api/Infrastructure/Jwt/IJwtService.cs`:

```csharp
namespace Tmap.Api.Infrastructure.Jwt;

public interface IJwtService
{
    // Signs with the configured ActiveKeyId. Returns the compact JWT and its absolute expiry.
    (string Token, DateTimeOffset ExpiresAt) CreateAccessToken(Guid userId);

    // 256-bit CSPRNG refresh token. Returns the RAW token (return to caller) and its
    // SHA-256 hex hash (the only value persisted).
    (string Raw, string Hash) CreateRefreshToken();

    // Hash an incoming raw refresh token for lookup/compare (SHA-256 hex).
    string HashRefreshToken(string raw);
}
```

- [ ] **Minimal impl** — `backend/src/Tmap.Api/Infrastructure/Jwt/JwtService.cs`:

```csharp
using System.IdentityModel.Tokens.Jwt;
using System.Security.Claims;
using System.Security.Cryptography;
using System.Text;
using Microsoft.Extensions.Options;
using Microsoft.IdentityModel.Tokens;

namespace Tmap.Api.Infrastructure.Jwt;

public sealed class JwtService(IOptions<JwtOptions> options) : IJwtService
{
    private readonly JwtOptions _o = options.Value;

    public (string Token, DateTimeOffset ExpiresAt) CreateAccessToken(Guid userId)
    {
        var token = CreateAccessTokenWithKey(userId, _o.ActiveKeyId);
        var expiresAt = DateTimeOffset.UtcNow.AddMinutes(_o.AccessTokenMinutes);
        return (token, expiresAt);
    }

    // Exposed for tests / rotation drills: sign with a specific kid.
    public string CreateAccessTokenWithKey(Guid userId, string keyId)
    {
        if (!_o.SigningKeys.TryGetValue(keyId, out var secret))
            throw new InvalidOperationException($"Unknown signing key id '{keyId}'.");

        var now = DateTimeOffset.UtcNow;
        var key = new SymmetricSecurityKey(Encoding.UTF8.GetBytes(secret)) { KeyId = keyId };
        var creds = new SigningCredentials(key, SecurityAlgorithms.HmacSha256);

        var claims = new List<Claim>
        {
            new(JwtRegisteredClaimNames.Sub, userId.ToString()),
            new(JwtRegisteredClaimNames.Jti, Guid.NewGuid().ToString()),
            new(JwtRegisteredClaimNames.Iat, now.ToUnixTimeSeconds().ToString(), ClaimValueTypes.Integer64),
        };

        var jwt = new JwtSecurityToken(
            issuer: _o.Issuer,
            audience: _o.Audience,
            claims: claims,
            notBefore: now.UtcDateTime,
            expires: now.AddMinutes(_o.AccessTokenMinutes).UtcDateTime,
            signingCredentials: creds);
        jwt.Header["kid"] = keyId;

        return new JwtSecurityTokenHandler().WriteToken(jwt);
    }

    public (string Raw, string Hash) CreateRefreshToken()
    {
        var bytes = RandomNumberGenerator.GetBytes(32); // 256-bit CSPRNG
        var raw = Base64UrlEncode(bytes);
        return (raw, HashRefreshToken(raw));
    }

    public string HashRefreshToken(string raw)
    {
        var hash = SHA256.HashData(Encoding.UTF8.GetBytes(raw));
        return Convert.ToHexString(hash); // uppercase hex
    }

    private static string Base64UrlEncode(byte[] b) =>
        Convert.ToBase64String(b).TrimEnd('=').Replace('+', '-').Replace('/', '_');
}
```

- [ ] **Minimal impl** — `backend/src/Tmap.Api/Infrastructure/Jwt/JwtBearerSetup.cs` (validation parameters used by both the unit test and `Program.cs`):

```csharp
using System.Text;
using Microsoft.IdentityModel.Tokens;

namespace Tmap.Api.Infrastructure.Jwt;

public static class JwtBearerSetup
{
    public static TokenValidationParameters BuildValidationParameters(JwtOptions o) => new()
    {
        ValidateIssuer = true,
        ValidIssuer = o.Issuer,
        ValidateAudience = true,
        ValidAudience = o.Audience,
        ValidateLifetime = true,
        ValidateIssuerSigningKey = true,
        ValidAlgorithms = [SecurityAlgorithms.HmacSha256], // pin HS256: blocks alg-confusion / alg:none
        ClockSkew = TimeSpan.FromMinutes(2),
        // Resolve the signing key by the token's kid against ALL active keys (rotation).
        IssuerSigningKeyResolver = (token, securityToken, kid, parameters) =>
            o.SigningKeys.TryGetValue(kid ?? "", out var secret)
                ? [new SymmetricSecurityKey(Encoding.UTF8.GetBytes(secret)) { KeyId = kid }]
                : o.SigningKeys.Select(kv =>
                    (SecurityKey)new SymmetricSecurityKey(Encoding.UTF8.GetBytes(kv.Value)) { KeyId = kv.Key }),
        NameClaimType = "sub",
    };
}
```

- [ ] **Run, expect PASS** — `dotnet test --filter "FullyQualifiedName~JwtServiceTests"` (all three).
- [ ] **Commit:**
```
feat(api): add IJwtService HS256 access tokens with kid rotation and CSPRNG refresh tokens

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P2-4: Auth DTOs, validators, and `POST /register` returning a token pair

- [ ] **Write failing test** — append to `AuthTests.cs`:

```csharp
private sealed record TokenPair(string accessToken, string refreshToken, DateTimeOffset accessTokenExpiresAt);

[Fact]
public async Task Register_returns_access_and_refresh_tokens()
{
    var email = $"reg-{Guid.NewGuid():N}@x.io";
    var res = await Client.PostAsJsonAsync("/api/v1/auth/register",
        new { email, password = "Password123!x" });

    res.StatusCode.Should().Be(System.Net.HttpStatusCode.OK);
    var body = await res.Content.ReadFromJsonAsync<TokenPair>();
    body!.accessToken.Should().NotBeNullOrWhiteSpace();
    body.refreshToken.Should().NotBeNullOrWhiteSpace();
    body.accessTokenExpiresAt.Should().BeAfter(DateTimeOffset.UtcNow);
}

[Fact]
public async Task Register_duplicate_email_returns_409()
{
    var email = $"dup-{Guid.NewGuid():N}@x.io";
    (await Client.PostAsJsonAsync("/api/v1/auth/register", new { email, password = "Password123!x" }))
        .StatusCode.Should().Be(System.Net.HttpStatusCode.OK);
    var second = await Client.PostAsJsonAsync("/api/v1/auth/register", new { email, password = "Password123!x" });
    second.StatusCode.Should().Be(System.Net.HttpStatusCode.Conflict);
}
```

- [ ] **Run, expect FAIL** — `dotnet test --filter "FullyQualifiedName~AuthTests.Register_returns_access_and_refresh_tokens"`. Expected failure: `404 Not Found`.
- [ ] **Minimal impl** — `backend/src/Tmap.Api/Features/Auth/AuthDtos.cs`:

```csharp
namespace Tmap.Api.Features.Auth;

public sealed record RegisterRequest(string Email, string Password);
public sealed record LoginRequest(string Email, string Password);
// Native clients send the refresh token in the body; web omits it (cookie). Nullable for that reason.
public sealed record RefreshRequest(string? RefreshToken);
public sealed record LogoutRequest(string? RefreshToken);

public sealed record TokenPairResponse(string AccessToken, string RefreshToken, DateTimeOffset AccessTokenExpiresAt);
public sealed record MeResponse(Guid Id, string Email, string TimeZoneId);
```

- [ ] **Minimal impl** — `backend/src/Tmap.Api/Features/Auth/AuthValidators.cs`:

```csharp
using FluentValidation;

namespace Tmap.Api.Features.Auth;

public sealed class RegisterRequestValidator : AbstractValidator<RegisterRequest>
{
    public RegisterRequestValidator()
    {
        RuleFor(x => x.Email).NotEmpty().EmailAddress().MaximumLength(256);
        RuleFor(x => x.Password).NotEmpty().MinimumLength(10).MaximumLength(256);
    }
}

public sealed class LoginRequestValidator : AbstractValidator<LoginRequest>
{
    public LoginRequestValidator()
    {
        RuleFor(x => x.Email).NotEmpty().EmailAddress();
        RuleFor(x => x.Password).NotEmpty();
    }
}
```

- [ ] **Minimal impl** — `backend/src/Tmap.Api/Features/Auth/RefreshCookie.cs` (web cookie helper; tests use the body path, but the helper ships now so §3.4 web/native split is real, not TBD):

```csharp
namespace Tmap.Api.Features.Auth;

public static class RefreshCookie
{
    public const string Name = "tmap_rt";

    public static void Write(HttpResponse res, string rawRefreshToken, DateTimeOffset expiresAt) =>
        res.Cookies.Append(Name, rawRefreshToken, new CookieOptions
        {
            HttpOnly = true,
            Secure = true,
            SameSite = SameSiteMode.Strict,
            Path = "/api/v1/auth", // scoped to auth routes only
            Expires = expiresAt,
        });

    public static void Clear(HttpResponse res) =>
        res.Cookies.Append(Name, "", new CookieOptions
        {
            HttpOnly = true,
            Secure = true,
            SameSite = SameSiteMode.Strict,
            Path = "/api/v1/auth",
            Expires = DateTimeOffset.UnixEpoch,
        });

    // Native sends in body; web sends via cookie. Body wins when present.
    public static string? Resolve(HttpRequest req, string? bodyToken) =>
        !string.IsNullOrEmpty(bodyToken) ? bodyToken
        : req.Cookies.TryGetValue(Name, out var c) && !string.IsNullOrEmpty(c) ? c
        : null;
}
```

- [ ] **Minimal impl** — `backend/src/Tmap.Api/Features/Auth/AuthEndpoints.cs` (register handler only for now; other handlers added in P2-5/6/8 — full register code, no placeholders):

```csharp
using Microsoft.AspNetCore.Identity;
using Microsoft.AspNetCore.Mvc;
using Serilog;
using Tmap.Api.Common;
using Tmap.Api.Common.Validation;
using Tmap.Api.Infrastructure;
using Tmap.Api.Infrastructure.Entities;
using Tmap.Api.Infrastructure.Jwt;

namespace Tmap.Api.Features.Auth;

public static class AuthEndpoints
{
    public static RouteGroupBuilder MapAuthEndpoints(this IEndpointRouteBuilder app)
    {
        var group = app.MapGroup("/api/v1/auth").WithTags("Auth");

        group.MapPost("/register", Register)
            .AddEndpointFilter<ValidationFilter<RegisterRequest>>()
            .RequireRateLimiting(RateLimitPolicies.AuthByIpAndEmail)
            .AllowAnonymous();

        return group;
    }

    private static async Task<IResult> Register(
        RegisterRequest req,
        UserManager<ApplicationUser> users,
        AppDbContext db,
        IJwtService jwt,
        HttpContext http)
    {
        var existing = await users.FindByEmailAsync(req.Email);
        if (existing is not null)
            // Register enumeration is an accepted v1 trade-off (spec §3.5), compensated by rate limiting.
            return Results.Problem(statusCode: StatusCodes.Status409Conflict, title: "Email already registered.");

        var user = new ApplicationUser { Id = Guid.CreateVersion7(), UserName = req.Email, Email = req.Email };
        var result = await users.CreateAsync(user, req.Password);
        if (!result.Succeeded)
            return Results.ValidationProblem(result.Errors.GroupBy(e => e.Code)
                .ToDictionary(g => g.Key, g => g.Select(e => e.Description).ToArray()));

        var pair = await AuthTokenIssuer.IssueAsync(user.Id, db, jwt, http);
        Log.ForContext("UserId", user.Id).ForContext("Ip", http.Connection.RemoteIpAddress?.ToString())
            .Information("auth.register.success");
        return Results.Ok(pair);
    }
}
```

- [ ] **Minimal impl** — `AuthTokenIssuer` (shared issue/rotate logic, in `AuthEndpoints.cs` same namespace; centralizes token-pair creation + cookie write so register/login/refresh share one code path — written in full):

```csharp
namespace Tmap.Api.Features.Auth;

using Tmap.Api.Infrastructure;
using Tmap.Api.Infrastructure.Entities;
using Tmap.Api.Infrastructure.Jwt;

internal static class AuthTokenIssuer
{
    public static async Task<TokenPairResponse> IssueAsync(
        Guid userId, AppDbContext db, IJwtService jwt, HttpContext http)
    {
        var (access, accessExp) = jwt.CreateAccessToken(userId);
        var (rawRefresh, refreshHash) = jwt.CreateRefreshToken();
        var now = DateTimeOffset.UtcNow;

        db.RefreshTokens.Add(new RefreshToken
        {
            Id = Guid.CreateVersion7(),
            UserId = userId,
            TokenHash = refreshHash,
            CreatedAt = now,
            ExpiresAt = now.AddDays(jwt is JwtService ? 60 : 60), // RefreshTokenDays via options below
            DeviceInfo = http.Request.Headers.UserAgent.ToString() is { Length: > 0 } ua ? ua[..Math.Min(ua.Length, 256)] : null,
        });
        await db.SaveChangesAsync();

        RefreshCookie.Write(http.Response, rawRefresh, now.AddDays(60));
        return new TokenPairResponse(access, rawRefresh, accessExp);
    }
}
```

> **Refinement (do in this same step, no separate commit):** replace the inline `60` with the configured `RefreshTokenDays`. Inject `IOptions<JwtOptions>` into `IssueAsync` (add a `JwtOptions o` parameter and pass `Options.Create`/the resolved options from the endpoint), and use `now.AddDays(o.RefreshTokenDays)` for both `ExpiresAt` and the cookie expiry. Final signature: `IssueAsync(Guid userId, AppDbContext db, IJwtService jwt, JwtOptions jwtOptions, HttpContext http)`. Pass `jwtOptions` from each handler via an injected `IOptions<JwtOptions>`.

- [ ] **Map in `Program.cs`** — after building `app`: `app.MapAuthEndpoints();`. Register FluentValidation: `builder.Services.AddValidatorsFromAssemblyContaining<RegisterRequestValidator>();`. Register `builder.Services.AddScoped<IJwtService, JwtService>();` and `builder.Services.Configure<JwtOptions>(builder.Configuration.GetSection(JwtOptions.SectionName));`.
- [ ] **Provide test config** — in the P0 `WebApplicationFactory<Program>` override (test project), add JWT config so tokens can be issued. Set via `ConfigureAppConfiguration` an in-memory collection:

```csharp
["Jwt:Issuer"] = "tmap-test",
["Jwt:Audience"] = "tmap-clients",
["Jwt:AccessTokenMinutes"] = "15",
["Jwt:RefreshTokenDays"] = "60",
["Jwt:ActiveKeyId"] = "k1",
["Jwt:SigningKeys:k1"] = "0123456789ABCDEF0123456789ABCDEF",
["Jwt:SigningKeys:k2"] = "FEDCBA9876543210FEDCBA9876543210",
```

> Coordination note: this is the only P2 change to the P0 factory beyond `RegisterAsync`. If P0's factory does not yet expose a config hook, add `ConfigureAppConfiguration((_, cfg) => cfg.AddInMemoryCollection(...))` to it.

- [ ] **Run, expect PASS** — `dotnet test --filter "FullyQualifiedName~AuthTests.Register_returns_access_and_refresh_tokens"` and `...~AuthTests.Register_duplicate_email_returns_409"` and `...~AuthTests.Register_rejects_password_shorter_than_10_chars_with_400"` (the P2-2 test now goes green via the validation filter).
- [ ] **Commit:**
```
feat(api): add POST /auth/register issuing access+refresh token pair

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P2-5: `POST /login` (generic 401, constant-time dummy hash) + `GET /me` (protected)

- [ ] **Write failing test** — append to `AuthTests.cs`:

```csharp
[Fact]
public async Task Login_then_access_me_returns_profile()
{
    var email = $"login-{Guid.NewGuid():N}@x.io";
    await Client.PostAsJsonAsync("/api/v1/auth/register", new { email, password = "Password123!x" });

    var login = await Client.PostAsJsonAsync("/api/v1/auth/login", new { email, password = "Password123!x" });
    login.StatusCode.Should().Be(System.Net.HttpStatusCode.OK);
    var pair = await login.Content.ReadFromJsonAsync<TokenPair>();

    using var req = new HttpRequestMessage(HttpMethod.Get, "/api/v1/auth/me");
    req.Headers.Authorization = new("Bearer", pair!.accessToken);
    var me = await Client.SendAsync(req);
    me.StatusCode.Should().Be(System.Net.HttpStatusCode.OK);
    var profile = await me.Content.ReadFromJsonAsync<JsonElement>();
    profile.GetProperty("email").GetString().Should().Be(email);
}

[Fact]
public async Task Me_without_token_returns_401()
{
    var res = await Client.GetAsync("/api/v1/auth/me");
    res.StatusCode.Should().Be(System.Net.HttpStatusCode.Unauthorized);
}

[Fact]
public async Task Login_unknown_email_and_wrong_password_both_return_generic_401()
{
    var email = $"gen-{Guid.NewGuid():N}@x.io";
    await Client.PostAsJsonAsync("/api/v1/auth/register", new { email, password = "Password123!x" });

    var unknown = await Client.PostAsJsonAsync("/api/v1/auth/login",
        new { email = $"nobody-{Guid.NewGuid():N}@x.io", password = "Password123!x" });
    var wrong = await Client.PostAsJsonAsync("/api/v1/auth/login",
        new { email, password = "WrongPassword!9" });

    unknown.StatusCode.Should().Be(System.Net.HttpStatusCode.Unauthorized);
    wrong.StatusCode.Should().Be(System.Net.HttpStatusCode.Unauthorized);
    // Bodies must be identical (no enumeration via message).
    var ub = await unknown.Content.ReadAsStringAsync();
    var wb = await wrong.Content.ReadAsStringAsync();
    ub.Should().Be(wb);
}
```

> Requires `using System.Text.Json;` at the top of `AuthTests.cs`.

- [ ] **Run, expect FAIL** — `dotnet test --filter "FullyQualifiedName~AuthTests.Login_then_access_me_returns_profile"`. Expected: `404` for `/login` (not mapped) → assertion fails.
- [ ] **Minimal impl** — add the `Login` and `Me` handlers + maps to `AuthEndpoints.cs`. In `MapAuthEndpoints`:

```csharp
group.MapPost("/login", Login)
    .AddEndpointFilter<ValidationFilter<LoginRequest>>()
    .RequireRateLimiting(RateLimitPolicies.AuthByIpAndEmail)
    .AllowAnonymous();

group.MapGet("/me", Me).RequireAuthorization();
```

Handlers:

```csharp
private static async Task<IResult> Login(
    LoginRequest req,
    UserManager<ApplicationUser> users,
    AppDbContext db,
    IJwtService jwt,
    IOptions<JwtOptions> jwtOptions,
    HttpContext http)
{
    var sourceIp = http.Connection.RemoteIpAddress?.ToString();
    var user = await users.FindByEmailAsync(req.Email);

    if (user is null)
    {
        // Constant-time path: spend the same work hashing a dummy password so timing
        // does not reveal whether the email exists (spec §3.5).
        users.PasswordHasher.VerifyHashedPassword(
            new ApplicationUser(),
            DummyPasswordHash,
            req.Password);
        Log.ForContext("Ip", sourceIp).Information("auth.login.failure {Reason}", "unknown_email");
        return GenericUnauthorized();
    }

    if (await users.IsLockedOutAsync(user))
    {
        Log.ForContext("UserId", user.Id).ForContext("Ip", sourceIp)
            .Warning("auth.login.lockout");
        return GenericUnauthorized();
    }

    var ok = await users.CheckPasswordAsync(user, req.Password);
    if (!ok)
    {
        await users.AccessFailedAsync(user); // increments lockout counter
        if (await users.IsLockedOutAsync(user))
            Log.ForContext("UserId", user.Id).ForContext("Ip", sourceIp).Warning("auth.login.lockout");
        else
            Log.ForContext("UserId", user.Id).ForContext("Ip", sourceIp)
                .Information("auth.login.failure {Reason}", "wrong_password");
        return GenericUnauthorized();
    }

    await users.ResetAccessFailedCountAsync(user);
    var pair = await AuthTokenIssuer.IssueAsync(user.Id, db, jwt, jwtOptions.Value, http);
    Log.ForContext("UserId", user.Id).ForContext("Ip", sourceIp).Information("auth.login.success");
    return Results.Ok(pair);
}

private static async Task<IResult> Me(ICurrentUser currentUser, UserManager<ApplicationUser> users)
{
    var user = await users.FindByIdAsync(currentUser.Id.ToString());
    if (user is null) return Results.Unauthorized();
    return Results.Ok(new MeResponse(user.Id, user.Email ?? "", user.TimeZoneId));
}

// Identical body for unknown-email and wrong-password — no enumeration.
private static IResult GenericUnauthorized() =>
    Results.Problem(statusCode: StatusCodes.Status401Unauthorized, title: "Invalid credentials.");

// Precomputed PBKDF2 hash of a fixed string under the default Identity hasher; used only
// for the constant-time no-such-user path. Not a real credential.
private static readonly string DummyPasswordHash =
    new Microsoft.AspNetCore.Identity.PasswordHasher<ApplicationUser>()
        .HashPassword(new ApplicationUser(), "constant-time-dummy-password");
```

> Add `using Microsoft.Extensions.Options;` and `using Tmap.Api.Infrastructure.Jwt;` to `AuthEndpoints.cs`. Computing `DummyPasswordHash` once at type-init is acceptable; using a *precomputed* hash keeps the verify-cost constant and avoids hashing-on-every-miss being cheaper than a real verify.

- [ ] **Run, expect PASS** — `dotnet test --filter "FullyQualifiedName~AuthTests.Login_then_access_me_returns_profile"`, `...~AuthTests.Me_without_token_returns_401"`, `...~AuthTests.Login_unknown_email_and_wrong_password_both_return_generic_401"`.
- [ ] **Commit:**
```
feat(api): add POST /auth/login (generic 401, constant-time) and GET /auth/me

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P2-6: `POST /refresh` — rotation + reuse-detection (family revoke)

- [ ] **Write failing test** — append to `AuthTests.cs`:

```csharp
[Fact]
public async Task Refresh_rotates_old_token_becomes_invalid()
{
    var email = $"rot-{Guid.NewGuid():N}@x.io";
    var reg = await (await Client.PostAsJsonAsync("/api/v1/auth/register",
        new { email, password = "Password123!x" })).Content.ReadFromJsonAsync<TokenPair>();

    var first = await Client.PostAsJsonAsync("/api/v1/auth/refresh", new { refreshToken = reg!.refreshToken });
    first.StatusCode.Should().Be(System.Net.HttpStatusCode.OK);
    var rotated = await first.Content.ReadFromJsonAsync<TokenPair>();
    rotated!.refreshToken.Should().NotBe(reg.refreshToken);

    // Old token is now invalid.
    var reuseOld = await Client.PostAsJsonAsync("/api/v1/auth/refresh", new { refreshToken = reg.refreshToken });
    reuseOld.StatusCode.Should().Be(System.Net.HttpStatusCode.Unauthorized);
}

[Fact]
public async Task Refresh_reuse_of_revoked_token_revokes_whole_family()
{
    var email = $"reuse-{Guid.NewGuid():N}@x.io";
    var reg = await (await Client.PostAsJsonAsync("/api/v1/auth/register",
        new { email, password = "Password123!x" })).Content.ReadFromJsonAsync<TokenPair>();

    // Rotate once: reg.refreshToken -> r2.
    var r2 = await (await Client.PostAsJsonAsync("/api/v1/auth/refresh",
        new { refreshToken = reg!.refreshToken })).Content.ReadFromJsonAsync<TokenPair>();

    // Attacker replays the already-rotated (revoked) original -> must revoke the family.
    var reuse = await Client.PostAsJsonAsync("/api/v1/auth/refresh", new { refreshToken = reg.refreshToken });
    reuse.StatusCode.Should().Be(System.Net.HttpStatusCode.Unauthorized);

    // The legitimate current token r2 is now also dead (family revoked).
    var afterFamilyRevoke = await Client.PostAsJsonAsync("/api/v1/auth/refresh",
        new { refreshToken = r2!.refreshToken });
    afterFamilyRevoke.StatusCode.Should().Be(System.Net.HttpStatusCode.Unauthorized);
}
```

- [ ] **Run, expect FAIL** — `dotnet test --filter "FullyQualifiedName~AuthTests.Refresh_rotates_old_token_becomes_invalid"`. Expected: `404` (route unmapped).
- [ ] **Minimal impl** — map and implement `Refresh`. In `MapAuthEndpoints`:

```csharp
group.MapPost("/refresh", Refresh)
    .RequireRateLimiting(RateLimitPolicies.AuthByIpAndEmail)
    .AllowAnonymous();
```

Handler (full rotation + reuse-detection; the "family" is the rotation chain reachable via `ReplacedByTokenHash` from any token belonging to the same user issued at the same root — we revoke ALL of the user's currently-live refresh tokens on reuse, which is the conservative, correct containment for a stolen token):

```csharp
private static async Task<IResult> Refresh(
    RefreshRequest body,
    HttpContext http,
    AppDbContext db,
    IJwtService jwt,
    IOptions<JwtOptions> jwtOptions)
{
    var raw = RefreshCookie.Resolve(http.Request, body.RefreshToken);
    if (string.IsNullOrEmpty(raw)) return GenericUnauthorized();

    var hash = jwt.HashRefreshToken(raw);
    var token = await db.RefreshTokens.SingleOrDefaultAsync(t => t.TokenHash == hash);
    if (token is null) return GenericUnauthorized();

    var now = DateTimeOffset.UtcNow;

    // Reuse-detection: a token presented after it was already revoked/rotated means the
    // chain is compromised -> revoke the entire family (all of the user's live tokens).
    if (token.RevokedAt is not null || token.ExpiresAt <= now)
    {
        var live = await db.RefreshTokens
            .Where(t => t.UserId == token.UserId && t.RevokedAt == null)
            .ToListAsync();
        foreach (var t in live) t.RevokedAt = now;
        await db.SaveChangesAsync();
        Log.ForContext("UserId", token.UserId).ForContext("Ip", http.Connection.RemoteIpAddress?.ToString())
            .Warning("auth.refresh.reuse_detected family_revoked count={Count}", live.Count);
        RefreshCookie.Clear(http.Response);
        return GenericUnauthorized();
    }

    // Rotate: issue a fresh pair, mark this token revoked + replaced.
    var (newRaw, newHash) = jwt.CreateRefreshToken();
    var (access, accessExp) = jwt.CreateAccessToken(token.UserId);

    token.RevokedAt = now;
    token.ReplacedByTokenHash = newHash;
    db.RefreshTokens.Add(new RefreshToken
    {
        Id = Guid.CreateVersion7(),
        UserId = token.UserId,
        TokenHash = newHash,
        CreatedAt = now,
        ExpiresAt = now.AddDays(jwtOptions.Value.RefreshTokenDays),
        DeviceInfo = token.DeviceInfo,
    });
    await db.SaveChangesAsync();

    RefreshCookie.Write(http.Response, newRaw, now.AddDays(jwtOptions.Value.RefreshTokenDays));
    Log.ForContext("UserId", token.UserId).Information("auth.refresh.success");
    return Results.Ok(new TokenPairResponse(access, newRaw, accessExp));
}
```

> `AuthTokenIssuer.IssueAsync` is used by register/login; `Refresh` writes its own row inline because it must also revoke the prior token in the same transaction. Both call `db.SaveChangesAsync()` once. Keep `using Microsoft.EntityFrameworkCore;` in `AuthEndpoints.cs`.

- [ ] **Run, expect PASS** — `dotnet test --filter "FullyQualifiedName~AuthTests.Refresh_rotates_old_token_becomes_invalid"` and `...~AuthTests.Refresh_reuse_of_revoked_token_revokes_whole_family"`.
- [ ] **Commit:**
```
feat(api): add POST /auth/refresh with rotation and family reuse-detection

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P2-7: `POST /logout` and `POST /logout-all`

- [ ] **Write failing test** — append to `AuthTests.cs`:

```csharp
[Fact]
public async Task Logout_revokes_presented_refresh_token()
{
    var email = $"lo-{Guid.NewGuid():N}@x.io";
    var reg = await (await Client.PostAsJsonAsync("/api/v1/auth/register",
        new { email, password = "Password123!x" })).Content.ReadFromJsonAsync<TokenPair>();

    var logout = await Client.PostAsJsonAsync("/api/v1/auth/logout", new { refreshToken = reg!.refreshToken });
    logout.StatusCode.Should().Be(System.Net.HttpStatusCode.NoContent);

    var afterLogout = await Client.PostAsJsonAsync("/api/v1/auth/refresh", new { refreshToken = reg.refreshToken });
    afterLogout.StatusCode.Should().Be(System.Net.HttpStatusCode.Unauthorized);
}

[Fact]
public async Task LogoutAll_revokes_every_refresh_token_for_user()
{
    var email = $"loall-{Guid.NewGuid():N}@x.io";
    var reg = await (await Client.PostAsJsonAsync("/api/v1/auth/register",
        new { email, password = "Password123!x" })).Content.ReadFromJsonAsync<TokenPair>();
    var login = await (await Client.PostAsJsonAsync("/api/v1/auth/login",
        new { email, password = "Password123!x" })).Content.ReadFromJsonAsync<TokenPair>();

    using var req = new HttpRequestMessage(HttpMethod.Post, "/api/v1/auth/logout-all");
    req.Headers.Authorization = new("Bearer", reg!.accessToken);
    (await Client.SendAsync(req)).StatusCode.Should().Be(System.Net.HttpStatusCode.NoContent);

    // Both refresh tokens are now dead.
    (await Client.PostAsJsonAsync("/api/v1/auth/refresh", new { refreshToken = reg.refreshToken }))
        .StatusCode.Should().Be(System.Net.HttpStatusCode.Unauthorized);
    (await Client.PostAsJsonAsync("/api/v1/auth/refresh", new { refreshToken = login!.refreshToken }))
        .StatusCode.Should().Be(System.Net.HttpStatusCode.Unauthorized);
}
```

- [ ] **Run, expect FAIL** — `dotnet test --filter "FullyQualifiedName~AuthTests.Logout_revokes_presented_refresh_token"`. Expected: `404`.
- [ ] **Minimal impl** — map + handlers. In `MapAuthEndpoints`:

```csharp
group.MapPost("/logout", Logout).AllowAnonymous();          // refresh token in body/cookie; no access token required
group.MapPost("/logout-all", LogoutAll).RequireAuthorization();
```

Handlers:

```csharp
private static async Task<IResult> Logout(LogoutRequest body, HttpContext http, AppDbContext db, IJwtService jwt)
{
    var raw = RefreshCookie.Resolve(http.Request, body.RefreshToken);
    if (!string.IsNullOrEmpty(raw))
    {
        var hash = jwt.HashRefreshToken(raw);
        var token = await db.RefreshTokens.SingleOrDefaultAsync(t => t.TokenHash == hash && t.RevokedAt == null);
        if (token is not null)
        {
            token.RevokedAt = DateTimeOffset.UtcNow;
            await db.SaveChangesAsync();
            Log.ForContext("UserId", token.UserId).Information("auth.logout");
        }
    }
    RefreshCookie.Clear(http.Response);
    return Results.NoContent(); // idempotent: always 204, never reveals validity
}

private static async Task<IResult> LogoutAll(ICurrentUser currentUser, HttpContext http, AppDbContext db)
{
    var userId = currentUser.Id;
    var now = DateTimeOffset.UtcNow;
    var live = await db.RefreshTokens.Where(t => t.UserId == userId && t.RevokedAt == null).ToListAsync();
    foreach (var t in live) t.RevokedAt = now;
    await db.SaveChangesAsync();
    Log.ForContext("UserId", userId).Information("auth.logout_all count={Count}", live.Count);
    RefreshCookie.Clear(http.Response);
    return Results.NoContent();
}
```

- [ ] **Run, expect PASS** — both new tests.
- [ ] **Commit:**
```
feat(api): add POST /auth/logout and /auth/logout-all

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P2-8: Lockout after N failed logins

- [ ] **Write failing test** — append to `AuthTests.cs` (lockout threshold = 5 from P2-2; after 5 wrong attempts the account is locked, so even a CORRECT password returns 401 while locked):

```csharp
[Fact]
public async Task Account_locks_out_after_five_failed_logins()
{
    var email = $"lock-{Guid.NewGuid():N}@x.io";
    await Client.PostAsJsonAsync("/api/v1/auth/register", new { email, password = "Password123!x" });

    for (var i = 0; i < 5; i++)
    {
        var bad = await Client.PostAsJsonAsync("/api/v1/auth/login", new { email, password = "Wrong!Password9" });
        bad.StatusCode.Should().Be(System.Net.HttpStatusCode.Unauthorized);
    }

    // Correct password now blocked due to lockout.
    var correctButLocked = await Client.PostAsJsonAsync("/api/v1/auth/login",
        new { email, password = "Password123!x" });
    correctButLocked.StatusCode.Should().Be(System.Net.HttpStatusCode.Unauthorized);

    // Assert at the data layer that the account is actually locked.
    await using var db = NewElevatedDbContext();
    var user = await db.Users.AsNoTracking().SingleAsync(u => u.NormalizedEmail == email.ToUpperInvariant());
    user.LockoutEnd.Should().NotBeNull();
    user.LockoutEnd!.Value.Should().BeAfter(DateTimeOffset.UtcNow);
}
```

> This test exercises behavior already implemented in P2-5's `Login` (it calls `AccessFailedAsync`/`IsLockedOutAsync`). If P2-5 was implemented correctly, this test passes immediately — that is the intended outcome. If it FAILS, the `Login` handler is missing the lockout-counter calls; fix `Login` (do not weaken the test).

- [ ] **Run, expect PASS (or FAIL → fix Login)** — `dotnet test --filter "FullyQualifiedName~AuthTests.Account_locks_out_after_five_failed_logins"`. If red, the failure is the missing `AccessFailedAsync` path; add it to `Login` (matching P2-5), rerun to green.

> **Rate-limit interaction caveat:** five rapid `/login` calls from the same test client share one source IP and email key. Confirm the sliding-window limit configured in P2-9 permits ≥ 6 requests per window for the login policy, or these five attempts + the verification call will themselves hit `429` and mask lockout. The P2-9 window is sized (≥ 10/window) accordingly; if this test goes `429`, raise the permit count in `RateLimitPolicies`, not the test.

- [ ] **Commit (if any fix was needed; otherwise fold the test into the P2-9 commit):**
```
test(api): verify account lockout after five failed logins

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P2-9: Rate limiting (sliding window) keyed by IP + email → 429

- [ ] **Write failing test** — append to `AuthTests.cs`:

```csharp
[Fact]
public async Task Auth_endpoints_return_429_when_rate_limit_exceeded()
{
    // Hammer register beyond the per-window permit for one IP+email key.
    var email = $"rl-{Guid.NewGuid():N}@x.io";
    var statuses = new List<int>();
    for (var i = 0; i < 30; i++)
    {
        var res = await Client.PostAsJsonAsync("/api/v1/auth/register", new { email, password = "Password123!x" });
        statuses.Add((int)res.StatusCode);
    }
    statuses.Should().Contain(StatusCodes.Status429TooManyRequests);
}
```

> Requires `using Microsoft.AspNetCore.Http;` for `StatusCodes`.

- [ ] **Run, expect FAIL** — `dotnet test --filter "FullyQualifiedName~AuthTests.Auth_endpoints_return_429_when_rate_limit_exceeded"`. Expected: no `429` present (rate limiting not configured) → assertion fails. (Note: `RateLimitPolicies.AuthByIpAndEmail` is already referenced by the endpoints from P2-4; if the policy/middleware does not exist yet, this manifests as a startup/compile error — implement below.)
- [ ] **Minimal impl** — `backend/src/Tmap.Api/Common/RateLimitPolicies.cs` (P2 owns the auth policy; if P1 created this file for other policies, ADD the constant + the partitioner without removing P1's):

```csharp
using System.Threading.RateLimiting;
using Microsoft.AspNetCore.RateLimiting;

namespace Tmap.Api.Common;

public static class RateLimitPolicies
{
    public const string AuthByIpAndEmail = "auth-ip-email";

    public static IServiceCollection AddTmapRateLimiting(this IServiceCollection services)
    {
        services.AddRateLimiter(options =>
        {
            options.RejectionStatusCode = StatusCodes.Status429TooManyRequests;

            options.AddPolicy(AuthByIpAndEmail, httpContext =>
            {
                var ip = httpContext.Connection.RemoteIpAddress?.ToString() ?? "unknown-ip";
                var email = ResolveEmailKey(httpContext);
                var partitionKey = $"{ip}|{email}";

                return RateLimitPartition.GetSlidingWindowLimiter(partitionKey, _ => new SlidingWindowRateLimiterOptions
                {
                    // Sized so the lockout test (5 fails + 1 verify) is not throttled, while the
                    // dedicated 429 test (30 calls) trips. Tune per environment.
                    PermitLimit = 10,
                    Window = TimeSpan.FromMinutes(1),
                    SegmentsPerWindow = 6,
                    QueueLimit = 0,
                    QueueProcessingOrder = QueueProcessingOrder.OldestFirst,
                });
            });
        });

        return services;
    }

    // Key by email when the request body carries one (register/login/refresh-by-body),
    // so attackers can't dodge the limit by rotating only IP or only email.
    private static string ResolveEmailKey(HttpContext ctx)
    {
        if (ctx.Items.TryGetValue("rl_email", out var cached) && cached is string s)
            return s;
        return "no-email";
    }
}
```

> **Email-key extraction:** minimal-API rate-limiting partitioners run before model binding, so the body isn't available in the partitioner. Add a tiny middleware (in `Program.cs`, before `UseRateLimiter`) that, for the three auth paths, buffers + peeks the JSON body for an `email` field and stashes it in `HttpContext.Items["rl_email"]`. Full middleware:

```csharp
app.Use(async (ctx, next) =>
{
    var path = ctx.Request.Path.Value ?? "";
    if (HttpMethods.IsPost(ctx.Request.Method) &&
        (path.EndsWith("/auth/login") || path.EndsWith("/auth/register") || path.EndsWith("/auth/refresh")))
    {
        ctx.Request.EnableBuffering();
        try
        {
            using var doc = await System.Text.Json.JsonDocument.ParseAsync(ctx.Request.Body, default, ctx.RequestAborted);
            if (doc.RootElement.TryGetProperty("email", out var e) && e.ValueKind == System.Text.Json.JsonValueKind.String)
                ctx.Items["rl_email"] = e.GetString()!.ToLowerInvariant();
        }
        catch (System.Text.Json.JsonException) { /* not JSON / no email: fall back to IP-only key */ }
        ctx.Request.Body.Position = 0; // rewind for model binding
    }
    await next();
});
```

- [ ] **Wire into `Program.cs`** — `builder.Services.AddTmapRateLimiting();` (services), then in the pipeline: the body-peek middleware (above) → `app.UseRateLimiter();` → routing/auth. Order: `UseRouting` (if explicit) → body-peek → `UseRateLimiter` → `UseAuthentication`/`UseAuthorization` → endpoints. The endpoints already declare `.RequireRateLimiting(RateLimitPolicies.AuthByIpAndEmail)` from P2-4/5/6.
- [ ] **Run, expect PASS** — `dotnet test --filter "FullyQualifiedName~AuthTests.Auth_endpoints_return_429_when_rate_limit_exceeded"`. Then re-run the lockout test `...~AuthTests.Account_locks_out_after_five_failed_logins"` to confirm it is NOT throttled (permit limit 10 > 6 calls). If throttled, raise `PermitLimit`.
- [ ] **Commit:**
```
feat(api): add sliding-window rate limiting on auth endpoints keyed by ip+email

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P2-10: JwtBearer authentication wired in `Program.cs` (pinned validation)

- [ ] **Write failing test** — append to `AuthTests.cs` (proves the pinned params actually reject a token signed with an unknown key, not just an unsigned one — i.e., real JwtBearer middleware is active):

```csharp
[Fact]
public async Task Me_with_token_signed_by_unknown_key_returns_401()
{
    var email = $"badkey-{Guid.NewGuid():N}@x.io";
    var reg = await (await Client.PostAsJsonAsync("/api/v1/auth/register",
        new { email, password = "Password123!x" })).Content.ReadFromJsonAsync<TokenPair>();

    // Tamper: flip a character in the signature segment.
    var parts = reg!.accessToken.Split('.');
    parts[2] = parts[2].Length > 0
        ? (parts[2][0] == 'A' ? "B" : "A") + parts[2][1..]
        : "AAAA";
    var tampered = string.Join('.', parts);

    using var req = new HttpRequestMessage(HttpMethod.Get, "/api/v1/auth/me");
    req.Headers.Authorization = new("Bearer", tampered);
    (await Client.SendAsync(req)).StatusCode.Should().Be(System.Net.HttpStatusCode.Unauthorized);
}
```

- [ ] **Run, expect result** — `dotnet test --filter "FullyQualifiedName~AuthTests.Me_with_token_signed_by_unknown_key_returns_401"`. If JwtBearer is not yet wired in `Program.cs`, every `/me` test (incl. P2-5) would have already failed; this task formalizes and pins the configuration. If `/me` currently returns 200 for a tampered token, that is the failure to fix here.
- [ ] **Minimal impl** — `backend/src/Tmap.Api/Infrastructure/Jwt/JwtBearerSetup.cs` — add the DI extension alongside `BuildValidationParameters`:

```csharp
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.Extensions.Options;

// ... existing usings ...

public static class JwtBearerSetup
{
    // BuildValidationParameters(...) from P2-3 stays as-is.

    public static IServiceCollection AddTmapJwtAuth(this IServiceCollection services)
    {
        services.AddAuthentication(JwtBearerDefaults.AuthenticationScheme)
            .AddJwtBearer(o =>
            {
                // Resolve options at startup; signing keys/issuer/audience come from config.
                var sp = services.BuildServiceProvider();
                var jwtOptions = sp.GetRequiredService<IOptions<JwtOptions>>().Value;
                o.MapInboundClaims = false;          // keep 'sub' as 'sub' (matches CurrentUser)
                o.TokenValidationParameters = BuildValidationParameters(jwtOptions);
                o.Events = new JwtBearerEvents
                {
                    OnAuthenticationFailed = ctx =>
                    {
                        Serilog.Log.ForContext("Ip", ctx.HttpContext.Connection.RemoteIpAddress?.ToString())
                            .Information("auth.token.invalid {Reason}", ctx.Exception.GetType().Name);
                        return Task.CompletedTask;
                    },
                };
            });
        services.AddAuthorization();
        return services;
    }
}
```

> **Avoid `BuildServiceProvider` smell:** prefer binding `JwtOptions` directly from `IConfiguration` inside the extension by passing `IConfiguration` in: `AddTmapJwtAuth(this IServiceCollection services, IConfiguration config)`, then `var jwtOptions = config.GetSection(JwtOptions.SectionName).Get<JwtOptions>()!;`. Use that form. Final call site in `Program.cs`: `builder.Services.AddTmapJwtAuth(builder.Configuration);`.

- [ ] **Wire into `Program.cs`** — `builder.Services.AddTmapJwtAuth(builder.Configuration);` (after `Configure<JwtOptions>`). In the pipeline ensure `app.UseAuthentication(); app.UseAuthorization();` are present (after `UseRateLimiter`). `CurrentUser` (P1) reads the `sub` claim; `MapInboundClaims = false` keeps it literally `sub`.
- [ ] **Run, expect PASS** — the tampered-token test plus a full `dotnet test --filter "FullyQualifiedName~AuthTests"` regression and `dotnet test --filter "FullyQualifiedName~JwtServiceTests"`.
- [ ] **Commit:**
```
feat(api): wire JwtBearer auth with pinned HS256 validation parameters

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P2-11: Complete `IntegrationTestBase.RegisterAsync` (the P0 stub)

- [ ] **Write failing test** — add a self-test in `AuthTests.cs` that proves the helper returns a usable authed client whose Bearer token reaches a protected endpoint:

```csharp
[Fact]
public async Task RegisterAsync_yields_authed_client_reaching_me()
{
    var authed = await RegisterAsync();
    authed.UserId.Should().NotBe(Guid.Empty);

    var me = await authed.Client.GetAsync("/api/v1/auth/me");
    me.StatusCode.Should().Be(System.Net.HttpStatusCode.OK);

    var profile = await me.Content.ReadFromJsonAsync<JsonElement>();
    Guid.Parse(profile.GetProperty("id").GetString()!).Should().Be(authed.UserId);
}
```

- [ ] **Run, expect FAIL** — `dotnet test --filter "FullyQualifiedName~AuthTests.RegisterAsync_yields_authed_client_reaching_me"`. Expected: `NotImplementedException` (P0 stub) or `UserId == Guid.Empty` assertion failure.
- [ ] **Minimal impl** — implement `RegisterAsync` in `backend/tests/Tmap.Api.Tests/IntegrationTestBase.cs` (the signature was fixed by P0: `Task<AuthedClient> RegisterAsync(string? email = null, string password = "Password123!x")`; `AuthedClient` exposes `HttpClient Client` + `Guid UserId`). Replace the stub body:

```csharp
public async Task<AuthedClient> RegisterAsync(string? email = null, string password = "Password123!x")
{
    email ??= $"user-{Guid.NewGuid():N}@tmap.test";

    var registerRes = await Client.PostAsJsonAsync("/api/v1/auth/register", new { email, password });
    registerRes.EnsureSuccessStatusCode();
    var pair = await registerRes.Content.ReadFromJsonAsync<TokenPairDto>()
               ?? throw new InvalidOperationException("register returned no token pair");

    // Fresh HttpClient bound to the same in-process server, with Bearer preset.
    var authedHttp = Factory.CreateClient();
    authedHttp.DefaultRequestHeaders.Authorization =
        new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", pair.AccessToken);

    using var meReq = new HttpRequestMessage(HttpMethod.Get, "/api/v1/auth/me");
    var meRes = await authedHttp.GetAsync("/api/v1/auth/me");
    meRes.EnsureSuccessStatusCode();
    var me = await meRes.Content.ReadFromJsonAsync<MeDto>()
             ?? throw new InvalidOperationException("me returned no profile");

    return new AuthedClient(authedHttp, me.Id);
}

// Local DTOs for the harness (kept private so they don't leak into slice tests).
private sealed record TokenPairDto(string AccessToken, string RefreshToken, DateTimeOffset AccessTokenExpiresAt);
private sealed record MeDto(Guid Id, string Email, string TimeZoneId);
```

> Coordination with P0: `RegisterAsync` resolves the email/password contract above and matches the `register` → `TokenPairResponse` → `me` → `MeResponse` shapes from this phase. If P0 defined `AuthedClient` as a `record AuthedClient(HttpClient Client, Guid UserId)`, the return above fits. `Factory` is the `WebApplicationFactory<Program>` the base already holds; if P0 named it differently (e.g. `_factory`), use that name. JSON property casing: responses serialize camelCase by default, and `System.Text.Json` matches case-insensitively, so `AccessToken`/`accessToken` both bind.

- [ ] **Run, expect PASS** — the self-test, then a full `dotnet test` of the auth suite.
- [ ] **Commit:**
```
test(api): implement IntegrationTestBase.RegisterAsync (register+login+bearer)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P2-12: Serilog auth security-event logging assertion (no secrets leaked)

- [ ] **Write failing test** — append to `AuthTests.cs`. Capture log output via an in-memory Serilog sink injected through the test factory config, then assert security events are emitted and **no token/password appears**. Add to the P0 factory a `List<string> LogMessages` populated by a `Serilog.Sinks.Observable`/delegate sink (or use `Serilog.Sink.InMemory` style). Concretely, expose a static `TestLogSink` the factory configures:

```csharp
[Fact]
public async Task Refresh_reuse_logs_family_revoke_without_leaking_tokens()
{
    TestLogSink.Clear();

    var email = $"log-{Guid.NewGuid():N}@x.io";
    var reg = await (await Client.PostAsJsonAsync("/api/v1/auth/register",
        new { email, password = "Password123!x" })).Content.ReadFromJsonAsync<TokenPair>();
    var r2 = await (await Client.PostAsJsonAsync("/api/v1/auth/refresh",
        new { refreshToken = reg!.refreshToken })).Content.ReadFromJsonAsync<TokenPair>();
    await Client.PostAsJsonAsync("/api/v1/auth/refresh", new { refreshToken = reg.refreshToken }); // reuse

    var log = TestLogSink.Messages;
    log.Should().Contain(m => m.Contains("auth.refresh.reuse_detected"));
    // Never log raw/hashed tokens or passwords.
    log.Should().NotContain(m => m.Contains(reg.refreshToken) || m.Contains(r2!.refreshToken) || m.Contains("Password123!x"));
}
```

- [ ] **Run, expect FAIL** — `dotnet test --filter "FullyQualifiedName~AuthTests.Refresh_reuse_logs_family_revoke_without_leaking_tokens"`. Expected: `TestLogSink` not found (compile) — implement it, OR if the sink exists but the message isn't emitted, the `Contain` assertion fails.
- [ ] **Minimal impl (test harness sink)** — add to `IntegrationTestBase.cs` (or a small `TestLogSink.cs` in the test project):

```csharp
public static class TestLogSink
{
    private static readonly List<string> _messages = new();
    private static readonly object _gate = new();

    public static IReadOnlyList<string> Messages { get { lock (_gate) return _messages.ToList(); } }
    public static void Clear() { lock (_gate) _messages.Clear(); }
    public static void Add(string rendered) { lock (_gate) _messages.Add(rendered); }
}
```

Wire it into Serilog in the P0 factory's `ConfigureServices`/host config (the factory configures Serilog for tests):

```csharp
// In the test WebApplicationFactory: use a delegating sink that forwards rendered messages.
builder.UseSerilog((ctx, lc) => lc
    .MinimumLevel.Information()
    .WriteTo.Sink(new DelegatingSink(evt =>
    {
        var sw = new System.IO.StringWriter();
        evt.RenderMessage(sw);
        // include property values so token-leak assertions are meaningful
        var props = string.Join(" ", evt.Properties.Select(p => $"{p.Key}={p.Value}"));
        TestLogSink.Add($"{sw} {props}");
    })));

// DelegatingSink: a 6-line ILogEventSink that invokes the provided action per event.
internal sealed class DelegatingSink(Action<Serilog.Events.LogEvent> write) : Serilog.Core.ILogEventSink
{
    public void Emit(Serilog.Events.LogEvent logEvent) => write(logEvent);
}
```

> **Production-side guarantee (no code change needed if P2-6 logging is correct):** the `auth.refresh.reuse_detected` and `auth.login.failure` log calls in this phase deliberately log only `UserId`, `Ip`, a `Reason` category, and a `count` — never the raw token, the hash, or the password. This task asserts that invariant. If the test reveals any handler logging a token/password, remove that field from the log call (do not relax the test).

- [ ] **Run, expect PASS** — the new test.
- [ ] **Commit:**
```
test(api): assert auth security logging emits events without leaking secrets

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P2-13: Full-phase green + format gate

- [ ] **Run the entire phase suite** — from `backend/`: `dotnet test --filter "FullyQualifiedName~AuthTests"` then `dotnet test --filter "FullyQualifiedName~JwtServiceTests"`. All green.
- [ ] **Build clean** — `dotnet build` (no warnings introduced by this phase; `Nullable` is enabled, so resolve any CS86xx).
- [ ] **Confirm migration applies on a fresh DB** — the P0 factory runs `MigrateAsync()` on startup; the green integration run already proves `AddIdentityAndRefreshTokens` applies against a real Testcontainers Postgres. No extra step.
- [ ] **Commit (only if any cleanup was needed):**
```
chore(api): finalize P2 auth phase — full suite green

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

**Phase P2 deliverables recap (all behaviors verified by green tests):** Identity on `IdentityUser<Guid>` with min-length-10 password policy and lockout; `RefreshToken` storage; `IJwtService` issuing HS256 access tokens (`sub`/`iss`/`aud`/`exp`/`iat`/`jti`, `kid` header, **no email**, two active keys for rotation) and 256-bit CSPRNG refresh tokens stored SHA-256-hashed; pinned `TokenValidationParameters` (HS256 only, ≤2-min skew, `alg:none`/unknown-key rejected); endpoints `register`/`login`/`refresh`/`logout`/`logout-all`/`me`; rotation with whole-family reuse-detection revoke; generic constant-time 401 for unknown-email and wrong-password; sliding-window rate limiting keyed by IP+email → 429; Serilog security-event logging with no secret leakage; and the completed P0 `IntegrationTestBase.RegisterAsync`.

---

## Phase P3 — Validation/Error Infrastructure + Tasks Slice (exemplar full CRUD)

This phase wires the cross-cutting **error & validation infrastructure** that every other slice depends on, then builds the **Tasks slice end-to-end** as the copy-me exemplar for all subsequent data slices (Subtasks, Projects, Notes, etc.). Files this phase **creates**: `backend/src/Tmap.Api/Common/Validation/ValidationFilter.cs`, `backend/src/Tmap.Api/Common/Errors/ProblemDetailsExtensions.cs`, `backend/src/Tmap.Api/Common/Errors/GlobalExceptionHandler.cs`, `backend/src/Tmap.Api/Features/Tasks/TaskDtos.cs`, `backend/src/Tmap.Api/Features/Tasks/TasksValidator.cs`, `backend/src/Tmap.Api/Features/Tasks/TasksEndpoints.cs`, `backend/src/Tmap.Api/Features/Subtasks/SubtaskDtos.cs`, `backend/src/Tmap.Api/Features/Subtasks/SubtasksValidator.cs`, `backend/src/Tmap.Api/Features/Subtasks/SubtasksEndpoints.cs`, `backend/tests/Tmap.Api.Tests/TasksTests.cs`, `backend/tests/Tmap.Api.Tests/SubtasksTests.cs`. Files this phase **modifies**: `backend/src/Tmap.Api/Program.cs` (register FluentValidation, ProblemDetails, exception handler, map the two route groups).

**Contract-gap notes (read before starting):**
- This phase assumes the P1 Infrastructure pieces exist verbatim: `SyncEntity`, `IOwnedByUser`, `ISyncable`, `ICurrentUser`/`CurrentUser`, `TaskItem`, `Subtask`, `TaskStatus`, the named query filters `"Tenant"` + `"SoftDelete"`, the RLS connection interceptor, and the write-time ownership `SaveChanges` interceptor. P3 does **not** redefine any of these.
- This phase assumes the P2 auth harness method `RegisterAsync(...)` returns an `AuthedClient { HttpClient Client; Guid UserId; }` and the P0 `IntegrationTestBase`/`PostgresFixture` / `NewElevatedDbContext()` exist verbatim.
- `ValidationFilter<T>` code is **copied verbatim** from the shared contract (it is declared as a Phase-P3 deliverable in the contract, so it lives here and every other slice uses it).
- **Rank generation:** the contract does not define a shared `RankGenerator`. To avoid inventing a conflicting shared symbol, P3 generates new ranks **inline, locally** inside the Tasks/Subtasks handlers using a simple midpoint/append scheme over the existing `Rank`/`SortOrder` columns. If P5/P-final wants a shared `LexoRank` helper, that is a future contract addition; P3 stays self-contained and does not export one.

---

### Task P3-1: ValidationFilter<T> endpoint filter (verbatim)

- [ ] **Write the file** `backend/src/Tmap.Api/Common/Validation/ValidationFilter.cs` with the exact contract code:

```csharp
using FluentValidation;

namespace Tmap.Api.Common.Validation;

public sealed class ValidationFilter<T>(IValidator<T> validator) : IEndpointFilter
{
    public async ValueTask<object?> InvokeAsync(EndpointFilterInvocationContext ctx, EndpointFilterDelegate next)
    {
        var arg = ctx.Arguments.OfType<T>().First();
        var r = await validator.ValidateAsync(arg);
        if (!r.IsValid) return TypedResults.ValidationProblem(r.ToDictionary());
        return await next(ctx);
    }
}
```

- [ ] **Run** `dotnet build` and **expect PASS** (compiles; it is referenced by endpoints in later tasks). If `IEndpointFilter`/`TypedResults` are unresolved, the file is missing `using Microsoft.AspNetCore.Http;` — add it (ImplicitUsings may already cover it; verify with the build).
- [ ] **Commit:**

```
chore(api): add ValidationFilter<T> endpoint filter

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P3-2: ProblemDetails wiring extension

- [ ] **Write the file** `backend/src/Tmap.Api/Common/Errors/ProblemDetailsExtensions.cs`. This centralizes the RFC 9457 ProblemDetails configuration so `Program.cs` and tests share one definition:

```csharp
using Microsoft.AspNetCore.Http.Features;

namespace Tmap.Api.Common.Errors;

public static class ProblemDetailsExtensions
{
    /// <summary>
    /// Registers RFC 9457 ProblemDetails generation with consistent extension members
    /// (traceId, instance) used by both the validation filter and the global exception handler.
    /// </summary>
    public static IServiceCollection AddTmapProblemDetails(this IServiceCollection services)
    {
        services.AddProblemDetails(options =>
        {
            options.CustomizeProblemDetails = context =>
            {
                context.ProblemDetails.Instance ??= context.HttpContext.Request.Path;
                context.ProblemDetails.Extensions["traceId"] =
                    context.HttpContext.Features.Get<IHttpActivityFeature>()?.Activity.Id
                    ?? context.HttpContext.TraceIdentifier;
            };
        });

        return services;
    }
}
```

- [ ] **Run** `dotnet build` and **expect PASS**.
- [ ] **Commit:**

```
feat(api): add RFC 9457 ProblemDetails configuration

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P3-3: Global exception handler (maps unhandled exceptions to RFC 9457)

- [ ] **Write the file** `backend/src/Tmap.Api/Common/Errors/GlobalExceptionHandler.cs`. It maps `ICurrentUser`'s fail-closed `InvalidOperationException` and `FluentValidation.ValidationException` to proper status codes, everything else to 500, all as ProblemDetails:

```csharp
using FluentValidation;
using Microsoft.AspNetCore.Diagnostics;
using Microsoft.AspNetCore.Http.Features;

namespace Tmap.Api.Common.Errors;

public sealed class GlobalExceptionHandler(
    IProblemDetailsService problemDetailsService,
    ILogger<GlobalExceptionHandler> logger) : IExceptionHandler
{
    public async ValueTask<bool> TryHandleAsync(
        HttpContext httpContext,
        Exception exception,
        CancellationToken cancellationToken)
    {
        var (status, title) = exception switch
        {
            ValidationException => (StatusCodes.Status400BadRequest, "Validation failed"),
            InvalidOperationException ioe when ioe.Message.Contains("authenticated", StringComparison.OrdinalIgnoreCase)
                => (StatusCodes.Status401Unauthorized, "Unauthorized"),
            _ => (StatusCodes.Status500InternalServerError, "An unexpected error occurred"),
        };

        if (status >= 500)
            logger.LogError(exception, "Unhandled exception processing {Method} {Path}",
                httpContext.Request.Method, httpContext.Request.Path);
        else
            logger.LogWarning(exception, "Handled exception ({Status}) on {Method} {Path}",
                status, httpContext.Request.Method, httpContext.Request.Path);

        httpContext.Response.StatusCode = status;

        return await problemDetailsService.TryWriteAsync(new ProblemDetailsContext
        {
            HttpContext = httpContext,
            Exception = exception,
            ProblemDetails =
            {
                Status = status,
                Title = title,
                Type = $"https://httpstatuses.io/{status}",
                Instance = httpContext.Request.Path,
            },
        });
    }
}
```

- [ ] **Run** `dotnet build` and **expect PASS**.
- [ ] **Commit:**

```
feat(api): add global exception handler mapping to RFC 9457

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P3-4: Register validation + ProblemDetails + exception handler in Program.cs

- [ ] **Write a failing test** at `backend/tests/Tmap.Api.Tests/TasksTests.cs` (initial stub — proves the infra is wired before any Tasks endpoint exists). The test asserts that an authenticated GET to a not-yet-mapped tasks route returns 404 *with a ProblemDetails content type* (confirming ProblemDetails is on the pipeline). Full file:

```csharp
using System.Net;
using FluentAssertions;
using Xunit;

namespace Tmap.Api.Tests;

[Collection("db")]
public sealed class TasksTests(PostgresFixture fixture) : IntegrationTestBase(fixture)
{
    [Fact]
    public async Task Unmapped_route_returns_problem_details_content_type()
    {
        var auth = await RegisterAsync();
        var resp = await auth.Client.GetAsync("/api/v1/tasks/does-not-exist-route/extra");

        resp.StatusCode.Should().Be(HttpStatusCode.NotFound);
        resp.Content.Headers.ContentType?.MediaType.Should().Be("application/problem+json");
    }
}
```

- [ ] **Run** `dotnet test --filter "FullyQualifiedName~TasksTests.Unmapped_route_returns_problem_details_content_type"` and **expect FAIL** — likely `404` with empty body / `text/plain` (no ProblemDetails registered) or a compile error if `IntegrationTestBase` isn't yet referencing a wired `Program`.
- [ ] **Modify** `backend/src/Tmap.Api/Program.cs` to register the infra. Add these registrations in the service-configuration section (do not remove P0–P2 registrations; insert alongside them):

```csharp
using FluentValidation;
using Tmap.Api.Common.Errors;

// --- P3: validation + error infrastructure ---
builder.Services.AddValidatorsFromAssemblyContaining<Program>(includeInternalTypes: true);
builder.Services.AddTmapProblemDetails();
builder.Services.AddExceptionHandler<GlobalExceptionHandler>();
```

And in the middleware section, **before** authentication/authorization middleware:

```csharp
app.UseExceptionHandler();
app.UseStatusCodePages(); // ensures 404/405 etc. emit ProblemDetails bodies
```

> Note: `AddValidatorsFromAssemblyContaining<Program>` discovers `TasksValidator`/`SubtasksValidator` (and every future slice's validator) automatically — no per-validator registration needed.

- [ ] **Run** the same filter again and **expect PASS**.
- [ ] **Commit:**

```
feat(api): wire FluentValidation, ProblemDetails, and exception handler

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P3-5: Task & Subtask DTO records

- [ ] **Write the file** `backend/src/Tmap.Api/Features/Subtasks/SubtaskDtos.cs` first (TaskResponse embeds `SubtaskResponse`):

```csharp
namespace Tmap.Api.Features.Subtasks;

public sealed record CreateSubtaskRequest(string Title);

public sealed record UpdateSubtaskRequest(string? Title, bool? Completed, string? Rank);

public sealed record SubtaskResponse(
    Guid Id,
    Guid TaskId,
    string Title,
    bool Completed,
    int SortOrder,
    DateTimeOffset CreatedAt,
    DateTimeOffset UpdatedAt);
```

- [ ] **Write the file** `backend/src/Tmap.Api/Features/Tasks/TaskDtos.cs`. Requests carry **no `UserId`** (ownership is server-stamped per the contract). `CreateTaskRequest` lets the client supply a client-generated UUIDv7 `Id` (sync-ready, optional) and a starting `Status`; `UpdateTaskRequest` is fully nullable for partial PATCH semantics:

```csharp
using Tmap.Api.Features.Subtasks;
using Tmap.Api.Infrastructure.Entities;

namespace Tmap.Api.Features.Tasks;

public sealed record CreateTaskRequest(
    Guid? Id,
    string Title,
    string? Notes,
    Guid? ProjectId,
    List<string>? Labels,
    string? Source,
    TaskStatus? Status,
    DateOnly? PlannedDate,
    DateTimeOffset? ScheduledStart,
    DateTimeOffset? ScheduledEnd,
    int? DurationMinutes,
    int? Priority,
    int? ReminderMinutes,
    string? Rank,
    DateOnly? DueDate);

public sealed record UpdateTaskRequest(
    string? Title,
    string? Notes,
    Guid? ProjectId,
    List<string>? Labels,
    string? Source,
    TaskStatus? Status,
    DateOnly? PlannedDate,
    DateTimeOffset? ScheduledStart,
    DateTimeOffset? ScheduledEnd,
    int? DurationMinutes,
    int? ActualTimeMinutes,
    int? Priority,
    int? ReminderMinutes,
    string? Rank,
    DateOnly? DueDate,
    DateTimeOffset? CompletedAt);

public sealed record ReorderItem(Guid Id, string Rank);

public sealed record TaskResponse(
    Guid Id,
    string Title,
    string Notes,
    Guid? ProjectId,
    List<string> Labels,
    string Source,
    TaskStatus Status,
    DateOnly? PlannedDate,
    DateTimeOffset? ScheduledStart,
    DateTimeOffset? ScheduledEnd,
    int DurationMinutes,
    int ActualTimeMinutes,
    int? Priority,
    int? ReminderMinutes,
    string Rank,
    DateOnly? DueDate,
    Guid? RecurrenceRuleId,
    bool IsRecurrenceTemplate,
    bool RecurrenceDetached,
    DateOnly? RecurrenceOriginalDate,
    DateTimeOffset? CompletedAt,
    DateTimeOffset CreatedAt,
    DateTimeOffset UpdatedAt,
    long ChangeSeq,
    IReadOnlyList<SubtaskResponse> Subtasks);
```

- [ ] **Run** `dotnet build` and **expect PASS**.
- [ ] **Commit:**

```
feat(api): add Task and Subtask request/response DTOs

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P3-6: TasksValidator + SubtasksValidator

- [ ] **Write a failing test** — append to `backend/tests/Tmap.Api.Tests/TasksTests.cs` a test that POSTing an empty-title task returns **400 ValidationProblemDetails** keyed on `Title`. (The endpoint doesn't exist yet, so this fails at the route/404 stage first — that is the expected initial failure.)

```csharp
    [Fact]
    public async Task Create_with_empty_title_returns_validation_problem_400()
    {
        var auth = await RegisterAsync();
        var resp = await auth.Client.PostAsJsonAsync("/api/v1/tasks", new { title = "" });

        resp.StatusCode.Should().Be(HttpStatusCode.BadRequest);
        resp.Content.Headers.ContentType?.MediaType.Should().Be("application/problem+json");

        var problem = await resp.Content.ReadFromJsonAsync<ValidationProblemDetails>();
        problem.Should().NotBeNull();
        problem!.Errors.Should().ContainKey("Title");
    }
```

Add the required usings at the top of the test file if not already present:

```csharp
using System.Net.Http.Json;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
```

- [ ] **Run** `dotnet test --filter "FullyQualifiedName~TasksTests.Create_with_empty_title_returns_validation_problem_400"` and **expect FAIL** (404 Not Found — no `/api/v1/tasks` POST mapped yet).
- [ ] **Write the file** `backend/src/Tmap.Api/Features/Tasks/TasksValidator.cs`:

```csharp
using FluentValidation;

namespace Tmap.Api.Features.Tasks;

public sealed class CreateTaskValidator : AbstractValidator<CreateTaskRequest>
{
    public CreateTaskValidator()
    {
        RuleFor(x => x.Title).NotEmpty().MaximumLength(500);
        RuleFor(x => x.Notes).MaximumLength(20_000).When(x => x.Notes is not null);
        RuleFor(x => x.DurationMinutes).GreaterThanOrEqualTo(0).When(x => x.DurationMinutes.HasValue);
        RuleFor(x => x.Priority).InclusiveBetween(1, 4).When(x => x.Priority.HasValue);
        RuleFor(x => x.ReminderMinutes).GreaterThanOrEqualTo(0).When(x => x.ReminderMinutes.HasValue);
        RuleFor(x => x.ScheduledEnd)
            .GreaterThanOrEqualTo(x => x.ScheduledStart!.Value)
            .When(x => x.ScheduledStart.HasValue && x.ScheduledEnd.HasValue)
            .WithMessage("ScheduledEnd must be on or after ScheduledStart.");
    }
}

public sealed class UpdateTaskValidator : AbstractValidator<UpdateTaskRequest>
{
    public UpdateTaskValidator()
    {
        RuleFor(x => x.Title).NotEmpty().MaximumLength(500).When(x => x.Title is not null);
        RuleFor(x => x.Notes).MaximumLength(20_000).When(x => x.Notes is not null);
        RuleFor(x => x.DurationMinutes).GreaterThanOrEqualTo(0).When(x => x.DurationMinutes.HasValue);
        RuleFor(x => x.ActualTimeMinutes).GreaterThanOrEqualTo(0).When(x => x.ActualTimeMinutes.HasValue);
        RuleFor(x => x.Priority).InclusiveBetween(1, 4).When(x => x.Priority.HasValue);
        RuleFor(x => x.ReminderMinutes).GreaterThanOrEqualTo(0).When(x => x.ReminderMinutes.HasValue);
        RuleFor(x => x.ScheduledEnd)
            .GreaterThanOrEqualTo(x => x.ScheduledStart!.Value)
            .When(x => x.ScheduledStart.HasValue && x.ScheduledEnd.HasValue)
            .WithMessage("ScheduledEnd must be on or after ScheduledStart.");
    }
}

public sealed class ReorderTasksValidator : AbstractValidator<IReadOnlyList<ReorderItem>>
{
    public ReorderTasksValidator()
    {
        RuleFor(x => x).NotEmpty().WithMessage("Reorder payload must contain at least one item.");
        RuleForEach(x => x).ChildRules(item =>
        {
            item.RuleFor(i => i.Id).NotEmpty();
            item.RuleFor(i => i.Rank).NotEmpty().MaximumLength(255);
        });
    }
}
```

- [ ] **Write the file** `backend/src/Tmap.Api/Features/Subtasks/SubtasksValidator.cs`:

```csharp
using FluentValidation;

namespace Tmap.Api.Features.Subtasks;

public sealed class CreateSubtaskValidator : AbstractValidator<CreateSubtaskRequest>
{
    public CreateSubtaskValidator()
    {
        RuleFor(x => x.Title).NotEmpty().MaximumLength(500);
    }
}

public sealed class UpdateSubtaskValidator : AbstractValidator<UpdateSubtaskRequest>
{
    public UpdateSubtaskValidator()
    {
        RuleFor(x => x.Title).NotEmpty().MaximumLength(500).When(x => x.Title is not null);
        RuleFor(x => x.Rank).NotEmpty().MaximumLength(255).When(x => x.Rank is not null);
    }
}
```

> The validators compile now but the test still fails on routing until P3-7 maps the endpoint. That is intentional: P3-7's "expect PASS" step is where this validation test goes green.

- [ ] **Run** `dotnet build` and **expect PASS** (validators compile). The endpoint test from this task remains RED until P3-7.
- [ ] **Commit:**

```
test(api): add empty-title validation test; add Tasks/Subtasks validators

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P3-7: TasksEndpoints — POST /tasks (create returns 201, scoped to user)

- [ ] **Write a failing test** — append to `TasksTests.cs`. Asserts POST returns **201**, a `Location` header, an echoed `TaskResponse`, and that the row is persisted in the DB **owned by the calling user** (asserted via `NewElevatedDbContext()`):

```csharp
    [Fact]
    public async Task Create_returns_201_and_persists_row_scoped_to_user()
    {
        var auth = await RegisterAsync();

        var resp = await auth.Client.PostAsJsonAsync("/api/v1/tasks", new
        {
            title = "Write the plan",
            notes = "first draft",
            status = "Inbox",
        });

        resp.StatusCode.Should().Be(HttpStatusCode.Created);
        resp.Headers.Location.Should().NotBeNull();

        var body = await resp.Content.ReadFromJsonAsync<TaskResponse>();
        body.Should().NotBeNull();
        body!.Title.Should().Be("Write the plan");
        body.Subtasks.Should().BeEmpty();
        body.ChangeSeq.Should().BeGreaterThan(0);
        body.Rank.Should().NotBeNullOrEmpty();

        await using var db = NewElevatedDbContext();
        var row = await db.Tasks.SingleAsync(t => t.Id == body.Id);
        row.UserId.Should().Be(auth.UserId);
        row.Title.Should().Be("Write the plan");
        row.DeletedAt.Should().BeNull();
    }
```

Add usings if missing: `using Tmap.Api.Features.Tasks;`, `using Microsoft.EntityFrameworkCore;`.

- [ ] **Run** `dotnet test --filter "FullyQualifiedName~TasksTests.Create_returns_201_and_persists_row_scoped_to_user"` and **expect FAIL** (404 — no POST mapped).
- [ ] **Create the file** `backend/src/Tmap.Api/Features/Tasks/TasksEndpoints.cs` with the route group, a shared `ToResponse` mapper, an inline midpoint/append rank helper, and the **POST** handler. (Other endpoints are added in later tasks — write only what's below now, plus the `MapTasks` shell that later tasks extend.)

```csharp
using Microsoft.AspNetCore.Http.HttpResults;
using Microsoft.EntityFrameworkCore;
using Tmap.Api.Common;
using Tmap.Api.Common.Validation;
using Tmap.Api.Features.Subtasks;
using Tmap.Api.Infrastructure;
using Tmap.Api.Infrastructure.Entities;

namespace Tmap.Api.Features.Tasks;

public static class TasksEndpoints
{
    public static RouteGroupBuilder MapTasks(this IEndpointRouteBuilder app)
    {
        var group = app.MapGroup("/api/v1/tasks").RequireAuthorization();

        group.MapPost("/", CreateAsync)
            .AddEndpointFilter<ValidationFilter<CreateTaskRequest>>();

        return group;
    }

    internal static async Task<Results<Created<TaskResponse>, ValidationProblem>> CreateAsync(
        CreateTaskRequest req,
        AppDbContext db,
        ICurrentUser user,
        CancellationToken ct)
    {
        var entity = new TaskItem
        {
            Id = req.Id is { } id && id != Guid.Empty ? id : Guid.CreateVersion7(),
            // UserId is stamped by the write-time ownership interceptor (P1); set here too for clarity.
            UserId = user.Id,
            Title = req.Title,
            Notes = req.Notes ?? string.Empty,
            ProjectId = req.ProjectId,
            Labels = req.Labels ?? [],
            Source = req.Source ?? string.Empty,
            Status = req.Status ?? TaskStatus.Inbox,
            PlannedDate = req.PlannedDate,
            ScheduledStart = req.ScheduledStart,
            ScheduledEnd = req.ScheduledEnd,
            DurationMinutes = req.DurationMinutes ?? 0,
            ActualTimeMinutes = 0,
            Priority = req.Priority,
            ReminderMinutes = req.ReminderMinutes,
            Rank = !string.IsNullOrEmpty(req.Rank)
                ? req.Rank
                : await NextRankAsync(db, user.Id, ct),
            DueDate = req.DueDate,
            IsRecurrenceTemplate = false,
            RecurrenceDetached = false,
        };

        db.Tasks.Add(entity);
        await db.SaveChangesAsync(ct);

        var response = ToResponse(entity, []);
        return TypedResults.Created($"/api/v1/tasks/{entity.Id}", response);
    }

    // --- helpers (local to the slice; not a shared symbol) ---

    /// <summary>Append a new rank after the user's current max task rank (simple end-append scheme).</summary>
    private static async Task<string> NextRankAsync(AppDbContext db, Guid userId, CancellationToken ct)
    {
        var maxRank = await db.Tasks
            .Where(t => t.UserId == userId)
            .OrderByDescending(t => t.Rank)
            .Select(t => t.Rank)
            .FirstOrDefaultAsync(ct);
        return RankAfter(maxRank);
    }

    /// <summary>Midpoint-style lexicographic rank generator: produces a key sorting after <paramref name="prev"/>.</summary>
    private static string RankAfter(string? prev)
    {
        if (string.IsNullOrEmpty(prev)) return "n"; // mid of 'a'..'z'
        var last = prev[^1];
        if (last < 'z') return prev[..^1] + (char)(last + 1);
        return prev + "n";
    }

    internal static TaskResponse ToResponse(TaskItem t, IReadOnlyList<SubtaskResponse> subtasks) => new(
        t.Id,
        t.Title,
        t.Notes,
        t.ProjectId,
        t.Labels,
        t.Source,
        t.Status,
        t.PlannedDate,
        t.ScheduledStart,
        t.ScheduledEnd,
        t.DurationMinutes,
        t.ActualTimeMinutes,
        t.Priority,
        t.ReminderMinutes,
        t.Rank,
        t.DueDate,
        t.RecurrenceRuleId,
        t.IsRecurrenceTemplate,
        t.RecurrenceDetached,
        t.RecurrenceOriginalDate,
        t.CompletedAt,
        t.CreatedAt,
        t.UpdatedAt,
        t.ChangeSeq,
        subtasks);

    internal static SubtaskResponse ToSubtaskResponse(Subtask s) => new(
        s.Id, s.TaskId, s.Title, s.Completed, s.SortOrder, s.CreatedAt, s.UpdatedAt);
}
```

- [ ] **Modify** `backend/src/Tmap.Api/Program.cs` — register the group in the endpoint-mapping section:

```csharp
using Tmap.Api.Features.Tasks;
// ...
app.MapTasks();
```

- [ ] **Run** `dotnet test --filter "FullyQualifiedName~TasksTests.Create_returns_201_and_persists_row_scoped_to_user"` and **expect PASS**.
- [ ] **Run** `dotnet test --filter "FullyQualifiedName~TasksTests.Create_with_empty_title_returns_validation_problem_400"` and **expect PASS** (validation filter now active on the mapped POST).
- [ ] **Commit:**

```
feat(api): add POST /api/v1/tasks (create, 201, server-stamped ownership)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P3-8: GET /tasks — getAll (no filter returns the user's live tasks)

- [ ] **Write a failing test** — append to `TasksTests.cs`. Creates two tasks, then `GET /api/v1/tasks` returns exactly those two:

```csharp
    [Fact]
    public async Task GetAll_returns_only_callers_live_tasks()
    {
        var auth = await RegisterAsync();
        await auth.Client.PostAsJsonAsync("/api/v1/tasks", new { title = "A", status = "Inbox" });
        await auth.Client.PostAsJsonAsync("/api/v1/tasks", new { title = "B", status = "Backlog" });

        var resp = await auth.Client.GetAsync("/api/v1/tasks");
        resp.StatusCode.Should().Be(HttpStatusCode.OK);

        var tasks = await resp.Content.ReadFromJsonAsync<List<TaskResponse>>();
        tasks.Should().NotBeNull();
        tasks!.Select(t => t.Title).Should().BeEquivalentTo(["A", "B"]);
    }
```

- [ ] **Run** `dotnet test --filter "FullyQualifiedName~TasksTests.GetAll_returns_only_callers_live_tasks"` and **expect FAIL** (404 — no GET mapped).
- [ ] **Modify** `TasksEndpoints.MapTasks` to map GET, and add the handler. The handler reads optional `status`, `date`, `q` and applies them as a filter chain (no params = getAll). Tenant + soft-delete filters apply automatically via the P1 named query filters. Subtasks are eager-loaded and embedded:

In `MapTasks`, add **after** the POST mapping:

```csharp
        group.MapGet("/", GetAsync);
```

Add the handler to the class:

```csharp
    internal static async Task<Ok<List<TaskResponse>>> GetAsync(
        AppDbContext db,
        ICurrentUser user,
        CancellationToken ct,
        string? status = null,
        DateOnly? date = null,
        string? q = null)
    {
        var query = db.Tasks.Include(t => t.Subtasks).AsQueryable();

        if (!string.IsNullOrWhiteSpace(status) && Enum.TryParse<TaskStatus>(status, ignoreCase: true, out var parsed))
            query = query.Where(t => t.Status == parsed);

        if (date is { } d)
            query = query.Where(t => t.PlannedDate == d);

        if (!string.IsNullOrWhiteSpace(q))
        {
            var pattern = $"%{q.Trim()}%";
            query = query.Where(t =>
                EF.Functions.ILike(t.Title, pattern) ||
                EF.Functions.ILike(t.Notes, pattern));
        }

        var rows = await query.OrderBy(t => t.Rank).ToListAsync(ct);

        var result = rows
            .Select(t => ToResponse(
                t,
                t.Subtasks
                    .Where(s => s.DeletedAt == null)
                    .OrderBy(s => s.SortOrder)
                    .Select(ToSubtaskResponse)
                    .ToList()))
            .ToList();

        return TypedResults.Ok(result);
    }
```

- [ ] **Run** the same filter again and **expect PASS**.
- [ ] **Commit:**

```
feat(api): add GET /api/v1/tasks (getAll)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P3-9: GET /tasks filters — status, date, q combinations

- [ ] **Write a failing test** — append to `TasksTests.cs`. Exercises each filter and a combination:

```csharp
    [Fact]
    public async Task GetAll_filters_by_status_date_and_query()
    {
        var auth = await RegisterAsync();
        await auth.Client.PostAsJsonAsync("/api/v1/tasks",
            new { title = "Inbox alpha", status = "Inbox" });
        await auth.Client.PostAsJsonAsync("/api/v1/tasks",
            new { title = "Planned beta", status = "Planned", plannedDate = "2026-06-02" });
        await auth.Client.PostAsJsonAsync("/api/v1/tasks",
            new { title = "Planned gamma", status = "Planned", plannedDate = "2026-06-03" });

        // status filter
        var byStatus = await (await auth.Client.GetAsync("/api/v1/tasks?status=Planned"))
            .Content.ReadFromJsonAsync<List<TaskResponse>>();
        byStatus!.Select(t => t.Title).Should().BeEquivalentTo(["Planned beta", "Planned gamma"]);

        // date filter
        var byDate = await (await auth.Client.GetAsync("/api/v1/tasks?date=2026-06-02"))
            .Content.ReadFromJsonAsync<List<TaskResponse>>();
        byDate!.Select(t => t.Title).Should().BeEquivalentTo(["Planned beta"]);

        // q filter (case-insensitive)
        var byQuery = await (await auth.Client.GetAsync("/api/v1/tasks?q=alpha"))
            .Content.ReadFromJsonAsync<List<TaskResponse>>();
        byQuery!.Select(t => t.Title).Should().BeEquivalentTo(["Inbox alpha"]);

        // combined status + date
        var combined = await (await auth.Client.GetAsync("/api/v1/tasks?status=Planned&date=2026-06-03"))
            .Content.ReadFromJsonAsync<List<TaskResponse>>();
        combined!.Select(t => t.Title).Should().BeEquivalentTo(["Planned gamma"]);
    }
```

- [ ] **Run** `dotnet test --filter "FullyQualifiedName~TasksTests.GetAll_filters_by_status_date_and_query"` and **expect PASS** (the GET handler from P3-8 already implements all filters). This task is a verification-only test against the existing implementation — if it FAILS, fix the filter chain in `GetAsync` until green; do **not** weaken the test.
- [ ] **Commit:**

```
test(api): cover GET /tasks status/date/q filter combinations

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P3-10: PATCH /tasks/{id} — partial update

- [ ] **Write a failing test** — append to `TasksTests.cs`. Creates a task, PATCHes title + status, asserts 200 + updated row + `UpdatedAt`/`ChangeSeq` advanced:

```csharp
    [Fact]
    public async Task Patch_updates_only_supplied_fields_and_advances_change_seq()
    {
        var auth = await RegisterAsync();
        var created = await (await auth.Client.PostAsJsonAsync("/api/v1/tasks",
            new { title = "Original", notes = "keep me", status = "Inbox" }))
            .Content.ReadFromJsonAsync<TaskResponse>();

        var resp = await auth.Client.PatchAsJsonAsync($"/api/v1/tasks/{created!.Id}",
            new { title = "Renamed", status = "Done" });

        resp.StatusCode.Should().Be(HttpStatusCode.OK);
        var updated = await resp.Content.ReadFromJsonAsync<TaskResponse>();
        updated!.Title.Should().Be("Renamed");
        updated.Status.Should().Be(TaskStatus.Done);
        updated.Notes.Should().Be("keep me");          // untouched field preserved
        updated.ChangeSeq.Should().BeGreaterThan(created.ChangeSeq);
    }
```

Add `using System.Net.Http.Json;` already present; ensure `PatchAsJsonAsync` extension is available (it is, via `System.Net.Http.Json`).

- [ ] **Run** `dotnet test --filter "FullyQualifiedName~TasksTests.Patch_updates_only_supplied_fields_and_advances_change_seq"` and **expect FAIL** (404 — no PATCH mapped).
- [ ] **Modify** `MapTasks` to add the route + filter:

```csharp
        group.MapPatch("/{id:guid}", UpdateAsync)
            .AddEndpointFilter<ValidationFilter<UpdateTaskRequest>>();
```

Add the handler. It loads the task **scoped by the query filters** (so another user's task is invisible → `NotFound`), applies non-null fields, and saves. The DB trigger advances `updated_at`/`change_seq`:

```csharp
    internal static async Task<Results<Ok<TaskResponse>, NotFound, ValidationProblem>> UpdateAsync(
        Guid id,
        UpdateTaskRequest req,
        AppDbContext db,
        ICurrentUser user,
        CancellationToken ct)
    {
        var task = await db.Tasks
            .Include(t => t.Subtasks)
            .FirstOrDefaultAsync(t => t.Id == id, ct);
        if (task is null) return TypedResults.NotFound();

        if (req.Title is not null) task.Title = req.Title;
        if (req.Notes is not null) task.Notes = req.Notes;
        if (req.ProjectId is not null) task.ProjectId = req.ProjectId;
        if (req.Labels is not null) task.Labels = req.Labels;
        if (req.Source is not null) task.Source = req.Source;
        if (req.Status is { } s) task.Status = s;
        if (req.PlannedDate is not null) task.PlannedDate = req.PlannedDate;
        if (req.ScheduledStart is not null) task.ScheduledStart = req.ScheduledStart;
        if (req.ScheduledEnd is not null) task.ScheduledEnd = req.ScheduledEnd;
        if (req.DurationMinutes is { } dm) task.DurationMinutes = dm;
        if (req.ActualTimeMinutes is { } am) task.ActualTimeMinutes = am;
        if (req.Priority is not null) task.Priority = req.Priority;
        if (req.ReminderMinutes is not null) task.ReminderMinutes = req.ReminderMinutes;
        if (!string.IsNullOrEmpty(req.Rank)) task.Rank = req.Rank;
        if (req.DueDate is not null) task.DueDate = req.DueDate;
        if (req.CompletedAt is not null) task.CompletedAt = req.CompletedAt;

        await db.SaveChangesAsync(ct);

        var subtasks = task.Subtasks
            .Where(st => st.DeletedAt == null)
            .OrderBy(st => st.SortOrder)
            .Select(ToSubtaskResponse)
            .ToList();
        return TypedResults.Ok(ToResponse(task, subtasks));
    }
```

- [ ] **Run** the same filter again and **expect PASS**.
- [ ] **Commit:**

```
feat(api): add PATCH /api/v1/tasks/{id} (partial update)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P3-11: DELETE /tasks/{id} — soft delete + cascade-tombstone subtasks

- [ ] **Write a failing test** — append to `TasksTests.cs`. Creates a task with one subtask, DELETEs the task, asserts 204, the task disappears from reads, **and** the subtask is tombstoned (`DeletedAt` set) — verified via elevated DbContext that ignores the SoftDelete filter:

```csharp
    [Fact]
    public async Task Delete_soft_deletes_task_and_tombstones_subtasks()
    {
        var auth = await RegisterAsync();
        var task = await (await auth.Client.PostAsJsonAsync("/api/v1/tasks",
            new { title = "Parent", status = "Inbox" }))
            .Content.ReadFromJsonAsync<TaskResponse>();
        await auth.Client.PostAsJsonAsync($"/api/v1/tasks/{task!.Id}/subtasks",
            new { title = "child" });

        var del = await auth.Client.DeleteAsync($"/api/v1/tasks/{task.Id}");
        del.StatusCode.Should().Be(HttpStatusCode.NoContent);

        // gone from the normal read path
        var afterGet = await auth.Client.GetAsync($"/api/v1/tasks");
        var live = await afterGet.Content.ReadFromJsonAsync<List<TaskResponse>>();
        live!.Should().NotContain(t => t.Id == task.Id);

        // tombstones exist in the DB (SoftDelete filter ignored)
        await using var db = NewElevatedDbContext();
        var taskRow = await db.Tasks.IgnoreQueryFilters(["SoftDelete"])
            .SingleAsync(t => t.Id == task.Id);
        taskRow.DeletedAt.Should().NotBeNull();

        var childRows = await db.Subtasks.IgnoreQueryFilters(["SoftDelete"])
            .Where(s => s.TaskId == task.Id).ToListAsync();
        childRows.Should().NotBeEmpty();
        childRows.Should().OnlyContain(s => s.DeletedAt != null);
    }
```

- [ ] **Run** `dotnet test --filter "FullyQualifiedName~TasksTests.Delete_soft_deletes_task_and_tombstones_subtasks"` and **expect FAIL** (404 on DELETE — not mapped; also depends on the subtasks POST from P3-14, so this test stays RED until both this DELETE and the subtask-create endpoint exist — implement DELETE now; if the subtask POST isn't yet present, temporarily add the subtask directly via `NewElevatedDbContext()` arrange, OR sequence P3-14 before re-running. Recommended: implement DELETE here, then this test goes green after P3-14. To keep the cycle honest, run it after P3-14 too.)

> Sequencing note: the cascade assertion needs the subtask-create endpoint (P3-14). Implement the DELETE handler here and verify the **task** tombstone with an arrange-only subtask inserted via `NewElevatedDbContext()` if you want this task green in isolation; the full HTTP-created-subtask version re-runs green after P3-14. Both forms assert the same cascade.

- [ ] **Modify** `MapTasks` to add the DELETE route:

```csharp
        group.MapDelete("/{id:guid}", DeleteAsync);
```

Add the handler. **Soft delete only** — set `DeletedAt` on the task and every live subtask; never `db.Remove`. The trigger bumps `change_seq`/`updated_at`:

```csharp
    internal static async Task<Results<NoContent, NotFound>> DeleteAsync(
        Guid id,
        AppDbContext db,
        ICurrentUser user,
        CancellationToken ct)
    {
        var task = await db.Tasks
            .Include(t => t.Subtasks)
            .FirstOrDefaultAsync(t => t.Id == id, ct);
        if (task is null) return TypedResults.NotFound();

        var now = DateTimeOffset.UtcNow;
        task.DeletedAt = now;
        foreach (var st in task.Subtasks.Where(s => s.DeletedAt == null))
            st.DeletedAt = now;

        await db.SaveChangesAsync(ct);
        return TypedResults.NoContent();
    }
```

- [ ] **Run** the same filter again and **expect PASS** (re-run after P3-14 if the subtask POST wasn't available at first run).
- [ ] **Commit:**

```
feat(api): add DELETE /api/v1/tasks/{id} (soft delete + cascade tombstone)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P3-12: PATCH /tasks/reorder — updates rank on targeted rows only

- [ ] **Write a failing test** — append to `TasksTests.cs`. Creates two tasks, reorders **one** of them, asserts the targeted row's rank changed and the other row's rank is untouched:

```csharp
    [Fact]
    public async Task Reorder_updates_rank_on_targeted_rows_only()
    {
        var auth = await RegisterAsync();
        var a = await (await auth.Client.PostAsJsonAsync("/api/v1/tasks",
            new { title = "A", status = "Inbox" })).Content.ReadFromJsonAsync<TaskResponse>();
        var b = await (await auth.Client.PostAsJsonAsync("/api/v1/tasks",
            new { title = "B", status = "Inbox" })).Content.ReadFromJsonAsync<TaskResponse>();

        var resp = await auth.Client.PatchAsJsonAsync("/api/v1/tasks/reorder",
            new[] { new { id = a!.Id, rank = "zzz" } });
        resp.StatusCode.Should().Be(HttpStatusCode.NoContent);

        await using var db = NewElevatedDbContext();
        var aRow = await db.Tasks.SingleAsync(t => t.Id == a.Id);
        var bRow = await db.Tasks.SingleAsync(t => t.Id == b!.Id);
        aRow.Rank.Should().Be("zzz");
        bRow.Rank.Should().Be(b!.Rank);   // unchanged
    }
```

- [ ] **Run** `dotnet test --filter "FullyQualifiedName~TasksTests.Reorder_updates_rank_on_targeted_rows_only"` and **expect FAIL** (404 — reorder not mapped). 

> Route-ordering caution: map `/reorder` as a literal segment; it must not collide with `/{id:guid}` (a `Guid` route constraint already excludes the literal "reorder", so order is safe, but keep the literal mapping explicit).

- [ ] **Modify** `MapTasks` to add the reorder route + validator filter:

```csharp
        group.MapPatch("/reorder", ReorderAsync)
            .AddEndpointFilter<ValidationFilter<IReadOnlyList<ReorderItem>>>();
```

Add the handler. It loads only the targeted tasks (scoped by tenant/soft-delete filters) and sets each rank; rows not in the payload are never touched. IDs that don't belong to the user simply aren't found (no leak, no error):

```csharp
    internal static async Task<Results<NoContent, ValidationProblem>> ReorderAsync(
        IReadOnlyList<ReorderItem> items,
        AppDbContext db,
        ICurrentUser user,
        CancellationToken ct)
    {
        var ids = items.Select(i => i.Id).ToList();
        var tasks = await db.Tasks.Where(t => ids.Contains(t.Id)).ToListAsync(ct);

        var byId = tasks.ToDictionary(t => t.Id);
        foreach (var item in items)
            if (byId.TryGetValue(item.Id, out var task))
                task.Rank = item.Rank;

        await db.SaveChangesAsync(ct);
        return TypedResults.NoContent();
    }
```

- [ ] **Run** the same filter again and **expect PASS**.
- [ ] **Commit:**

```
feat(api): add PATCH /api/v1/tasks/reorder (rank, targeted rows only)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P3-13: Cross-user isolation — read/update/delete another user's task return 404 (not 403)

- [ ] **Write a failing test** — append to `TasksTests.cs`. User1 creates a task; User2 attempts GET-by-implied-read, PATCH, DELETE → all **404** (existence is not leaked). Also confirms User2's `GET /tasks` does not include User1's task:

```csharp
    [Fact]
    public async Task Cross_user_cannot_read_update_or_delete_anothers_task_returns_404()
    {
        var owner = await RegisterAsync("owner@example.com");
        var intruder = await RegisterAsync("intruder@example.com");

        var task = await (await owner.Client.PostAsJsonAsync("/api/v1/tasks",
            new { title = "secret", status = "Inbox" }))
            .Content.ReadFromJsonAsync<TaskResponse>();

        // intruder's list must not contain it
        var intruderList = await (await intruder.Client.GetAsync("/api/v1/tasks"))
            .Content.ReadFromJsonAsync<List<TaskResponse>>();
        intruderList!.Should().NotContain(t => t.Id == task!.Id);

        // update → 404
        var patch = await intruder.Client.PatchAsJsonAsync($"/api/v1/tasks/{task!.Id}",
            new { title = "hijack" });
        patch.StatusCode.Should().Be(HttpStatusCode.NotFound);

        // delete → 404
        var del = await intruder.Client.DeleteAsync($"/api/v1/tasks/{task.Id}");
        del.StatusCode.Should().Be(HttpStatusCode.NotFound);

        // owner's row is intact & not tombstoned
        await using var db = NewElevatedDbContext();
        var row = await db.Tasks.IgnoreQueryFilters(["SoftDelete", "Tenant"])
            .SingleAsync(t => t.Id == task.Id);
        row.DeletedAt.Should().BeNull();
        row.Title.Should().Be("secret");
    }
```

- [ ] **Run** `dotnet test --filter "FullyQualifiedName~TasksTests.Cross_user_cannot_read_update_or_delete_anothers_task_returns_404"` and **expect PASS** — the PATCH/DELETE handlers already `FirstOrDefaultAsync` through the **Tenant** named filter (P1), so the intruder never sees the row and gets `NotFound`. RLS (P1) is the backstop. If this FAILS (e.g. returns 403 or 200), the bug is a missing/incorrect tenant filter — fix in P1's filter wiring, **not** by adding explicit `UserId ==` checks in the handler (the contract requires reliance on RLS + named filters).
- [ ] **Commit:**

```
test(api): prove cross-user task isolation returns 404 (no existence leak)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P3-14: SubtasksEndpoints — POST /tasks/{taskId}/subtasks (nested create)

- [ ] **Create the test file** `backend/tests/Tmap.Api.Tests/SubtasksTests.cs` with a failing nested-create test: POST a subtask under an owned task → 201, persisted, scoped, and **embedded in the parent's TaskResponse on read**:

```csharp
using System.Net;
using System.Net.Http.Json;
using FluentAssertions;
using Microsoft.EntityFrameworkCore;
using Tmap.Api.Features.Subtasks;
using Tmap.Api.Features.Tasks;
using Xunit;

namespace Tmap.Api.Tests;

[Collection("db")]
public sealed class SubtasksTests(PostgresFixture fixture) : IntegrationTestBase(fixture)
{
    private async Task<TaskResponse> CreateTaskAsync(AuthedClient auth, string title = "Parent") =>
        (await (await auth.Client.PostAsJsonAsync("/api/v1/tasks", new { title, status = "Inbox" }))
            .Content.ReadFromJsonAsync<TaskResponse>())!;

    [Fact]
    public async Task Create_subtask_returns_201_and_embeds_in_parent_task_on_read()
    {
        var auth = await RegisterAsync();
        var task = await CreateTaskAsync(auth);

        var resp = await auth.Client.PostAsJsonAsync(
            $"/api/v1/tasks/{task.Id}/subtasks", new { title = "step one" });

        resp.StatusCode.Should().Be(HttpStatusCode.Created);
        var sub = await resp.Content.ReadFromJsonAsync<SubtaskResponse>();
        sub!.Title.Should().Be("step one");
        sub.TaskId.Should().Be(task.Id);

        // embedded on read
        var reread = await (await auth.Client.GetAsync("/api/v1/tasks"))
            .Content.ReadFromJsonAsync<List<TaskResponse>>();
        var parent = reread!.Single(t => t.Id == task.Id);
        parent.Subtasks.Should().ContainSingle(s => s.Id == sub.Id && s.Title == "step one");

        await using var db = NewElevatedDbContext();
        var row = await db.Subtasks.SingleAsync(s => s.Id == sub.Id);
        row.UserId.Should().Be(auth.UserId);
        row.TaskId.Should().Be(task.Id);
    }
}
```

- [ ] **Run** `dotnet test --filter "FullyQualifiedName~SubtasksTests.Create_subtask_returns_201_and_embeds_in_parent_task_on_read"` and **expect FAIL** (404 — subtask route not mapped).
- [ ] **Create the file** `backend/src/Tmap.Api/Features/Subtasks/SubtasksEndpoints.cs`. The nested-create handler **verifies the parent task is visible to the caller** (tenant filter) → 404 otherwise (prevents attaching a subtask to another user's task and prevents existence leak):

```csharp
using Microsoft.AspNetCore.Http.HttpResults;
using Microsoft.EntityFrameworkCore;
using Tmap.Api.Common;
using Tmap.Api.Common.Validation;
using Tmap.Api.Features.Tasks;
using Tmap.Api.Infrastructure;
using Tmap.Api.Infrastructure.Entities;

namespace Tmap.Api.Features.Subtasks;

public static class SubtasksEndpoints
{
    public static IEndpointRouteBuilder MapSubtasks(this IEndpointRouteBuilder app)
    {
        var nested = app.MapGroup("/api/v1/tasks/{taskId:guid}/subtasks").RequireAuthorization();
        nested.MapPost("/", CreateAsync)
            .AddEndpointFilter<ValidationFilter<CreateSubtaskRequest>>();

        var flat = app.MapGroup("/api/v1/subtasks").RequireAuthorization();
        flat.MapPatch("/{id:guid}", UpdateAsync)
            .AddEndpointFilter<ValidationFilter<UpdateSubtaskRequest>>();
        flat.MapDelete("/{id:guid}", DeleteAsync);

        return app;
    }

    internal static async Task<Results<Created<SubtaskResponse>, NotFound, ValidationProblem>> CreateAsync(
        Guid taskId,
        CreateSubtaskRequest req,
        AppDbContext db,
        ICurrentUser user,
        CancellationToken ct)
    {
        // parent must be visible to the caller (tenant filter) else 404
        var parentExists = await db.Tasks.AnyAsync(t => t.Id == taskId, ct);
        if (!parentExists) return TypedResults.NotFound();

        var maxOrder = await db.Subtasks
            .Where(s => s.TaskId == taskId)
            .Select(s => (int?)s.SortOrder)
            .MaxAsync(ct) ?? -1;

        var entity = new Subtask
        {
            Id = Guid.CreateVersion7(),
            UserId = user.Id,          // also enforced by the write-time ownership interceptor (P1)
            TaskId = taskId,
            Title = req.Title,
            Completed = false,
            SortOrder = maxOrder + 1,
        };

        db.Subtasks.Add(entity);
        await db.SaveChangesAsync(ct);

        return TypedResults.Created(
            $"/api/v1/subtasks/{entity.Id}",
            TasksEndpoints.ToSubtaskResponse(entity));
    }

    internal static async Task<Results<Ok<SubtaskResponse>, NotFound, ValidationProblem>> UpdateAsync(
        Guid id,
        UpdateSubtaskRequest req,
        AppDbContext db,
        ICurrentUser user,
        CancellationToken ct)
    {
        var sub = await db.Subtasks.FirstOrDefaultAsync(s => s.Id == id, ct);
        if (sub is null) return TypedResults.NotFound();

        if (req.Title is not null) sub.Title = req.Title;
        if (req.Completed is { } c) sub.Completed = c;
        // Rank/SortOrder: the contract Subtask uses SortOrder(int); accept Rank as an opaque
        // ordering hint mapped to SortOrder when numeric, else ignored (subtasks keep int order).
        if (!string.IsNullOrEmpty(req.Rank) && int.TryParse(req.Rank, out var parsedOrder))
            sub.SortOrder = parsedOrder;

        await db.SaveChangesAsync(ct);
        return TypedResults.Ok(TasksEndpoints.ToSubtaskResponse(sub));
    }

    internal static async Task<Results<NoContent, NotFound>> DeleteAsync(
        Guid id,
        AppDbContext db,
        ICurrentUser user,
        CancellationToken ct)
    {
        var sub = await db.Subtasks.FirstOrDefaultAsync(s => s.Id == id, ct);
        if (sub is null) return TypedResults.NotFound();

        sub.DeletedAt = DateTimeOffset.UtcNow;   // soft delete only
        await db.SaveChangesAsync(ct);
        return TypedResults.NoContent();
    }
}
```

- [ ] **Modify** `backend/src/Tmap.Api/Program.cs` — map the subtasks group near `app.MapTasks()`:

```csharp
using Tmap.Api.Features.Subtasks;
// ...
app.MapSubtasks();
```

- [ ] **Run** the SubtasksTests create filter again and **expect PASS**.
- [ ] **Re-run** `dotnet test --filter "FullyQualifiedName~TasksTests.Delete_soft_deletes_task_and_tombstones_subtasks"` and **expect PASS** now that the subtask-create endpoint exists.
- [ ] **Commit:**

```
feat(api): add POST /api/v1/tasks/{taskId}/subtasks + subtask update/delete routes

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P3-15: PATCH /subtasks/{id} — update title/completed

- [ ] **Write a failing test** — append to `SubtasksTests.cs`:

```csharp
    [Fact]
    public async Task Patch_subtask_updates_completed_and_title()
    {
        var auth = await RegisterAsync();
        var task = await CreateTaskAsync(auth);
        var sub = await (await auth.Client.PostAsJsonAsync(
                $"/api/v1/tasks/{task.Id}/subtasks", new { title = "draft" }))
            .Content.ReadFromJsonAsync<SubtaskResponse>();

        var resp = await auth.Client.PatchAsJsonAsync(
            $"/api/v1/subtasks/{sub!.Id}", new { title = "final", completed = true });

        resp.StatusCode.Should().Be(HttpStatusCode.OK);
        var updated = await resp.Content.ReadFromJsonAsync<SubtaskResponse>();
        updated!.Title.Should().Be("final");
        updated.Completed.Should().BeTrue();
    }
```

- [ ] **Run** `dotnet test --filter "FullyQualifiedName~SubtasksTests.Patch_subtask_updates_completed_and_title"` and **expect PASS** (the PATCH handler was added in P3-14). Verification-only against existing code; if RED, fix `UpdateAsync` until green.
- [ ] **Commit:**

```
test(api): cover PATCH /api/v1/subtasks/{id}

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P3-16: DELETE /subtasks/{id} — soft delete; cross-user 404

- [ ] **Write a failing test** — append to `SubtasksTests.cs`. Covers soft delete (tombstone, removed from parent's embedded list) **and** cross-user 404:

```csharp
    [Fact]
    public async Task Delete_subtask_soft_deletes_and_removes_from_parent_read()
    {
        var auth = await RegisterAsync();
        var task = await CreateTaskAsync(auth);
        var sub = await (await auth.Client.PostAsJsonAsync(
                $"/api/v1/tasks/{task.Id}/subtasks", new { title = "gone" }))
            .Content.ReadFromJsonAsync<SubtaskResponse>();

        var del = await auth.Client.DeleteAsync($"/api/v1/subtasks/{sub!.Id}");
        del.StatusCode.Should().Be(HttpStatusCode.NoContent);

        var parent = (await (await auth.Client.GetAsync("/api/v1/tasks"))
            .Content.ReadFromJsonAsync<List<TaskResponse>>())!.Single(t => t.Id == task.Id);
        parent.Subtasks.Should().NotContain(s => s.Id == sub.Id);

        await using var db = NewElevatedDbContext();
        var row = await db.Subtasks.IgnoreQueryFilters(["SoftDelete"]).SingleAsync(s => s.Id == sub.Id);
        row.DeletedAt.Should().NotBeNull();
    }

    [Fact]
    public async Task Cross_user_cannot_delete_anothers_subtask_returns_404()
    {
        var owner = await RegisterAsync("sub-owner@example.com");
        var intruder = await RegisterAsync("sub-intruder@example.com");
        var task = await CreateTaskAsync(owner);
        var sub = await (await owner.Client.PostAsJsonAsync(
                $"/api/v1/tasks/{task.Id}/subtasks", new { title = "private" }))
            .Content.ReadFromJsonAsync<SubtaskResponse>();

        var del = await intruder.Client.DeleteAsync($"/api/v1/subtasks/{sub!.Id}");
        del.StatusCode.Should().Be(HttpStatusCode.NotFound);

        await using var db = NewElevatedDbContext();
        var row = await db.Subtasks.IgnoreQueryFilters(["SoftDelete", "Tenant"]).SingleAsync(s => s.Id == sub.Id);
        row.DeletedAt.Should().BeNull();   // intruder's call did nothing
    }
```

- [ ] **Run** `dotnet test --filter "FullyQualifiedName~SubtasksTests.Delete_subtask_soft_deletes_and_removes_from_parent_read"` and **expect PASS**.
- [ ] **Run** `dotnet test --filter "FullyQualifiedName~SubtasksTests.Cross_user_cannot_delete_anothers_subtask_returns_404"` and **expect PASS** (tenant filter makes the intruder's subtask invisible → 404).
- [ ] **Commit:**

```
test(api): cover subtask soft-delete and cross-user 404 isolation

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P3-17: Full-suite green gate for the phase

- [ ] **Run** the whole Tasks + Subtasks suite: `dotnet test --filter "FullyQualifiedName~TasksTests|FullyQualifiedName~SubtasksTests"` and **expect PASS** (all P3 tests green together — catches cross-test ordering/route-collision regressions, especially `/reorder` vs `/{id:guid}`).
- [ ] **Run** `dotnet build` and **expect PASS** with no warnings introduced by this phase.
- [ ] **Run** `dotnet format --verify-no-changes` (or the repo's configured formatter) and **expect PASS**; if it reports changes, run `dotnet format` and re-commit.
- [ ] **Commit (only if formatting changed anything):**

```
chore(api): format P3 validation + Tasks slice

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```
```

Phase P3 task list is complete above. Key deliverables: validation/error infra (`ValidationFilter<T>` verbatim, `AddTmapProblemDetails`, `GlobalExceptionHandler`, FluentValidation auto-registration in `Program.cs`) and the full Tasks slice exemplar (DTOs with no `UserId`, validators, all CRUD + filtered GET + reorder + nested subtasks) with TDD tests covering 201-create scoping, status/date/q filters, partial PATCH, soft-delete cascade-tombstone, single-row reorder, cross-user 404 isolation, and 400 ValidationProblemDetails.

Contract-gap flags surfaced at the top of the output: (1) no shared rank generator exists in the contract, so P3 uses a local inline midpoint/append helper rather than inventing a shared `LexoRank` symbol; (2) the contract's `Subtask` uses `SortOrder int` (not `Rank string`), so the subtask `UpdateSubtaskRequest.Rank` is mapped opaquely to `SortOrder` — flagged in case the contract intends subtask reordering to use a different mechanism.

---

## Phase P4 — Projects, NoteGroups, Notes slices

This phase implements three CRUD slices following the Tasks exemplar from P3 (DTOs, validators, RLS-scoped handlers, soft-delete with cascade tombstoning, reorder-by-rank), each with full per-endpoint tests.

### Task P4-1: Project CRUD happy-path test (create → get → patch)

- [ ] Write the failing test file `backend/tests/Tmap.Api.Tests/ProjectsTests.cs` with the create/list/patch happy-path test:

```csharp
using System.Net;
using System.Net.Http.Json;
using FluentAssertions;
using Tmap.Api.Features.Projects;
using Xunit;

namespace Tmap.Api.Tests;

[Collection("db")]
public sealed class ProjectsTests(PostgresFixture fixture) : IntegrationTestBase(fixture)
{
    [Fact]
    public async Task Create_then_get_then_patch_roundtrips()
    {
        var authed = await RegisterAsync();

        var createReq = new CreateProjectRequest(
            Name: "Acme",
            Color: "#ff8800",
            Emoji: "🚀",
            Rank: "a0");
        var createResp = await authed.Client.PostAsJsonAsync("/api/v1/projects", createReq);
        createResp.StatusCode.Should().Be(HttpStatusCode.Created);

        var created = await createResp.Content.ReadFromJsonAsync<ProjectResponse>();
        created.Should().NotBeNull();
        created!.Name.Should().Be("Acme");
        created.Color.Should().Be("#ff8800");
        created.Emoji.Should().Be("🚀");
        created.Rank.Should().Be("a0");
        created.ActualTimeMinutes.Should().Be(0);
        created.Id.Should().NotBe(Guid.Empty);

        var listResp = await authed.Client.GetAsync("/api/v1/projects");
        listResp.StatusCode.Should().Be(HttpStatusCode.OK);
        var list = await listResp.Content.ReadFromJsonAsync<List<ProjectResponse>>();
        list.Should().ContainSingle(p => p.Id == created.Id);

        var patchReq = new UpdateProjectRequest(
            Name: "Acme Renamed",
            Color: "#00aa55",
            Emoji: "🎯",
            Rank: "a1");
        var patchResp = await authed.Client.PatchAsJsonAsync($"/api/v1/projects/{created.Id}", patchReq);
        patchResp.StatusCode.Should().Be(HttpStatusCode.OK);
        var patched = await patchResp.Content.ReadFromJsonAsync<ProjectResponse>();
        patched!.Name.Should().Be("Acme Renamed");
        patched.Color.Should().Be("#00aa55");
        patched.Emoji.Should().Be("🎯");
        patched.Rank.Should().Be("a1");
        patched.Id.Should().Be(created.Id);
    }
}
```

- [ ] Run, expect FAIL: `dotnet test --filter "FullyQualifiedName~ProjectsTests.Create_then_get_then_patch_roundtrips"` — expect a **compile failure** with messages like `error CS0246: The type or namespace name 'CreateProjectRequest' could not be found` and `'UpdateProjectRequest' could not be found` and `'ProjectResponse' could not be found`.

### Task P4-2: Project DTOs (records)

- [ ] Create `backend/src/Tmap.Api/Features/Projects/ProjectDtos.cs` with the full DTO set:

```csharp
namespace Tmap.Api.Features.Projects;

public sealed record CreateProjectRequest(
    string Name,
    string Color,
    string Emoji,
    string Rank);

public sealed record UpdateProjectRequest(
    string Name,
    string Color,
    string Emoji,
    string Rank);

public sealed record ProjectResponse(
    Guid Id,
    string Name,
    string Color,
    string Emoji,
    string Rank,
    int ActualTimeMinutes,
    DateTimeOffset CreatedAt,
    DateTimeOffset UpdatedAt);

public sealed record ReorderItem(Guid Id, string Rank);
```

- [ ] Run, expect FAIL: `dotnet test --filter "FullyQualifiedName~ProjectsTests.Create_then_get_then_patch_roundtrips"` — DTOs now resolve, but the route is unmapped so the create call returns `404 Not Found`; expect FAIL with FluentAssertions message `Expected createResp.StatusCode to be HttpStatusCode.Created {value: 201}, but found HttpStatusCode.NotFound {value: 404}.`

### Task P4-3: Project validator

- [ ] Create `backend/src/Tmap.Api/Features/Projects/ProjectValidators.cs`:

```csharp
using FluentValidation;

namespace Tmap.Api.Features.Projects;

public sealed class CreateProjectValidator : AbstractValidator<CreateProjectRequest>
{
    public CreateProjectValidator()
    {
        RuleFor(x => x.Name).NotEmpty().MaximumLength(200);
        RuleFor(x => x.Color).NotEmpty().MaximumLength(32);
        RuleFor(x => x.Emoji).MaximumLength(16);
        RuleFor(x => x.Rank).NotEmpty().MaximumLength(64);
    }
}

public sealed class UpdateProjectValidator : AbstractValidator<UpdateProjectRequest>
{
    public UpdateProjectValidator()
    {
        RuleFor(x => x.Name).NotEmpty().MaximumLength(200);
        RuleFor(x => x.Color).NotEmpty().MaximumLength(32);
        RuleFor(x => x.Emoji).MaximumLength(16);
        RuleFor(x => x.Rank).NotEmpty().MaximumLength(64);
    }
}

public sealed class ReorderItemsValidator : AbstractValidator<IReadOnlyList<ReorderItem>>
{
    public ReorderItemsValidator()
    {
        RuleFor(x => x).NotEmpty();
        RuleForEach(x => x).ChildRules(item =>
        {
            item.RuleFor(i => i.Id).NotEmpty();
            item.RuleFor(i => i.Rank).NotEmpty().MaximumLength(64);
        });
    }
}
```

- [ ] Run, expect FAIL (unchanged): `dotnet test --filter "FullyQualifiedName~ProjectsTests.Create_then_get_then_patch_roundtrips"` — still `404` (route still unmapped). Same FluentAssertions message as P4-2.

### Task P4-4: Project endpoints + handlers (create/list/get/patch only, no delete/reorder yet)

- [ ] Create `backend/src/Tmap.Api/Features/Projects/ProjectEndpoints.cs` with the group, mapping, and handlers (delete/reorder added in P4-6 and P4-8):

```csharp
using Microsoft.AspNetCore.Http.HttpResults;
using Microsoft.EntityFrameworkCore;
using Tmap.Api.Common;
using Tmap.Api.Common.Validation;
using Tmap.Api.Infrastructure;
using Tmap.Api.Infrastructure.Entities;

namespace Tmap.Api.Features.Projects;

public static class ProjectEndpoints
{
    public static RouteGroupBuilder MapProjects(this IEndpointRouteBuilder app)
    {
        var group = app.MapGroup("/api/v1/projects").RequireAuthorization();

        group.MapGet("/", GetAll);
        group.MapPost("/", Create).AddEndpointFilter<ValidationFilter<CreateProjectRequest>>();
        group.MapPatch("/{id:guid}", Update).AddEndpointFilter<ValidationFilter<UpdateProjectRequest>>();

        return group;
    }

    private static async Task<Ok<List<ProjectResponse>>> GetAll(AppDbContext db, CancellationToken ct)
    {
        var projects = await db.Projects
            .OrderBy(p => p.Rank)
            .Select(p => ToResponse(p))
            .ToListAsync(ct);

        return TypedResults.Ok(projects);
    }

    private static async Task<Created<ProjectResponse>> Create(
        CreateProjectRequest req,
        AppDbContext db,
        ICurrentUser currentUser,
        CancellationToken ct)
    {
        var project = new Project
        {
            Id = Guid.CreateVersion7(),
            UserId = currentUser.Id,
            Name = req.Name,
            Color = req.Color,
            Emoji = req.Emoji,
            Rank = req.Rank,
            ActualTimeMinutes = 0,
        };

        db.Projects.Add(project);
        await db.SaveChangesAsync(ct);

        return TypedResults.Created($"/api/v1/projects/{project.Id}", ToResponse(project));
    }

    private static async Task<Results<Ok<ProjectResponse>, NotFound>> Update(
        Guid id,
        UpdateProjectRequest req,
        AppDbContext db,
        CancellationToken ct)
    {
        var project = await db.Projects.FirstOrDefaultAsync(p => p.Id == id, ct);
        if (project is null)
            return TypedResults.NotFound();

        project.Name = req.Name;
        project.Color = req.Color;
        project.Emoji = req.Emoji;
        project.Rank = req.Rank;

        await db.SaveChangesAsync(ct);

        return TypedResults.Ok(ToResponse(project));
    }

    private static ProjectResponse ToResponse(Project p) => new(
        p.Id,
        p.Name,
        p.Color,
        p.Emoji,
        p.Rank,
        p.ActualTimeMinutes,
        p.CreatedAt,
        p.UpdatedAt);
}
```

- [ ] Register the validators and map the group in `backend/src/Tmap.Api/Program.cs`. Add to the service-registration section:

```csharp
builder.Services.AddScoped<IValidator<Tmap.Api.Features.Projects.CreateProjectRequest>, Tmap.Api.Features.Projects.CreateProjectValidator>();
builder.Services.AddScoped<IValidator<Tmap.Api.Features.Projects.UpdateProjectRequest>, Tmap.Api.Features.Projects.UpdateProjectValidator>();
builder.Services.AddScoped<IValidator<IReadOnlyList<Tmap.Api.Features.Projects.ReorderItem>>, Tmap.Api.Features.Projects.ReorderItemsValidator>();
```

  and in the endpoint-mapping section (after auth slices are mapped):

```csharp
app.MapProjects();
```

  with `using Tmap.Api.Features.Projects;` and `using FluentValidation;` at the top of `Program.cs` if not already present.

- [ ] Run, expect PASS: `dotnet test --filter "FullyQualifiedName~ProjectsTests.Create_then_get_then_patch_roundtrips"`.
- [ ] Commit:

```
git add -A && git commit -m "$(cat <<'EOF'
feat(api): projects create/list/patch slice

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task P4-5: Project cross-user 404 test

- [ ] Add the cross-user isolation test to `backend/tests/Tmap.Api.Tests/ProjectsTests.cs`:

```csharp
    [Fact]
    public async Task Cross_user_cannot_see_or_mutate_anothers_project()
    {
        var alice = await RegisterAsync();
        var bob = await RegisterAsync();

        var createResp = await alice.Client.PostAsJsonAsync(
            "/api/v1/projects",
            new CreateProjectRequest("Alice Project", "#111111", "🔒", "a0"));
        createResp.StatusCode.Should().Be(HttpStatusCode.Created);
        var aliceProject = await createResp.Content.ReadFromJsonAsync<ProjectResponse>();

        // Bob's list must not include Alice's project.
        var bobList = await bob.Client.GetFromJsonAsync<List<ProjectResponse>>("/api/v1/projects");
        bobList.Should().NotContain(p => p.Id == aliceProject!.Id);

        // Bob cannot patch Alice's project (filtered out → 404, not 403).
        var bobPatch = await bob.Client.PatchAsJsonAsync(
            $"/api/v1/projects/{aliceProject!.Id}",
            new UpdateProjectRequest("Hijacked", "#000000", "💀", "z9"));
        bobPatch.StatusCode.Should().Be(HttpStatusCode.NotFound);

        // Bob cannot delete Alice's project.
        var bobDelete = await bob.Client.DeleteAsync($"/api/v1/projects/{aliceProject.Id}");
        bobDelete.StatusCode.Should().Be(HttpStatusCode.NotFound);

        // Alice still sees her project unchanged.
        var aliceList = await alice.Client.GetFromJsonAsync<List<ProjectResponse>>("/api/v1/projects");
        aliceList.Should().ContainSingle(p => p.Id == aliceProject.Id && p.Name == "Alice Project");
    }
```

- [ ] Run, expect FAIL: `dotnet test --filter "FullyQualifiedName~ProjectsTests.Cross_user_cannot_see_or_mutate_anothers_project"` — the patch/list assertions pass (the Tenant query filter already scopes them), but the `DELETE` endpoint is unmapped, so `bobDelete` returns `405 Method Not Allowed` (route exists for GET/POST but not DELETE on a sub-path) or `404`; either way expect FAIL on `bobDelete.StatusCode.Should().Be(HttpStatusCode.NotFound)` if a different code is returned. With no `{id}` DELETE route, ASP.NET returns `404`, so the precise failing line is the delete assertion only if routing differs — most likely the test FAILS at the delete line with `Expected bobDelete.StatusCode to be HttpStatusCode.NotFound {value: 404}, but found HttpStatusCode.MethodNotAllowed {value: 405}.` (Resolve in P4-6.)

### Task P4-6: Project DELETE — soft-delete self + set-null cascade to note_groups, notes, tasks

- [ ] Add the delete handler and its route to `ProjectEndpoints.cs`. Insert the route in `MapProjects` after the patch mapping:

```csharp
        group.MapDelete("/{id:guid}", Delete);
```

  and add the handler method (note the bulk `ExecuteUpdateAsync` null-out of child `ProjectId` on live rows; the Tenant named filter scopes these, RLS backstops, and the DB trigger advances `change_seq`/`updated_at`):

```csharp
    private static async Task<Results<NoContent, NotFound>> Delete(
        Guid id,
        AppDbContext db,
        ICurrentUser currentUser,
        CancellationToken ct)
    {
        var project = await db.Projects.FirstOrDefaultAsync(p => p.Id == id, ct);
        if (project is null)
            return TypedResults.NotFound();

        var now = DateTimeOffset.UtcNow;

        // Soft-delete the project itself.
        project.DeletedAt = now;
        await db.SaveChangesAsync(ct);

        // Set-null cascade: live note_groups, notes, and tasks owned by this user
        // that reference the project lose the reference (no-op tombstoning of children).
        await db.NoteGroups
            .Where(g => g.ProjectId == id)
            .ExecuteUpdateAsync(s => s.SetProperty(g => g.ProjectId, (Guid?)null), ct);

        await db.Notes
            .Where(n => n.ProjectId == id)
            .ExecuteUpdateAsync(s => s.SetProperty(n => n.ProjectId, (Guid?)null), ct);

        await db.Tasks
            .Where(t => t.ProjectId == id)
            .ExecuteUpdateAsync(s => s.SetProperty(t => t.ProjectId, (Guid?)null), ct);

        return TypedResults.NoContent();
    }
```

- [ ] Run, expect PASS: `dotnet test --filter "FullyQualifiedName~ProjectsTests.Cross_user_cannot_see_or_mutate_anothers_project"`.

### Task P4-7: Project DELETE cascade-behavior test (set-null on children, no-op tombstone on tasks)

- [ ] Add the cascade test to `ProjectsTests.cs`. It uses `NewElevatedDbContext()` to arrange child rows and to assert post-delete state directly against the DB. Required `using` lines at the top of the file: `using Microsoft.EntityFrameworkCore;`, `using Tmap.Api.Infrastructure.Entities;`.

```csharp
    [Fact]
    public async Task Delete_softdeletes_project_and_nulls_child_project_ids()
    {
        var authed = await RegisterAsync();

        var createResp = await authed.Client.PostAsJsonAsync(
            "/api/v1/projects",
            new CreateProjectRequest("ToDelete", "#abcabc", "🗑️", "a0"));
        var project = await createResp.Content.ReadFromJsonAsync<ProjectResponse>();
        var projectId = project!.Id;

        // Arrange children that reference the project, via the elevated context.
        var noteGroupId = Guid.CreateVersion7();
        var noteId = Guid.CreateVersion7();
        var taskId = Guid.CreateVersion7();
        await using (var arrange = NewElevatedDbContext())
        {
            arrange.NoteGroups.Add(new NoteGroup
            {
                Id = noteGroupId,
                UserId = authed.UserId,
                Name = "G",
                Emoji = "📁",
                ProjectId = projectId,
                Rank = "a0",
            });
            arrange.Notes.Add(new Note
            {
                Id = noteId,
                UserId = authed.UserId,
                GroupId = null,
                ProjectId = projectId,
                Title = "N",
                Content = "body",
                Rank = "a0",
            });
            arrange.Tasks.Add(new TaskItem
            {
                Id = taskId,
                UserId = authed.UserId,
                Title = "T",
                Notes = "",
                ProjectId = projectId,
                Labels = [],
                Source = "manual",
                Status = TaskStatus.Inbox,
                Rank = "a0",
            });
            await arrange.SaveChangesAsync();
        }

        var deleteResp = await authed.Client.DeleteAsync($"/api/v1/projects/{projectId}");
        deleteResp.StatusCode.Should().Be(HttpStatusCode.NoContent);

        // Project is gone from the live list.
        var list = await authed.Client.GetFromJsonAsync<List<ProjectResponse>>("/api/v1/projects");
        list.Should().NotContain(p => p.Id == projectId);

        await using var assertDb = NewElevatedDbContext();

        // Project itself is soft-deleted (tombstoned), not hard-deleted.
        var deletedProject = await assertDb.Projects
            .IgnoreQueryFilters(["SoftDelete"])
            .SingleAsync(p => p.Id == projectId);
        deletedProject.DeletedAt.Should().NotBeNull();

        // Children: project_id set to null, rows themselves still live.
        var group = await assertDb.NoteGroups.SingleAsync(g => g.Id == noteGroupId);
        group.ProjectId.Should().BeNull();
        group.DeletedAt.Should().BeNull();

        var note = await assertDb.Notes.SingleAsync(n => n.Id == noteId);
        note.ProjectId.Should().BeNull();
        note.DeletedAt.Should().BeNull();

        var task = await assertDb.Tasks.SingleAsync(t => t.Id == taskId);
        task.ProjectId.Should().BeNull();
        task.DeletedAt.Should().BeNull();
    }
```

- [ ] Run, expect PASS: `dotnet test --filter "FullyQualifiedName~ProjectsTests.Delete_softdeletes_project_and_nulls_child_project_ids"`.
- [ ] Commit:

```
git add -A && git commit -m "$(cat <<'EOF'
feat(api): project delete soft-deletes and nulls child project_id

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task P4-8: Project reorder (single-row rank change) — test first

- [ ] Add the reorder test to `ProjectsTests.cs`:

```csharp
    [Fact]
    public async Task Reorder_updates_only_specified_rows_ranks()
    {
        var authed = await RegisterAsync();

        var a = await (await authed.Client.PostAsJsonAsync(
            "/api/v1/projects", new CreateProjectRequest("A", "#1", "🅰️", "a0")))
            .Content.ReadFromJsonAsync<ProjectResponse>();
        var b = await (await authed.Client.PostAsJsonAsync(
            "/api/v1/projects", new CreateProjectRequest("B", "#2", "🅱️", "a1")))
            .Content.ReadFromJsonAsync<ProjectResponse>();

        // Move B to rank "a0m" (between nothing and A in lexicographic terms is irrelevant;
        // the contract only requires the single addressed row's rank change).
        var reorderResp = await authed.Client.PatchAsJsonAsync(
            "/api/v1/projects/reorder",
            new[] { new ReorderItem(b!.Id, "Zz") });
        reorderResp.StatusCode.Should().Be(HttpStatusCode.NoContent);

        var list = await authed.Client.GetFromJsonAsync<List<ProjectResponse>>("/api/v1/projects");
        list!.Single(p => p.Id == b.Id).Rank.Should().Be("Zz");
        // A is untouched.
        list.Single(p => p.Id == a!.Id).Rank.Should().Be("a0");
        // Ordering reflects new ranks: A ("a0") before B ("Zz")? No — "Zz" < "a0" lexicographically,
        // so B sorts first. Verify ordering by rank.
        list.Select(p => p.Rank).Should().BeInAscendingOrder();
    }
```

- [ ] Run, expect FAIL: `dotnet test --filter "FullyQualifiedName~ProjectsTests.Reorder_updates_only_specified_rows_ranks"` — the `/reorder` route is unmapped; expect FAIL on `reorderResp.StatusCode.Should().Be(HttpStatusCode.NoContent)` with `but found HttpStatusCode.NotFound {value: 404}.`

### Task P4-9: Project reorder endpoint + handler

- [ ] Add the reorder route to `MapProjects` (place it **before** the `/{id:guid}` routes so the literal segment is matched first):

```csharp
        group.MapPatch("/reorder", Reorder)
            .AddEndpointFilter<ValidationFilter<IReadOnlyList<ReorderItem>>>();
```

  and add the handler (bulk per-row rank update scoped by the Tenant filter; only addressed live rows change):

```csharp
    private static async Task<NoContent> Reorder(
        IReadOnlyList<ReorderItem> items,
        AppDbContext db,
        CancellationToken ct)
    {
        foreach (var item in items)
        {
            await db.Projects
                .Where(p => p.Id == item.Id)
                .ExecuteUpdateAsync(s => s.SetProperty(p => p.Rank, item.Rank), ct);
        }

        return TypedResults.NoContent();
    }
```

- [ ] Run, expect PASS: `dotnet test --filter "FullyQualifiedName~ProjectsTests.Reorder_updates_only_specified_rows_ranks"`.
- [ ] Run the whole Projects class, expect PASS: `dotnet test --filter "FullyQualifiedName~ProjectsTests"`.
- [ ] Commit:

```
git add -A && git commit -m "$(cat <<'EOF'
feat(api): project reorder by rank (single-row update)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Slice 2 — NoteGroups

### Task P4-10: NoteGroup CRUD happy-path test

- [ ] Create `backend/tests/Tmap.Api.Tests/NoteGroupsTests.cs` with the happy-path test:

```csharp
using System.Net;
using System.Net.Http.Json;
using FluentAssertions;
using Microsoft.EntityFrameworkCore;
using Tmap.Api.Features.NoteGroups;
using Tmap.Api.Infrastructure.Entities;
using Xunit;

namespace Tmap.Api.Tests;

[Collection("db")]
public sealed class NoteGroupsTests(PostgresFixture fixture) : IntegrationTestBase(fixture)
{
    [Fact]
    public async Task Create_then_get_then_patch_roundtrips()
    {
        var authed = await RegisterAsync();

        var createResp = await authed.Client.PostAsJsonAsync(
            "/api/v1/note-groups",
            new CreateNoteGroupRequest(Name: "Inbox", Emoji: "📥", ProjectId: null, Rank: "a0"));
        createResp.StatusCode.Should().Be(HttpStatusCode.Created);

        var created = await createResp.Content.ReadFromJsonAsync<NoteGroupResponse>();
        created!.Name.Should().Be("Inbox");
        created.Emoji.Should().Be("📥");
        created.ProjectId.Should().BeNull();
        created.Rank.Should().Be("a0");
        created.Id.Should().NotBe(Guid.Empty);

        var list = await authed.Client.GetFromJsonAsync<List<NoteGroupResponse>>("/api/v1/note-groups");
        list.Should().ContainSingle(g => g.Id == created.Id);

        var patchResp = await authed.Client.PatchAsJsonAsync(
            $"/api/v1/note-groups/{created.Id}",
            new UpdateNoteGroupRequest(Name: "Renamed", Emoji: "📦", ProjectId: null, Rank: "a1"));
        patchResp.StatusCode.Should().Be(HttpStatusCode.OK);
        var patched = await patchResp.Content.ReadFromJsonAsync<NoteGroupResponse>();
        patched!.Name.Should().Be("Renamed");
        patched.Emoji.Should().Be("📦");
        patched.Rank.Should().Be("a1");
        patched.Id.Should().Be(created.Id);
    }
}
```

- [ ] Run, expect FAIL: `dotnet test --filter "FullyQualifiedName~NoteGroupsTests.Create_then_get_then_patch_roundtrips"` — expect **compile failure** `error CS0246: ... 'CreateNoteGroupRequest' could not be found`, `'UpdateNoteGroupRequest'`, `'NoteGroupResponse'`.

### Task P4-11: NoteGroup DTOs

- [ ] Create `backend/src/Tmap.Api/Features/NoteGroups/NoteGroupDtos.cs`:

```csharp
namespace Tmap.Api.Features.NoteGroups;

public sealed record CreateNoteGroupRequest(
    string Name,
    string Emoji,
    Guid? ProjectId,
    string Rank);

public sealed record UpdateNoteGroupRequest(
    string Name,
    string Emoji,
    Guid? ProjectId,
    string Rank);

public sealed record NoteGroupResponse(
    Guid Id,
    string Name,
    string Emoji,
    Guid? ProjectId,
    string Rank,
    DateTimeOffset CreatedAt,
    DateTimeOffset UpdatedAt);

public sealed record ReorderItem(Guid Id, string Rank);
```

- [ ] Run, expect FAIL: `dotnet test --filter "FullyQualifiedName~NoteGroupsTests.Create_then_get_then_patch_roundtrips"` — DTOs resolve; route unmapped so create returns `404`. Expect FAIL `Expected createResp.StatusCode to be HttpStatusCode.Created {value: 201}, but found HttpStatusCode.NotFound {value: 404}.`

### Task P4-12: NoteGroup validator

- [ ] Create `backend/src/Tmap.Api/Features/NoteGroups/NoteGroupValidators.cs`:

```csharp
using FluentValidation;

namespace Tmap.Api.Features.NoteGroups;

public sealed class CreateNoteGroupValidator : AbstractValidator<CreateNoteGroupRequest>
{
    public CreateNoteGroupValidator()
    {
        RuleFor(x => x.Name).NotEmpty().MaximumLength(200);
        RuleFor(x => x.Emoji).MaximumLength(16);
        RuleFor(x => x.Rank).NotEmpty().MaximumLength(64);
    }
}

public sealed class UpdateNoteGroupValidator : AbstractValidator<UpdateNoteGroupRequest>
{
    public UpdateNoteGroupValidator()
    {
        RuleFor(x => x.Name).NotEmpty().MaximumLength(200);
        RuleFor(x => x.Emoji).MaximumLength(16);
        RuleFor(x => x.Rank).NotEmpty().MaximumLength(64);
    }
}

public sealed class ReorderItemsValidator : AbstractValidator<IReadOnlyList<ReorderItem>>
{
    public ReorderItemsValidator()
    {
        RuleFor(x => x).NotEmpty();
        RuleForEach(x => x).ChildRules(item =>
        {
            item.RuleFor(i => i.Id).NotEmpty();
            item.RuleFor(i => i.Rank).NotEmpty().MaximumLength(64);
        });
    }
}
```

- [ ] Run, expect FAIL (unchanged `404`): `dotnet test --filter "FullyQualifiedName~NoteGroupsTests.Create_then_get_then_patch_roundtrips"`.

### Task P4-13: NoteGroup endpoints + handlers (create/list/get-filtered/patch; delete/reorder later)

- [ ] Create `backend/src/Tmap.Api/Features/NoteGroups/NoteGroupEndpoints.cs`. The `GET` supports an optional `projectId` filter (`/note-groups?projectId=`):

```csharp
using Microsoft.AspNetCore.Http.HttpResults;
using Microsoft.EntityFrameworkCore;
using Tmap.Api.Common;
using Tmap.Api.Common.Validation;
using Tmap.Api.Infrastructure;
using Tmap.Api.Infrastructure.Entities;

namespace Tmap.Api.Features.NoteGroups;

public static class NoteGroupEndpoints
{
    public static RouteGroupBuilder MapNoteGroups(this IEndpointRouteBuilder app)
    {
        var group = app.MapGroup("/api/v1/note-groups").RequireAuthorization();

        group.MapGet("/", GetAll);
        group.MapPost("/", Create).AddEndpointFilter<ValidationFilter<CreateNoteGroupRequest>>();
        group.MapPatch("/{id:guid}", Update).AddEndpointFilter<ValidationFilter<UpdateNoteGroupRequest>>();

        return group;
    }

    private static async Task<Ok<List<NoteGroupResponse>>> GetAll(
        Guid? projectId,
        AppDbContext db,
        CancellationToken ct)
    {
        var query = db.NoteGroups.AsQueryable();
        if (projectId is { } pid)
            query = query.Where(g => g.ProjectId == pid);

        var groups = await query
            .OrderBy(g => g.Rank)
            .Select(g => ToResponse(g))
            .ToListAsync(ct);

        return TypedResults.Ok(groups);
    }

    private static async Task<Created<NoteGroupResponse>> Create(
        CreateNoteGroupRequest req,
        AppDbContext db,
        ICurrentUser currentUser,
        CancellationToken ct)
    {
        var group = new NoteGroup
        {
            Id = Guid.CreateVersion7(),
            UserId = currentUser.Id,
            Name = req.Name,
            Emoji = req.Emoji,
            ProjectId = req.ProjectId,
            Rank = req.Rank,
        };

        db.NoteGroups.Add(group);
        await db.SaveChangesAsync(ct);

        return TypedResults.Created($"/api/v1/note-groups/{group.Id}", ToResponse(group));
    }

    private static async Task<Results<Ok<NoteGroupResponse>, NotFound>> Update(
        Guid id,
        UpdateNoteGroupRequest req,
        AppDbContext db,
        CancellationToken ct)
    {
        var group = await db.NoteGroups.FirstOrDefaultAsync(g => g.Id == id, ct);
        if (group is null)
            return TypedResults.NotFound();

        group.Name = req.Name;
        group.Emoji = req.Emoji;
        group.ProjectId = req.ProjectId;
        group.Rank = req.Rank;

        await db.SaveChangesAsync(ct);

        return TypedResults.Ok(ToResponse(group));
    }

    private static NoteGroupResponse ToResponse(NoteGroup g) => new(
        g.Id,
        g.Name,
        g.Emoji,
        g.ProjectId,
        g.Rank,
        g.CreatedAt,
        g.UpdatedAt);
}
```

- [ ] Register validators and map the group in `Program.cs`. Add to service registration:

```csharp
builder.Services.AddScoped<IValidator<Tmap.Api.Features.NoteGroups.CreateNoteGroupRequest>, Tmap.Api.Features.NoteGroups.CreateNoteGroupValidator>();
builder.Services.AddScoped<IValidator<Tmap.Api.Features.NoteGroups.UpdateNoteGroupRequest>, Tmap.Api.Features.NoteGroups.UpdateNoteGroupValidator>();
builder.Services.AddScoped<IValidator<IReadOnlyList<Tmap.Api.Features.NoteGroups.ReorderItem>>, Tmap.Api.Features.NoteGroups.ReorderItemsValidator>();
```

  and to endpoint mapping:

```csharp
app.MapNoteGroups();
```

  with `using Tmap.Api.Features.NoteGroups;` at the top of `Program.cs`.

- [ ] Run, expect PASS: `dotnet test --filter "FullyQualifiedName~NoteGroupsTests.Create_then_get_then_patch_roundtrips"`.
- [ ] Commit:

```
git add -A && git commit -m "$(cat <<'EOF'
feat(api): note-groups create/list/patch slice with projectId filter

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task P4-14: NoteGroup filtered read + cross-user 404 test

- [ ] Add the filtered-read and cross-user tests to `NoteGroupsTests.cs`:

```csharp
    [Fact]
    public async Task GetAll_filters_by_projectId()
    {
        var authed = await RegisterAsync();
        var projectId = Guid.CreateVersion7();
        var otherProjectId = Guid.CreateVersion7();

        // Create a real project so the FK reference is valid, then groups referencing it.
        await using (var arrange = NewElevatedDbContext())
        {
            arrange.Projects.Add(new Project { Id = projectId, UserId = authed.UserId, Name = "P1", Color = "#1", Emoji = "📁", Rank = "a0", ActualTimeMinutes = 0 });
            arrange.Projects.Add(new Project { Id = otherProjectId, UserId = authed.UserId, Name = "P2", Color = "#2", Emoji = "📁", Rank = "a1", ActualTimeMinutes = 0 });
            await arrange.SaveChangesAsync();
        }

        var inP1 = await (await authed.Client.PostAsJsonAsync("/api/v1/note-groups",
            new CreateNoteGroupRequest("G-P1", "📁", projectId, "a0")))
            .Content.ReadFromJsonAsync<NoteGroupResponse>();
        var inP2 = await (await authed.Client.PostAsJsonAsync("/api/v1/note-groups",
            new CreateNoteGroupRequest("G-P2", "📁", otherProjectId, "a0")))
            .Content.ReadFromJsonAsync<NoteGroupResponse>();
        var unassigned = await (await authed.Client.PostAsJsonAsync("/api/v1/note-groups",
            new CreateNoteGroupRequest("G-none", "📁", null, "a0")))
            .Content.ReadFromJsonAsync<NoteGroupResponse>();

        var filtered = await authed.Client.GetFromJsonAsync<List<NoteGroupResponse>>(
            $"/api/v1/note-groups?projectId={projectId}");
        filtered.Should().ContainSingle(g => g.Id == inP1!.Id);
        filtered.Should().NotContain(g => g.Id == inP2!.Id);
        filtered.Should().NotContain(g => g.Id == unassigned!.Id);

        var all = await authed.Client.GetFromJsonAsync<List<NoteGroupResponse>>("/api/v1/note-groups");
        all!.Select(g => g.Id).Should().Contain(new[] { inP1!.Id, inP2!.Id, unassigned!.Id });
    }

    [Fact]
    public async Task Cross_user_cannot_see_or_mutate_anothers_note_group()
    {
        var alice = await RegisterAsync();
        var bob = await RegisterAsync();

        var created = await (await alice.Client.PostAsJsonAsync("/api/v1/note-groups",
            new CreateNoteGroupRequest("Alice Group", "🔒", null, "a0")))
            .Content.ReadFromJsonAsync<NoteGroupResponse>();

        var bobList = await bob.Client.GetFromJsonAsync<List<NoteGroupResponse>>("/api/v1/note-groups");
        bobList.Should().NotContain(g => g.Id == created!.Id);

        var bobPatch = await bob.Client.PatchAsJsonAsync($"/api/v1/note-groups/{created!.Id}",
            new UpdateNoteGroupRequest("Hijack", "💀", null, "z9"));
        bobPatch.StatusCode.Should().Be(HttpStatusCode.NotFound);

        var bobDelete = await bob.Client.DeleteAsync($"/api/v1/note-groups/{created.Id}");
        bobDelete.StatusCode.Should().Be(HttpStatusCode.NotFound);
    }
```

- [ ] Run, expect FAIL: `dotnet test --filter "FullyQualifiedName~NoteGroupsTests.GetAll_filters_by_projectId|FullyQualifiedName~NoteGroupsTests.Cross_user_cannot_see_or_mutate_anothers_note_group"` — `GetAll_filters_by_projectId` PASSES, but `Cross_user_cannot_see_or_mutate_anothers_note_group` FAILS on the delete assertion because the DELETE route is unmapped: `Expected bobDelete.StatusCode to be HttpStatusCode.NotFound {value: 404}, but found HttpStatusCode.MethodNotAllowed {value: 405}.` (Resolve DELETE in P4-15.)

### Task P4-15: NoteGroup DELETE — soft-delete self + cascade-tombstone its notes

- [ ] Add the delete route to `MapNoteGroups`:

```csharp
        group.MapDelete("/{id:guid}", Delete);
```

  and the handler (soft-deletes the group, then cascade-tombstones its live notes by setting `DeletedAt`; the trigger bumps `change_seq`/`updated_at`):

```csharp
    private static async Task<Results<NoContent, NotFound>> Delete(
        Guid id,
        AppDbContext db,
        CancellationToken ct)
    {
        var group = await db.NoteGroups.FirstOrDefaultAsync(g => g.Id == id, ct);
        if (group is null)
            return TypedResults.NotFound();

        var now = DateTimeOffset.UtcNow;

        group.DeletedAt = now;
        await db.SaveChangesAsync(ct);

        // Cascade-tombstone the group's live notes.
        await db.Notes
            .Where(n => n.GroupId == id)
            .ExecuteUpdateAsync(s => s.SetProperty(n => n.DeletedAt, now), ct);

        return TypedResults.NoContent();
    }
```

- [ ] Run, expect PASS: `dotnet test --filter "FullyQualifiedName~NoteGroupsTests.Cross_user_cannot_see_or_mutate_anothers_note_group"`.

### Task P4-16: NoteGroup DELETE cascade-tombstone behavior test

- [ ] Add the cascade-tombstone test to `NoteGroupsTests.cs`:

```csharp
    [Fact]
    public async Task Delete_softdeletes_group_and_tombstones_its_notes()
    {
        var authed = await RegisterAsync();

        var group = await (await authed.Client.PostAsJsonAsync("/api/v1/note-groups",
            new CreateNoteGroupRequest("WithNotes", "📁", null, "a0")))
            .Content.ReadFromJsonAsync<NoteGroupResponse>();
        var groupId = group!.Id;

        var noteInGroupId = Guid.CreateVersion7();
        var looseNoteId = Guid.CreateVersion7();
        await using (var arrange = NewElevatedDbContext())
        {
            arrange.Notes.Add(new Note
            {
                Id = noteInGroupId, UserId = authed.UserId, GroupId = groupId,
                ProjectId = null, Title = "in group", Content = "x", Rank = "a0",
            });
            arrange.Notes.Add(new Note
            {
                Id = looseNoteId, UserId = authed.UserId, GroupId = null,
                ProjectId = null, Title = "loose", Content = "y", Rank = "a0",
            });
            await arrange.SaveChangesAsync();
        }

        var deleteResp = await authed.Client.DeleteAsync($"/api/v1/note-groups/{groupId}");
        deleteResp.StatusCode.Should().Be(HttpStatusCode.NoContent);

        await using var assertDb = NewElevatedDbContext();

        // Group is tombstoned, not hard-deleted.
        var deletedGroup = await assertDb.NoteGroups
            .IgnoreQueryFilters(["SoftDelete"])
            .SingleAsync(g => g.Id == groupId);
        deletedGroup.DeletedAt.Should().NotBeNull();

        // The note that was in the group is tombstoned.
        var deletedNote = await assertDb.Notes
            .IgnoreQueryFilters(["SoftDelete"])
            .SingleAsync(n => n.Id == noteInGroupId);
        deletedNote.DeletedAt.Should().NotBeNull();

        // The loose note (different group) is untouched.
        var liveNote = await assertDb.Notes.SingleAsync(n => n.Id == looseNoteId);
        liveNote.DeletedAt.Should().BeNull();
    }
```

- [ ] Run, expect PASS: `dotnet test --filter "FullyQualifiedName~NoteGroupsTests.Delete_softdeletes_group_and_tombstones_its_notes"`.
- [ ] Commit:

```
git add -A && git commit -m "$(cat <<'EOF'
feat(api): note-group delete cascade-tombstones its notes

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task P4-17: NoteGroup reorder — test first

- [ ] Add the reorder test to `NoteGroupsTests.cs`:

```csharp
    [Fact]
    public async Task Reorder_updates_only_specified_rows_ranks()
    {
        var authed = await RegisterAsync();

        var a = await (await authed.Client.PostAsJsonAsync("/api/v1/note-groups",
            new CreateNoteGroupRequest("A", "📁", null, "a0")))
            .Content.ReadFromJsonAsync<NoteGroupResponse>();
        var b = await (await authed.Client.PostAsJsonAsync("/api/v1/note-groups",
            new CreateNoteGroupRequest("B", "📁", null, "a1")))
            .Content.ReadFromJsonAsync<NoteGroupResponse>();

        var reorderResp = await authed.Client.PatchAsJsonAsync(
            "/api/v1/note-groups/reorder",
            new[] { new ReorderItem(b!.Id, "Zz") });
        reorderResp.StatusCode.Should().Be(HttpStatusCode.NoContent);

        var list = await authed.Client.GetFromJsonAsync<List<NoteGroupResponse>>("/api/v1/note-groups");
        list!.Single(g => g.Id == b.Id).Rank.Should().Be("Zz");
        list.Single(g => g.Id == a!.Id).Rank.Should().Be("a0");
        list.Select(g => g.Rank).Should().BeInAscendingOrder();
    }
```

- [ ] Run, expect FAIL: `dotnet test --filter "FullyQualifiedName~NoteGroupsTests.Reorder_updates_only_specified_rows_ranks"` — `/reorder` unmapped; expect FAIL `Expected reorderResp.StatusCode to be HttpStatusCode.NoContent {value: 204}, but found HttpStatusCode.NotFound {value: 404}.`

### Task P4-18: NoteGroup reorder endpoint + handler

- [ ] Add the reorder route to `MapNoteGroups` **before** the `/{id:guid}` routes:

```csharp
        group.MapPatch("/reorder", Reorder)
            .AddEndpointFilter<ValidationFilter<IReadOnlyList<ReorderItem>>>();
```

  and the handler:

```csharp
    private static async Task<NoContent> Reorder(
        IReadOnlyList<ReorderItem> items,
        AppDbContext db,
        CancellationToken ct)
    {
        foreach (var item in items)
        {
            await db.NoteGroups
                .Where(g => g.Id == item.Id)
                .ExecuteUpdateAsync(s => s.SetProperty(g => g.Rank, item.Rank), ct);
        }

        return TypedResults.NoContent();
    }
```

- [ ] Run, expect PASS: `dotnet test --filter "FullyQualifiedName~NoteGroupsTests.Reorder_updates_only_specified_rows_ranks"`.
- [ ] Run the whole class, expect PASS: `dotnet test --filter "FullyQualifiedName~NoteGroupsTests"`.
- [ ] Commit:

```
git add -A && git commit -m "$(cat <<'EOF'
feat(api): note-group reorder by rank (single-row update)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Slice 3 — Notes

### Task P4-19: Note CRUD happy-path test (create → get-by-id → list → patch)

- [ ] Create `backend/tests/Tmap.Api.Tests/NotesTests.cs`:

```csharp
using System.Net;
using System.Net.Http.Json;
using FluentAssertions;
using Microsoft.EntityFrameworkCore;
using Tmap.Api.Features.Notes;
using Tmap.Api.Infrastructure.Entities;
using Xunit;

namespace Tmap.Api.Tests;

[Collection("db")]
public sealed class NotesTests(PostgresFixture fixture) : IntegrationTestBase(fixture)
{
    [Fact]
    public async Task Create_getById_list_patch_roundtrips()
    {
        var authed = await RegisterAsync();

        var createResp = await authed.Client.PostAsJsonAsync(
            "/api/v1/notes",
            new CreateNoteRequest(GroupId: null, ProjectId: null, Title: "First", Content: "hello", Rank: "a0"));
        createResp.StatusCode.Should().Be(HttpStatusCode.Created);
        var created = await createResp.Content.ReadFromJsonAsync<NoteResponse>();
        created!.Title.Should().Be("First");
        created.Content.Should().Be("hello");
        created.GroupId.Should().BeNull();
        created.ProjectId.Should().BeNull();
        created.Rank.Should().Be("a0");
        created.Id.Should().NotBe(Guid.Empty);

        var getResp = await authed.Client.GetAsync($"/api/v1/notes/{created.Id}");
        getResp.StatusCode.Should().Be(HttpStatusCode.OK);
        var fetched = await getResp.Content.ReadFromJsonAsync<NoteResponse>();
        fetched!.Id.Should().Be(created.Id);
        fetched.Content.Should().Be("hello");

        var list = await authed.Client.GetFromJsonAsync<List<NoteResponse>>("/api/v1/notes");
        list.Should().ContainSingle(n => n.Id == created.Id);

        var patchResp = await authed.Client.PatchAsJsonAsync(
            $"/api/v1/notes/{created.Id}",
            new UpdateNoteRequest(GroupId: null, ProjectId: null, Title: "First Edited", Content: "world", Rank: "a1"));
        patchResp.StatusCode.Should().Be(HttpStatusCode.OK);
        var patched = await patchResp.Content.ReadFromJsonAsync<NoteResponse>();
        patched!.Title.Should().Be("First Edited");
        patched.Content.Should().Be("world");
        patched.Rank.Should().Be("a1");
        patched.Id.Should().Be(created.Id);
    }
}
```

- [ ] Run, expect FAIL: `dotnet test --filter "FullyQualifiedName~NotesTests.Create_getById_list_patch_roundtrips"` — expect **compile failure** `error CS0246: ... 'CreateNoteRequest' could not be found`, `'UpdateNoteRequest'`, `'NoteResponse'`.

### Task P4-20: Note DTOs

- [ ] Create `backend/src/Tmap.Api/Features/Notes/NoteDtos.cs`:

```csharp
namespace Tmap.Api.Features.Notes;

public sealed record CreateNoteRequest(
    Guid? GroupId,
    Guid? ProjectId,
    string Title,
    string Content,
    string Rank);

public sealed record UpdateNoteRequest(
    Guid? GroupId,
    Guid? ProjectId,
    string Title,
    string Content,
    string Rank);

public sealed record NoteResponse(
    Guid Id,
    Guid? GroupId,
    Guid? ProjectId,
    string Title,
    string Content,
    string Rank,
    DateTimeOffset CreatedAt,
    DateTimeOffset UpdatedAt);

public sealed record ReorderItem(Guid Id, string Rank);
```

- [ ] Run, expect FAIL: `dotnet test --filter "FullyQualifiedName~NotesTests.Create_getById_list_patch_roundtrips"` — DTOs resolve; route unmapped so create returns `404`. Expect FAIL `Expected createResp.StatusCode to be HttpStatusCode.Created {value: 201}, but found HttpStatusCode.NotFound {value: 404}.`

### Task P4-21: Note validator

- [ ] Create `backend/src/Tmap.Api/Features/Notes/NoteValidators.cs`:

```csharp
using FluentValidation;

namespace Tmap.Api.Features.Notes;

public sealed class CreateNoteValidator : AbstractValidator<CreateNoteRequest>
{
    public CreateNoteValidator()
    {
        RuleFor(x => x.Title).NotNull().MaximumLength(500);
        RuleFor(x => x.Content).NotNull();
        RuleFor(x => x.Rank).NotEmpty().MaximumLength(64);
    }
}

public sealed class UpdateNoteValidator : AbstractValidator<UpdateNoteRequest>
{
    public UpdateNoteValidator()
    {
        RuleFor(x => x.Title).NotNull().MaximumLength(500);
        RuleFor(x => x.Content).NotNull();
        RuleFor(x => x.Rank).NotEmpty().MaximumLength(64);
    }
}

public sealed class ReorderItemsValidator : AbstractValidator<IReadOnlyList<ReorderItem>>
{
    public ReorderItemsValidator()
    {
        RuleFor(x => x).NotEmpty();
        RuleForEach(x => x).ChildRules(item =>
        {
            item.RuleFor(i => i.Id).NotEmpty();
            item.RuleFor(i => i.Rank).NotEmpty().MaximumLength(64);
        });
    }
}
```

- [ ] Run, expect FAIL (unchanged `404`): `dotnet test --filter "FullyQualifiedName~NotesTests.Create_getById_list_patch_roundtrips"`.

### Task P4-22: Note endpoints + handlers (create/list-filtered/get-by-id/patch; delete/reorder later)

- [ ] Create `backend/src/Tmap.Api/Features/Notes/NoteEndpoints.cs`. `GET /notes` supports `?groupId=&projectId=` filters; `GET /notes/{id}` returns one note (embedded-free):

```csharp
using Microsoft.AspNetCore.Http.HttpResults;
using Microsoft.EntityFrameworkCore;
using Tmap.Api.Common;
using Tmap.Api.Common.Validation;
using Tmap.Api.Infrastructure;
using Tmap.Api.Infrastructure.Entities;

namespace Tmap.Api.Features.Notes;

public static class NoteEndpoints
{
    public static RouteGroupBuilder MapNotes(this IEndpointRouteBuilder app)
    {
        var group = app.MapGroup("/api/v1/notes").RequireAuthorization();

        group.MapGet("/", GetAll);
        group.MapGet("/{id:guid}", GetById);
        group.MapPost("/", Create).AddEndpointFilter<ValidationFilter<CreateNoteRequest>>();
        group.MapPatch("/{id:guid}", Update).AddEndpointFilter<ValidationFilter<UpdateNoteRequest>>();

        return group;
    }

    private static async Task<Ok<List<NoteResponse>>> GetAll(
        Guid? groupId,
        Guid? projectId,
        AppDbContext db,
        CancellationToken ct)
    {
        var query = db.Notes.AsQueryable();
        if (groupId is { } gid)
            query = query.Where(n => n.GroupId == gid);
        if (projectId is { } pid)
            query = query.Where(n => n.ProjectId == pid);

        var notes = await query
            .OrderBy(n => n.Rank)
            .Select(n => ToResponse(n))
            .ToListAsync(ct);

        return TypedResults.Ok(notes);
    }

    private static async Task<Results<Ok<NoteResponse>, NotFound>> GetById(
        Guid id,
        AppDbContext db,
        CancellationToken ct)
    {
        var note = await db.Notes.FirstOrDefaultAsync(n => n.Id == id, ct);
        return note is null
            ? TypedResults.NotFound()
            : TypedResults.Ok(ToResponse(note));
    }

    private static async Task<Created<NoteResponse>> Create(
        CreateNoteRequest req,
        AppDbContext db,
        ICurrentUser currentUser,
        CancellationToken ct)
    {
        var note = new Note
        {
            Id = Guid.CreateVersion7(),
            UserId = currentUser.Id,
            GroupId = req.GroupId,
            ProjectId = req.ProjectId,
            Title = req.Title,
            Content = req.Content,
            Rank = req.Rank,
        };

        db.Notes.Add(note);
        await db.SaveChangesAsync(ct);

        return TypedResults.Created($"/api/v1/notes/{note.Id}", ToResponse(note));
    }

    private static async Task<Results<Ok<NoteResponse>, NotFound>> Update(
        Guid id,
        UpdateNoteRequest req,
        AppDbContext db,
        CancellationToken ct)
    {
        var note = await db.Notes.FirstOrDefaultAsync(n => n.Id == id, ct);
        if (note is null)
            return TypedResults.NotFound();

        note.GroupId = req.GroupId;
        note.ProjectId = req.ProjectId;
        note.Title = req.Title;
        note.Content = req.Content;
        note.Rank = req.Rank;

        await db.SaveChangesAsync(ct);

        return TypedResults.Ok(ToResponse(note));
    }

    private static NoteResponse ToResponse(Note n) => new(
        n.Id,
        n.GroupId,
        n.ProjectId,
        n.Title,
        n.Content,
        n.Rank,
        n.CreatedAt,
        n.UpdatedAt);
}
```

- [ ] Register validators and map the group in `Program.cs`. Add to service registration:

```csharp
builder.Services.AddScoped<IValidator<Tmap.Api.Features.Notes.CreateNoteRequest>, Tmap.Api.Features.Notes.CreateNoteValidator>();
builder.Services.AddScoped<IValidator<Tmap.Api.Features.Notes.UpdateNoteRequest>, Tmap.Api.Features.Notes.UpdateNoteValidator>();
builder.Services.AddScoped<IValidator<IReadOnlyList<Tmap.Api.Features.Notes.ReorderItem>>, Tmap.Api.Features.Notes.ReorderItemsValidator>();
```

  and to endpoint mapping:

```csharp
app.MapNotes();
```

  with `using Tmap.Api.Features.Notes;` at the top of `Program.cs`.

- [ ] Run, expect PASS: `dotnet test --filter "FullyQualifiedName~NotesTests.Create_getById_list_patch_roundtrips"`.
- [ ] Commit:

```
git add -A && git commit -m "$(cat <<'EOF'
feat(api): notes create/list/get-by-id/patch slice with group+project filters

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task P4-23: Note filtered reads + cross-user 404 test

- [ ] Add the filtered-read and cross-user tests to `NotesTests.cs`:

```csharp
    [Fact]
    public async Task GetAll_filters_by_groupId_and_projectId()
    {
        var authed = await RegisterAsync();
        var groupId = Guid.CreateVersion7();
        var projectId = Guid.CreateVersion7();

        await using (var arrange = NewElevatedDbContext())
        {
            arrange.Projects.Add(new Project { Id = projectId, UserId = authed.UserId, Name = "P", Color = "#1", Emoji = "📁", Rank = "a0", ActualTimeMinutes = 0 });
            arrange.NoteGroups.Add(new NoteGroup { Id = groupId, UserId = authed.UserId, Name = "G", Emoji = "📁", ProjectId = null, Rank = "a0" });
            await arrange.SaveChangesAsync();
        }

        var inGroup = await (await authed.Client.PostAsJsonAsync("/api/v1/notes",
            new CreateNoteRequest(groupId, null, "in-group", "x", "a0")))
            .Content.ReadFromJsonAsync<NoteResponse>();
        var inProject = await (await authed.Client.PostAsJsonAsync("/api/v1/notes",
            new CreateNoteRequest(null, projectId, "in-project", "y", "a0")))
            .Content.ReadFromJsonAsync<NoteResponse>();
        var loose = await (await authed.Client.PostAsJsonAsync("/api/v1/notes",
            new CreateNoteRequest(null, null, "loose", "z", "a0")))
            .Content.ReadFromJsonAsync<NoteResponse>();

        var byGroup = await authed.Client.GetFromJsonAsync<List<NoteResponse>>(
            $"/api/v1/notes?groupId={groupId}");
        byGroup.Should().ContainSingle(n => n.Id == inGroup!.Id);
        byGroup.Should().NotContain(n => n.Id == inProject!.Id || n.Id == loose!.Id);

        var byProject = await authed.Client.GetFromJsonAsync<List<NoteResponse>>(
            $"/api/v1/notes?projectId={projectId}");
        byProject.Should().ContainSingle(n => n.Id == inProject!.Id);
        byProject.Should().NotContain(n => n.Id == inGroup!.Id || n.Id == loose!.Id);

        var all = await authed.Client.GetFromJsonAsync<List<NoteResponse>>("/api/v1/notes");
        all!.Select(n => n.Id).Should().Contain(new[] { inGroup!.Id, inProject!.Id, loose!.Id });
    }

    [Fact]
    public async Task Cross_user_cannot_see_get_or_mutate_anothers_note()
    {
        var alice = await RegisterAsync();
        var bob = await RegisterAsync();

        var created = await (await alice.Client.PostAsJsonAsync("/api/v1/notes",
            new CreateNoteRequest(null, null, "Alice Note", "secret", "a0")))
            .Content.ReadFromJsonAsync<NoteResponse>();

        var bobList = await bob.Client.GetFromJsonAsync<List<NoteResponse>>("/api/v1/notes");
        bobList.Should().NotContain(n => n.Id == created!.Id);

        var bobGet = await bob.Client.GetAsync($"/api/v1/notes/{created!.Id}");
        bobGet.StatusCode.Should().Be(HttpStatusCode.NotFound);

        var bobPatch = await bob.Client.PatchAsJsonAsync($"/api/v1/notes/{created.Id}",
            new UpdateNoteRequest(null, null, "Hijack", "evil", "z9"));
        bobPatch.StatusCode.Should().Be(HttpStatusCode.NotFound);

        var bobDelete = await bob.Client.DeleteAsync($"/api/v1/notes/{created.Id}");
        bobDelete.StatusCode.Should().Be(HttpStatusCode.NotFound);
    }
```

- [ ] Run, expect FAIL: `dotnet test --filter "FullyQualifiedName~NotesTests.GetAll_filters_by_groupId_and_projectId|FullyQualifiedName~NotesTests.Cross_user_cannot_see_get_or_mutate_anothers_note"` — `GetAll_filters_by_groupId_and_projectId` PASSES; `Cross_user...` FAILS on the delete assertion (DELETE route unmapped): `Expected bobDelete.StatusCode to be HttpStatusCode.NotFound {value: 404}, but found HttpStatusCode.MethodNotAllowed {value: 405}.` (Resolve DELETE in P4-24.)

### Task P4-24: Note DELETE — soft-delete (no children to cascade)

- [ ] Add the delete route to `MapNotes`:

```csharp
        group.MapDelete("/{id:guid}", Delete);
```

  and the handler (a note has no synced children, so plain soft-delete):

```csharp
    private static async Task<Results<NoContent, NotFound>> Delete(
        Guid id,
        AppDbContext db,
        CancellationToken ct)
    {
        var note = await db.Notes.FirstOrDefaultAsync(n => n.Id == id, ct);
        if (note is null)
            return TypedResults.NotFound();

        note.DeletedAt = DateTimeOffset.UtcNow;
        await db.SaveChangesAsync(ct);

        return TypedResults.NoContent();
    }
```

- [ ] Run, expect PASS: `dotnet test --filter "FullyQualifiedName~NotesTests.Cross_user_cannot_see_get_or_mutate_anothers_note"`.

### Task P4-25: Note DELETE soft-delete behavior test

- [ ] Add the soft-delete behavior test to `NotesTests.cs`:

```csharp
    [Fact]
    public async Task Delete_softdeletes_note_and_removes_from_live_reads()
    {
        var authed = await RegisterAsync();

        var note = await (await authed.Client.PostAsJsonAsync("/api/v1/notes",
            new CreateNoteRequest(null, null, "Doomed", "bye", "a0")))
            .Content.ReadFromJsonAsync<NoteResponse>();
        var noteId = note!.Id;

        var deleteResp = await authed.Client.DeleteAsync($"/api/v1/notes/{noteId}");
        deleteResp.StatusCode.Should().Be(HttpStatusCode.NoContent);

        // Gone from live list and get-by-id.
        var list = await authed.Client.GetFromJsonAsync<List<NoteResponse>>("/api/v1/notes");
        list.Should().NotContain(n => n.Id == noteId);
        var getResp = await authed.Client.GetAsync($"/api/v1/notes/{noteId}");
        getResp.StatusCode.Should().Be(HttpStatusCode.NotFound);

        // Tombstoned, not hard-deleted.
        await using var assertDb = NewElevatedDbContext();
        var tombstoned = await assertDb.Notes
            .IgnoreQueryFilters(["SoftDelete"])
            .SingleAsync(n => n.Id == noteId);
        tombstoned.DeletedAt.Should().NotBeNull();
    }
```

- [ ] Run, expect PASS: `dotnet test --filter "FullyQualifiedName~NotesTests.Delete_softdeletes_note_and_removes_from_live_reads"`.
- [ ] Commit:

```
git add -A && git commit -m "$(cat <<'EOF'
feat(api): note delete soft-deletes (tombstone)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task P4-26: Note reorder — test first

- [ ] Add the reorder test to `NotesTests.cs`:

```csharp
    [Fact]
    public async Task Reorder_updates_only_specified_rows_ranks()
    {
        var authed = await RegisterAsync();

        var a = await (await authed.Client.PostAsJsonAsync("/api/v1/notes",
            new CreateNoteRequest(null, null, "A", "x", "a0")))
            .Content.ReadFromJsonAsync<NoteResponse>();
        var b = await (await authed.Client.PostAsJsonAsync("/api/v1/notes",
            new CreateNoteRequest(null, null, "B", "y", "a1")))
            .Content.ReadFromJsonAsync<NoteResponse>();

        var reorderResp = await authed.Client.PatchAsJsonAsync(
            "/api/v1/notes/reorder",
            new[] { new ReorderItem(b!.Id, "Zz") });
        reorderResp.StatusCode.Should().Be(HttpStatusCode.NoContent);

        var list = await authed.Client.GetFromJsonAsync<List<NoteResponse>>("/api/v1/notes");
        list!.Single(n => n.Id == b.Id).Rank.Should().Be("Zz");
        list.Single(n => n.Id == a!.Id).Rank.Should().Be("a0");
        list.Select(n => n.Rank).Should().BeInAscendingOrder();
    }
```

- [ ] Run, expect FAIL: `dotnet test --filter "FullyQualifiedName~NotesTests.Reorder_updates_only_specified_rows_ranks"` — `/reorder` unmapped; expect FAIL `Expected reorderResp.StatusCode to be HttpStatusCode.NoContent {value: 204}, but found HttpStatusCode.NotFound {value: 404}.`

### Task P4-27: Note reorder endpoint + handler

- [ ] Add the reorder route to `MapNotes` **before** the `/{id:guid}` routes:

```csharp
        group.MapPatch("/reorder", Reorder)
            .AddEndpointFilter<ValidationFilter<IReadOnlyList<ReorderItem>>>();
```

  and the handler:

```csharp
    private static async Task<NoContent> Reorder(
        IReadOnlyList<ReorderItem> items,
        AppDbContext db,
        CancellationToken ct)
    {
        foreach (var item in items)
        {
            await db.Notes
                .Where(n => n.Id == item.Id)
                .ExecuteUpdateAsync(s => s.SetProperty(n => n.Rank, item.Rank), ct);
        }

        return TypedResults.NoContent();
    }
```

- [ ] Run, expect PASS: `dotnet test --filter "FullyQualifiedName~NotesTests.Reorder_updates_only_specified_rows_ranks"`.
- [ ] Run all three slice classes, expect PASS: `dotnet test --filter "FullyQualifiedName~ProjectsTests|FullyQualifiedName~NoteGroupsTests|FullyQualifiedName~NotesTests"`.
- [ ] Build to confirm no warnings introduced: `dotnet build`.
- [ ] Commit:

```
git add -A && git commit -m "$(cat <<'EOF'
feat(api): note reorder by rank (single-row update)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Files referenced (absolute paths)

Created/modified by this phase under `C:/Users/aboab/Desktop/Projects/sunsama clone/`:
- `backend/src/Tmap.Api/Features/Projects/{ProjectEndpoints,ProjectDtos,ProjectValidators}.cs`
- `backend/src/Tmap.Api/Features/NoteGroups/{NoteGroupEndpoints,NoteGroupDtos,NoteGroupValidators}.cs`
- `backend/src/Tmap.Api/Features/Notes/{NoteEndpoints,NoteDtos,NoteValidators}.cs`
- `backend/tests/Tmap.Api.Tests/{ProjectsTests,NoteGroupsTests,NotesTests}.cs`
- `backend/src/Tmap.Api/Program.cs` (DI registrations + `app.MapProjects()/MapNoteGroups()/MapNotes()`)

Depends on (defined in P0/P1/P3, USED not redefined here): `AppDbContext`, entities `Project`/`NoteGroup`/`Note`/`TaskItem`, the `"Tenant"`/`"SoftDelete"` named query filters, `ICurrentUser`, `ValidationFilter<T>`, the SaveChanges ownership interceptor, the `change_seq`/`updated_at` trigger, and the test harness (`PostgresFixture`, `IntegrationTestBase`, `RegisterAsync`, `NewElevatedDbContext`).

---

## Phase P5 — Recurrence, FocusSessions, DailyPlans, Settings, Reports slices

This phase implements five vertical slices under `backend/src/Tmap.Api/Features/` plus their tests, building on the shared contract (entities, `SyncEntity`, `ICurrentUser`, `ValidationFilter<T>`, named query filters, RLS, the `change_seq` trigger, the test harness from P0, and the validation/ProblemDetails wiring from P3). Files this phase creates:
- `Features/Recurrence/RecurrenceEndpoints.cs`, `RecurrenceDtos.cs`, `RecurrenceValidators.cs`, `OccurrenceGenerator.cs`
- `Features/FocusSessions/FocusSessionsEndpoints.cs`, `FocusSessionsDtos.cs`, `FocusSessionsValidator.cs`
- `Features/DailyPlans/DailyPlansEndpoints.cs`, `DailyPlansDtos.cs`, `DailyPlansValidator.cs`
- `Features/Settings/SettingsEndpoints.cs`, `SettingsDtos.cs`, `SettingsValidator.cs`, `SyncedSettingKeys.cs`
- `Features/Reports/ReportsEndpoints.cs`, `ReportsDtos.cs`
- Tests: `tests/Tmap.Api.Tests/RecurrenceTests.cs`, `OccurrenceGeneratorTests.cs`, `FocusSessionsTests.cs`, `DailyPlansTests.cs`, `SettingsTests.cs`, `ReportsTests.cs`
Files this phase modifies:
- `Program.cs` (register the five `Map<Slice>Endpoints` groups + the validators in DI)

**Contract gaps noted (assumptions, NOT new shared symbols):** (1) `Program.cs` is assumed to expose extension methods `Map{Recurrence,FocusSessions,DailyPlans,Settings,Reports}Endpoints(this IEndpointRouteBuilder app)` registered alongside the other slices; if the host instead inlines `MapGroup` calls, move the group setup there verbatim. (2) `ApplicationUser.TimeZoneId` (IANA, default `"UTC"`) is read via `UserManager<ApplicationUser>` / a direct `AspNetUsers` query inside the Reports slice — no new shared type. (3) The advisory-lock helper `pg_advisory_xact_lock(key bigint)` is called via `DbContext.Database.ExecuteSqlInterpolatedAsync` inside an explicit transaction — no shared wrapper introduced. (4) `OccurrenceGenerator` is a new **slice-local** static class (not shared) living under `Features/Recurrence/`.

---

### Task P5-1: OccurrenceGenerator — daily/weekly date math (pure, no DB)

The recurrence date-generation algorithm is pure and the riskiest logic in this phase, so it gets its own unit test class with zero DB dependency. It mirrors `electron/recurrenceUtils.ts` semantics exactly: daily steps by `interval` days from the template start; weekly walks week-by-week (Sunday-anchored) emitting the selected `DaysOfWeek`, advancing `7 * interval` days each week; `EndType.Count` caps the **total** generated occurrences from the template start (not just those in range); `EndType.Date` stops after `EndDate` inclusive; existing dates and exception dates are skipped in the returned set.

- [ ] **Write failing test.** Create `backend/tests/Tmap.Api.Tests/OccurrenceGeneratorTests.cs`:
```csharp
using FluentAssertions;
using Tmap.Api.Features.Recurrence;
using Tmap.Api.Infrastructure.Entities;
using Xunit;

namespace Tmap.Api.Tests;

public class OccurrenceGeneratorTests
{
    private static OccurrenceRuleData Weekly(int interval, IEnumerable<int> days,
        RecurrenceEndType endType = RecurrenceEndType.Never, int? endCount = null, DateOnly? endDate = null) =>
        new(RecurrenceFrequency.Weekly, interval, days.ToList(), endType, endCount, endDate);

    private static OccurrenceRuleData Daily(int interval,
        RecurrenceEndType endType = RecurrenceEndType.Never, int? endCount = null, DateOnly? endDate = null) =>
        new(RecurrenceFrequency.Daily, interval, new List<int>(), endType, endCount, endDate);

    private static DateOnly D(string s) => DateOnly.Parse(s);

    [Fact]
    public void Weekly_MonWedFri_GeneratesExpectedDatesInRange()
    {
        // Template starts Mon 2026-06-01 (a Monday). Mon=1, Wed=3, Fri=5.
        var rule = Weekly(1, new[] { 1, 3, 5 });
        var result = OccurrenceGenerator.Generate(
            rule, templateStart: D("2026-06-01"),
            rangeStart: D("2026-06-01"), rangeEnd: D("2026-06-07"),
            existingDates: new HashSet<DateOnly>(), exceptionDates: new HashSet<DateOnly>());

        result.Should().Equal(D("2026-06-01"), D("2026-06-03"), D("2026-06-05"));
    }

    [Fact]
    public void Weekly_EveryTwoWeeks_SkipsTheOffWeek()
    {
        var rule = Weekly(2, new[] { 1 }); // every other Monday
        var result = OccurrenceGenerator.Generate(
            rule, D("2026-06-01"), D("2026-06-01"), D("2026-06-30"),
            new HashSet<DateOnly>(), new HashSet<DateOnly>());

        result.Should().Equal(D("2026-06-01"), D("2026-06-15"), D("2026-06-29"));
    }

    [Fact]
    public void Daily_EveryThreeDays_RespectsInterval()
    {
        var rule = Daily(3);
        var result = OccurrenceGenerator.Generate(
            rule, D("2026-06-01"), D("2026-06-01"), D("2026-06-10"),
            new HashSet<DateOnly>(), new HashSet<DateOnly>());

        result.Should().Equal(D("2026-06-01"), D("2026-06-04"), D("2026-06-07"), D("2026-06-10"));
    }

    [Fact]
    public void Count_CapsTotalOccurrencesFromTemplateStart()
    {
        // 4 total daily occurrences; range starts after the first one.
        var rule = Daily(1, RecurrenceEndType.Count, endCount: 4);
        var result = OccurrenceGenerator.Generate(
            rule, templateStart: D("2026-06-01"),
            rangeStart: D("2026-06-02"), rangeEnd: D("2026-06-30"),
            new HashSet<DateOnly>(), new HashSet<DateOnly>());

        // Occurrences are 06-01..06-04; only 06-02..06-04 are in range.
        result.Should().Equal(D("2026-06-02"), D("2026-06-03"), D("2026-06-04"));
    }

    [Fact]
    public void EndDate_IsInclusive()
    {
        var rule = Daily(1, RecurrenceEndType.Date, endDate: D("2026-06-03"));
        var result = OccurrenceGenerator.Generate(
            rule, D("2026-06-01"), D("2026-06-01"), D("2026-06-30"),
            new HashSet<DateOnly>(), new HashSet<DateOnly>());

        result.Should().Equal(D("2026-06-01"), D("2026-06-02"), D("2026-06-03"));
    }

    [Fact]
    public void ExistingAndExceptionDates_AreExcludedButStillCountTowardCount()
    {
        var rule = Daily(1, RecurrenceEndType.Count, endCount: 4);
        var result = OccurrenceGenerator.Generate(
            rule, D("2026-06-01"), D("2026-06-01"), D("2026-06-30"),
            existingDates: new HashSet<DateOnly> { D("2026-06-02") },
            exceptionDates: new HashSet<DateOnly> { D("2026-06-03") });

        // 4 occurrences 06-01..06-04; 06-02 already exists, 06-03 is an exception.
        result.Should().Equal(D("2026-06-01"), D("2026-06-04"));
    }

    [Fact]
    public void Weekly_WithNoDaysOfWeek_GeneratesNothing()
    {
        var rule = Weekly(1, Array.Empty<int>());
        var result = OccurrenceGenerator.Generate(
            rule, D("2026-06-01"), D("2026-06-01"), D("2026-06-30"),
            new HashSet<DateOnly>(), new HashSet<DateOnly>());

        result.Should().BeEmpty();
    }
}
```
- [ ] **Run, expect FAIL (compile error).** `dotnet test --filter "FullyQualifiedName~OccurrenceGeneratorTests"` — expect failure: `error CS0246: The type or namespace name 'OccurrenceGenerator' could not be found` (and `OccurrenceRuleData`).
- [ ] **Minimal impl (full code).** Create `backend/src/Tmap.Api/Features/Recurrence/OccurrenceGenerator.cs`:
```csharp
using Tmap.Api.Infrastructure.Entities;

namespace Tmap.Api.Features.Recurrence;

/// <summary>Immutable rule snapshot used by the pure occurrence generator.</summary>
public sealed record OccurrenceRuleData(
    RecurrenceFrequency Frequency,
    int Interval,
    IReadOnlyList<int> DaysOfWeek,
    RecurrenceEndType EndType,
    int? EndCount,
    DateOnly? EndDate);

/// <summary>
/// Pure, side-effect-free occurrence date generator mirroring the legacy
/// electron/recurrenceUtils.ts algorithm. Caller supplies the template's
/// original start date and the [rangeStart, rangeEnd] window (both inclusive).
/// existingDates/exceptionDates are removed from the OUTPUT but still consume
/// the EndType.Count budget.
/// </summary>
public static class OccurrenceGenerator
{
    private const int MaxWeeks = 520; // ~10 years safety cap

    public static List<DateOnly> Generate(
        OccurrenceRuleData rule,
        DateOnly templateStart,
        DateOnly rangeStart,
        DateOnly rangeEnd,
        ISet<DateOnly> existingDates,
        ISet<DateOnly> exceptionDates)
    {
        var results = new List<DateOnly>();
        var interval = rule.Interval < 1 ? 1 : rule.Interval;
        var maxCount = rule.EndType == RecurrenceEndType.Count && rule.EndCount is { } c ? c : int.MaxValue;
        var endDate = rule.EndType == RecurrenceEndType.Date ? rule.EndDate : null;
        var totalCount = 0;

        void Emit(DateOnly date)
        {
            totalCount++;
            if (date < rangeStart) return;
            if (existingDates.Contains(date) || exceptionDates.Contains(date)) return;
            results.Add(date);
        }

        bool Stop(DateOnly date) =>
            date > rangeEnd || (endDate is { } ed && date > ed) || totalCount >= maxCount;

        if (rule.Frequency == RecurrenceFrequency.Daily)
        {
            var current = templateStart;
            while (!Stop(current))
            {
                Emit(current);
                current = current.AddDays(interval);
            }
        }
        else // Weekly
        {
            var days = new HashSet<int>(rule.DaysOfWeek);
            if (days.Count == 0) return results;

            // Sunday-anchored week containing templateStart. DayOfWeek.Sunday == 0.
            var weekStart = templateStart.AddDays(-(int)templateStart.DayOfWeek);

            for (var week = 0; week <= MaxWeeks; week++)
            {
                for (var dow = 0; dow < 7; dow++)
                {
                    if (!days.Contains(dow)) continue;
                    var date = weekStart.AddDays(dow);
                    if (date < templateStart) continue;
                    if (Stop(date)) return results;
                    Emit(date);
                }
                weekStart = weekStart.AddDays(7 * interval);
                // Early exit once we've walked entirely past the window/end.
                if (weekStart > rangeEnd || (endDate is { } ed && weekStart > ed)) break;
            }
        }

        return results;
    }
}
```
- [ ] **Run, expect PASS.** `dotnet test --filter "FullyQualifiedName~OccurrenceGeneratorTests"`.
- [ ] **Commit.**
```
test(api): add OccurrenceGenerator unit tests and pure date algorithm

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P5-2: FocusSessions slice — append-only POST

`focusSessions.add` maps to `POST /api/v1/focus-sessions` (append-only; `Project` is a name snapshot, never an FK). The handler inserts a new `FocusSession` (`SyncEntity`); `UserId`/`ChangeSeq`/`CreatedAt`/`UpdatedAt` are stamped server-side (ownership interceptor + trigger), so the request never supplies them.

- [ ] **Write failing test.** Create `backend/tests/Tmap.Api.Tests/FocusSessionsTests.cs`:
```csharp
using System.Net;
using System.Net.Http.Json;
using FluentAssertions;
using Microsoft.EntityFrameworkCore;
using Tmap.Api.Features.FocusSessions;
using Tmap.Api.Infrastructure.Entities;
using Xunit;

namespace Tmap.Api.Tests;

[Collection("db")]
public class FocusSessionsTests(PostgresFixture fixture) : IntegrationTestBase(fixture)
{
    [Fact]
    public async Task Add_PersistsAppendOnlySession_ScopedToUser()
    {
        var user = await RegisterAsync();
        var body = new CreateFocusSessionRequest(
            TaskId: null,
            Project: "Deep Work",
            StartedAt: DateTimeOffset.Parse("2026-06-01T09:00:00Z"),
            EndedAt: DateTimeOffset.Parse("2026-06-01T09:25:00Z"),
            Minutes: 25,
            Date: new DateOnly(2026, 6, 1));

        var resp = await user.Client.PostAsJsonAsync("/api/v1/focus-sessions", body);
        resp.StatusCode.Should().Be(HttpStatusCode.Created);

        var dto = await resp.Content.ReadFromJsonAsync<FocusSessionResponse>();
        dto!.Project.Should().Be("Deep Work");
        dto.Minutes.Should().Be(25);
        dto.Id.Should().NotBe(Guid.Empty);

        await using var db = NewElevatedDbContext();
        var row = await db.Set<FocusSession>().SingleAsync(f => f.Id == dto.Id);
        row.UserId.Should().Be(user.UserId);
        row.ChangeSeq.Should().BeGreaterThan(0);
    }

    [Fact]
    public async Task Add_RequiresAuthentication()
    {
        var body = new CreateFocusSessionRequest(null, "x",
            DateTimeOffset.UtcNow, DateTimeOffset.UtcNow, 5, new DateOnly(2026, 6, 1));
        var resp = await Client.PostAsJsonAsync("/api/v1/focus-sessions", body);
        resp.StatusCode.Should().Be(HttpStatusCode.Unauthorized);
    }

    [Fact]
    public async Task Add_RejectsNonPositiveMinutes()
    {
        var user = await RegisterAsync();
        var body = new CreateFocusSessionRequest(null, "x",
            DateTimeOffset.UtcNow, DateTimeOffset.UtcNow, 0, new DateOnly(2026, 6, 1));
        var resp = await user.Client.PostAsJsonAsync("/api/v1/focus-sessions", body);
        resp.StatusCode.Should().Be(HttpStatusCode.BadRequest);
    }
}
```
- [ ] **Run, expect FAIL (compile error).** `dotnet test --filter "FullyQualifiedName~FocusSessionsTests"` — expect `CS0246: ... 'CreateFocusSessionRequest' / 'FocusSessionResponse'`.
- [ ] **Minimal impl — DTOs (full code).** Create `backend/src/Tmap.Api/Features/FocusSessions/FocusSessionsDtos.cs`:
```csharp
namespace Tmap.Api.Features.FocusSessions;

public sealed record CreateFocusSessionRequest(
    Guid? TaskId,
    string Project,
    DateTimeOffset StartedAt,
    DateTimeOffset EndedAt,
    int Minutes,
    DateOnly Date);

public sealed record FocusSessionResponse(
    Guid Id,
    Guid? TaskId,
    string Project,
    DateTimeOffset StartedAt,
    DateTimeOffset EndedAt,
    int Minutes,
    DateOnly Date,
    DateTimeOffset CreatedAt,
    DateTimeOffset UpdatedAt);
```
- [ ] **Minimal impl — validator (full code).** Create `backend/src/Tmap.Api/Features/FocusSessions/FocusSessionsValidator.cs`:
```csharp
using FluentValidation;

namespace Tmap.Api.Features.FocusSessions;

public sealed class CreateFocusSessionValidator : AbstractValidator<CreateFocusSessionRequest>
{
    public CreateFocusSessionValidator()
    {
        RuleFor(x => x.Project).NotNull().MaximumLength(200);
        RuleFor(x => x.Minutes).GreaterThan(0);
        RuleFor(x => x.EndedAt).GreaterThanOrEqualTo(x => x.StartedAt);
    }
}
```
- [ ] **Minimal impl — endpoints (full code).** Create `backend/src/Tmap.Api/Features/FocusSessions/FocusSessionsEndpoints.cs`:
```csharp
using Microsoft.AspNetCore.Http.HttpResults;
using Tmap.Api.Common;
using Tmap.Api.Common.Validation;
using Tmap.Api.Infrastructure;
using Tmap.Api.Infrastructure.Entities;

namespace Tmap.Api.Features.FocusSessions;

public static class FocusSessionsEndpoints
{
    public static IEndpointRouteBuilder MapFocusSessionsEndpoints(this IEndpointRouteBuilder app)
    {
        var group = app.MapGroup("/api/v1/focus-sessions").RequireAuthorization();

        group.MapPost("/", Add)
            .AddEndpointFilter<ValidationFilter<CreateFocusSessionRequest>>()
            .WithName("AddFocusSession");

        return app;
    }

    private static async Task<Created<FocusSessionResponse>> Add(
        CreateFocusSessionRequest req,
        AppDbContext db,
        ICurrentUser currentUser,
        CancellationToken ct)
    {
        var entity = new FocusSession
        {
            Id = Guid.CreateVersion7(),
            UserId = currentUser.Id, // interceptor also stamps; explicit for clarity
            TaskId = req.TaskId,
            Project = req.Project,
            StartedAt = req.StartedAt,
            EndedAt = req.EndedAt,
            Minutes = req.Minutes,
            Date = req.Date,
        };
        db.Add(entity);
        await db.SaveChangesAsync(ct);

        var dto = ToResponse(entity);
        return TypedResults.Created($"/api/v1/focus-sessions/{entity.Id}", dto);
    }

    private static FocusSessionResponse ToResponse(FocusSession e) =>
        new(e.Id, e.TaskId, e.Project, e.StartedAt, e.EndedAt, e.Minutes, e.Date, e.CreatedAt, e.UpdatedAt);
}
```
- [ ] **Wire into Program.cs.** In `backend/src/Tmap.Api/Program.cs`, register the validator and map the group (placed with the other slice registrations):
```csharp
// DI registration (with the other AddScoped<IValidator<...>> lines):
builder.Services.AddScoped<IValidator<CreateFocusSessionRequest>, CreateFocusSessionValidator>();

// Endpoint mapping (with the other app.Map*Endpoints() calls, after auth middleware):
app.MapFocusSessionsEndpoints();
```
(add `using FluentValidation;`, `using Tmap.Api.Features.FocusSessions;` at the top of `Program.cs` if not already present.)
- [ ] **Run, expect PASS.** `dotnet test --filter "FullyQualifiedName~FocusSessionsTests"`.
- [ ] **Commit.**
```
feat(api): add append-only focus-sessions slice

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P5-3: DailyPlans slice — GET + PUT upsert (composite key, whole-day LWW)

`dailyPlans.get`/`upsert` map to `GET /api/v1/daily-plans/{date}` and `PUT /api/v1/daily-plans/{date}`. `DailyPlan` has composite PK `(UserId, Date)` and **no `Id`**; `PlannedTaskIds` is a `jsonb` array with whole-day last-writer-wins. PUT upserts: if a row exists for `(currentUser, date)` it is updated in place, otherwise inserted. `CommittedAt` is set server-side to "now" on each upsert (the day's commit timestamp).

- [ ] **Write failing test.** Create `backend/tests/Tmap.Api.Tests/DailyPlansTests.cs`:
```csharp
using System.Net;
using System.Net.Http.Json;
using FluentAssertions;
using Tmap.Api.Features.DailyPlans;
using Xunit;

namespace Tmap.Api.Tests;

[Collection("db")]
public class DailyPlansTests(PostgresFixture fixture) : IntegrationTestBase(fixture)
{
    [Fact]
    public async Task Get_ReturnsNotFound_WhenNoPlanForDate()
    {
        var user = await RegisterAsync();
        var resp = await user.Client.GetAsync("/api/v1/daily-plans/2026-06-01");
        resp.StatusCode.Should().Be(HttpStatusCode.NotFound);
    }

    [Fact]
    public async Task Put_InsertsThenUpdates_WholeDayLww()
    {
        var user = await RegisterAsync();
        var id1 = Guid.CreateVersion7();
        var id2 = Guid.CreateVersion7();

        // First upsert (insert)
        var put1 = await user.Client.PutAsJsonAsync("/api/v1/daily-plans/2026-06-01",
            new UpsertDailyPlanRequest(new List<Guid> { id1 }, 60));
        put1.StatusCode.Should().Be(HttpStatusCode.OK);
        var dto1 = await put1.Content.ReadFromJsonAsync<DailyPlanResponse>();
        dto1!.PlannedTaskIds.Should().Equal(id1);
        dto1.PlannedMinutes.Should().Be(60);
        dto1.Date.Should().Be(new DateOnly(2026, 6, 1));

        // Second upsert (update / whole-day overwrite)
        var put2 = await user.Client.PutAsJsonAsync("/api/v1/daily-plans/2026-06-01",
            new UpsertDailyPlanRequest(new List<Guid> { id2 }, 90));
        put2.StatusCode.Should().Be(HttpStatusCode.OK);

        var get = await user.Client.GetFromJsonAsync<DailyPlanResponse>("/api/v1/daily-plans/2026-06-01");
        get!.PlannedTaskIds.Should().Equal(id2); // whole-day replaced, not merged
        get.PlannedMinutes.Should().Be(90);
    }

    [Fact]
    public async Task Put_IsScopedPerUser()
    {
        var a = await RegisterAsync();
        var b = await RegisterAsync();
        var idA = Guid.CreateVersion7();

        await a.Client.PutAsJsonAsync("/api/v1/daily-plans/2026-06-02",
            new UpsertDailyPlanRequest(new List<Guid> { idA }, 30));

        // Same date, different user -> independent row, B sees none.
        var bGet = await b.Client.GetAsync("/api/v1/daily-plans/2026-06-02");
        bGet.StatusCode.Should().Be(HttpStatusCode.NotFound);

        var aGet = await a.Client.GetFromJsonAsync<DailyPlanResponse>("/api/v1/daily-plans/2026-06-02");
        aGet!.PlannedTaskIds.Should().Equal(idA);
    }

    [Fact]
    public async Task Put_RejectsNegativeMinutes()
    {
        var user = await RegisterAsync();
        var resp = await user.Client.PutAsJsonAsync("/api/v1/daily-plans/2026-06-01",
            new UpsertDailyPlanRequest(new List<Guid>(), -1));
        resp.StatusCode.Should().Be(HttpStatusCode.BadRequest);
    }

    [Fact]
    public async Task Get_RequiresAuthentication()
    {
        var resp = await Client.GetAsync("/api/v1/daily-plans/2026-06-01");
        resp.StatusCode.Should().Be(HttpStatusCode.Unauthorized);
    }
}
```
- [ ] **Run, expect FAIL (compile error).** `dotnet test --filter "FullyQualifiedName~DailyPlansTests"` — expect `CS0246: ... 'UpsertDailyPlanRequest' / 'DailyPlanResponse'`.
- [ ] **Minimal impl — DTOs (full code).** Create `backend/src/Tmap.Api/Features/DailyPlans/DailyPlansDtos.cs`:
```csharp
namespace Tmap.Api.Features.DailyPlans;

public sealed record UpsertDailyPlanRequest(
    List<Guid> PlannedTaskIds,
    int PlannedMinutes);

public sealed record DailyPlanResponse(
    DateOnly Date,
    DateTimeOffset CommittedAt,
    List<Guid> PlannedTaskIds,
    int PlannedMinutes,
    DateTimeOffset CreatedAt,
    DateTimeOffset UpdatedAt);
```
- [ ] **Minimal impl — validator (full code).** Create `backend/src/Tmap.Api/Features/DailyPlans/DailyPlansValidator.cs`:
```csharp
using FluentValidation;

namespace Tmap.Api.Features.DailyPlans;

public sealed class UpsertDailyPlanValidator : AbstractValidator<UpsertDailyPlanRequest>
{
    public UpsertDailyPlanValidator()
    {
        RuleFor(x => x.PlannedTaskIds).NotNull();
        RuleFor(x => x.PlannedMinutes).GreaterThanOrEqualTo(0);
    }
}
```
- [ ] **Minimal impl — endpoints (full code).** Create `backend/src/Tmap.Api/Features/DailyPlans/DailyPlansEndpoints.cs`:
```csharp
using Microsoft.AspNetCore.Http.HttpResults;
using Microsoft.EntityFrameworkCore;
using Tmap.Api.Common;
using Tmap.Api.Common.Validation;
using Tmap.Api.Infrastructure;
using Tmap.Api.Infrastructure.Entities;

namespace Tmap.Api.Features.DailyPlans;

public static class DailyPlansEndpoints
{
    public static IEndpointRouteBuilder MapDailyPlansEndpoints(this IEndpointRouteBuilder app)
    {
        var group = app.MapGroup("/api/v1/daily-plans").RequireAuthorization();

        group.MapGet("/{date}", Get).WithName("GetDailyPlan");
        group.MapPut("/{date}", Upsert)
            .AddEndpointFilter<ValidationFilter<UpsertDailyPlanRequest>>()
            .WithName("UpsertDailyPlan");

        return app;
    }

    private static async Task<Results<Ok<DailyPlanResponse>, NotFound>> Get(
        DateOnly date,
        AppDbContext db,
        ICurrentUser currentUser,
        CancellationToken ct)
    {
        // Tenant + soft-delete filters apply automatically.
        var plan = await db.Set<DailyPlan>()
            .AsNoTracking()
            .FirstOrDefaultAsync(p => p.Date == date, ct);

        return plan is null ? TypedResults.NotFound() : TypedResults.Ok(ToResponse(plan));
    }

    private static async Task<Ok<DailyPlanResponse>> Upsert(
        DateOnly date,
        UpsertDailyPlanRequest req,
        AppDbContext db,
        ICurrentUser currentUser,
        CancellationToken ct)
    {
        var userId = currentUser.Id;
        var plan = await db.Set<DailyPlan>()
            .FirstOrDefaultAsync(p => p.Date == date, ct);

        if (plan is null)
        {
            plan = new DailyPlan
            {
                UserId = userId,
                Date = date,
                CommittedAt = DateTimeOffset.UtcNow,
                PlannedTaskIds = req.PlannedTaskIds,
                PlannedMinutes = req.PlannedMinutes,
            };
            db.Add(plan);
        }
        else
        {
            // whole-day last-writer-wins: replace the array, do not merge.
            plan.CommittedAt = DateTimeOffset.UtcNow;
            plan.PlannedTaskIds = req.PlannedTaskIds;
            plan.PlannedMinutes = req.PlannedMinutes;
        }

        await db.SaveChangesAsync(ct);
        return TypedResults.Ok(ToResponse(plan));
    }

    private static DailyPlanResponse ToResponse(DailyPlan p) =>
        new(p.Date, p.CommittedAt, p.PlannedTaskIds, p.PlannedMinutes, p.CreatedAt, p.UpdatedAt);
}
```
- [ ] **Wire into Program.cs.**
```csharp
builder.Services.AddScoped<IValidator<UpsertDailyPlanRequest>, UpsertDailyPlanValidator>();
app.MapDailyPlansEndpoints();
```
(add `using Tmap.Api.Features.DailyPlans;`.)
- [ ] **Run, expect PASS.** `dotnet test --filter "FullyQualifiedName~DailyPlansTests"`.
- [ ] **Commit.**
```
feat(api): add daily-plans slice with composite-key whole-day upsert

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P5-4: Settings slice — GET + PUT with server-side whitelist + TimeZoneId

`settings.get`/`save` map to `GET /api/v1/settings` and `PUT /api/v1/settings`. Synced settings are key/value `UserSetting` rows (composite PK `(UserId, Key)`); the server **whitelists** `workStartHour`, `workEndHour`, `timeIncrement` — any other key in the request is **silently ignored** (never persisted). GET returns the whitelisted key/value pairs **plus** the user's `TimeZoneId` (read from `AspNetUsers`). PUT upserts only whitelisted keys; `Value` is stored as jsonb text exactly as supplied (legacy stores stringified values).

- [ ] **Write failing test.** Create `backend/tests/Tmap.Api.Tests/SettingsTests.cs`:
```csharp
using System.Net;
using System.Net.Http.Json;
using FluentAssertions;
using Microsoft.EntityFrameworkCore;
using Tmap.Api.Features.Settings;
using Tmap.Api.Infrastructure.Entities;
using Xunit;

namespace Tmap.Api.Tests;

[Collection("db")]
public class SettingsTests(PostgresFixture fixture) : IntegrationTestBase(fixture)
{
    [Fact]
    public async Task Get_NewUser_ReturnsEmptySettingsAndDefaultTimeZone()
    {
        var user = await RegisterAsync();
        var dto = await user.Client.GetFromJsonAsync<SettingsResponse>("/api/v1/settings");
        dto!.Settings.Should().BeEmpty();
        dto.TimeZoneId.Should().Be("UTC");
    }

    [Fact]
    public async Task Put_PersistsWhitelistedKeys_AndIgnoresOthers()
    {
        var user = await RegisterAsync();
        var req = new SaveSettingsRequest(new Dictionary<string, string>
        {
            ["workStartHour"] = "8",
            ["workEndHour"] = "17",
            ["timeIncrement"] = "15",
            ["sidebarCollapsed"] = "true", // NOT whitelisted -> must be ignored
        });

        var put = await user.Client.PutAsJsonAsync("/api/v1/settings", req);
        put.StatusCode.Should().Be(HttpStatusCode.OK);

        var dto = await user.Client.GetFromJsonAsync<SettingsResponse>("/api/v1/settings");
        dto!.Settings.Should().ContainKey("workStartHour").WhoseValue.Should().Be("8");
        dto.Settings.Should().ContainKey("workEndHour").WhoseValue.Should().Be("17");
        dto.Settings.Should().ContainKey("timeIncrement").WhoseValue.Should().Be("15");
        dto.Settings.Should().NotContainKey("sidebarCollapsed");

        await using var db = NewElevatedDbContext();
        var keys = await db.Set<UserSetting>()
            .Where(s => s.UserId == user.UserId)
            .Select(s => s.Key).ToListAsync();
        keys.Should().BeEquivalentTo(new[] { "workStartHour", "workEndHour", "timeIncrement" });
    }

    [Fact]
    public async Task Put_UpdatesExistingKeyInPlace()
    {
        var user = await RegisterAsync();
        await user.Client.PutAsJsonAsync("/api/v1/settings",
            new SaveSettingsRequest(new Dictionary<string, string> { ["timeIncrement"] = "30" }));
        await user.Client.PutAsJsonAsync("/api/v1/settings",
            new SaveSettingsRequest(new Dictionary<string, string> { ["timeIncrement"] = "5" }));

        var dto = await user.Client.GetFromJsonAsync<SettingsResponse>("/api/v1/settings");
        dto!.Settings["timeIncrement"].Should().Be("5");

        await using var db = NewElevatedDbContext();
        var count = await db.Set<UserSetting>()
            .CountAsync(s => s.UserId == user.UserId && s.Key == "timeIncrement");
        count.Should().Be(1); // upsert, not duplicate
    }

    [Fact]
    public async Task SettingsAreScopedPerUser()
    {
        var a = await RegisterAsync();
        var b = await RegisterAsync();
        await a.Client.PutAsJsonAsync("/api/v1/settings",
            new SaveSettingsRequest(new Dictionary<string, string> { ["workStartHour"] = "6" }));

        var bDto = await b.Client.GetFromJsonAsync<SettingsResponse>("/api/v1/settings");
        bDto!.Settings.Should().BeEmpty();
    }

    [Fact]
    public async Task Get_RequiresAuthentication()
    {
        var resp = await Client.GetAsync("/api/v1/settings");
        resp.StatusCode.Should().Be(HttpStatusCode.Unauthorized);
    }
}
```
- [ ] **Run, expect FAIL (compile error).** `dotnet test --filter "FullyQualifiedName~SettingsTests"` — expect `CS0246: ... 'SettingsResponse' / 'SaveSettingsRequest'`.
- [ ] **Minimal impl — whitelist (full code).** Create `backend/src/Tmap.Api/Features/Settings/SyncedSettingKeys.cs`:
```csharp
namespace Tmap.Api.Features.Settings;

/// <summary>
/// Server-side allowlist of settings keys that may be synced. Any key not in
/// this set is ignored on write and never returned. Purely-local UI prefs
/// (sidebarCollapsed, notesCollapsed, projectsCollapsed) intentionally stay
/// on-device and are absent here.
/// </summary>
public static class SyncedSettingKeys
{
    public static readonly IReadOnlySet<string> Allowed = new HashSet<string>(StringComparer.Ordinal)
    {
        "workStartHour",
        "workEndHour",
        "timeIncrement",
    };
}
```
- [ ] **Minimal impl — DTOs (full code).** Create `backend/src/Tmap.Api/Features/Settings/SettingsDtos.cs`:
```csharp
namespace Tmap.Api.Features.Settings;

public sealed record SaveSettingsRequest(Dictionary<string, string> Settings);

public sealed record SettingsResponse(
    Dictionary<string, string> Settings,
    string TimeZoneId);
```
- [ ] **Minimal impl — validator (full code).** Create `backend/src/Tmap.Api/Features/Settings/SettingsValidator.cs`:
```csharp
using FluentValidation;

namespace Tmap.Api.Features.Settings;

public sealed class SaveSettingsValidator : AbstractValidator<SaveSettingsRequest>
{
    public SaveSettingsValidator()
    {
        RuleFor(x => x.Settings).NotNull();
        // Non-whitelisted keys are silently ignored by the handler, not rejected
        // (the legacy client may send local-only prefs); we only bound value size.
        RuleForEach(x => x.Settings)
            .Must(kv => kv.Value is null || kv.Value.Length <= 4000)
            .WithMessage("Setting value exceeds maximum length.");
    }
}
```
- [ ] **Minimal impl — endpoints (full code).** Create `backend/src/Tmap.Api/Features/Settings/SettingsEndpoints.cs`:
```csharp
using Microsoft.AspNetCore.Http.HttpResults;
using Microsoft.EntityFrameworkCore;
using Tmap.Api.Common;
using Tmap.Api.Common.Validation;
using Tmap.Api.Infrastructure;
using Tmap.Api.Infrastructure.Entities;

namespace Tmap.Api.Features.Settings;

public static class SettingsEndpoints
{
    public static IEndpointRouteBuilder MapSettingsEndpoints(this IEndpointRouteBuilder app)
    {
        var group = app.MapGroup("/api/v1/settings").RequireAuthorization();

        group.MapGet("/", Get).WithName("GetSettings");
        group.MapPut("/", Save)
            .AddEndpointFilter<ValidationFilter<SaveSettingsRequest>>()
            .WithName("SaveSettings");

        return app;
    }

    private static async Task<Ok<SettingsResponse>> Get(
        AppDbContext db,
        ICurrentUser currentUser,
        CancellationToken ct)
    {
        var userId = currentUser.Id;

        var rows = await db.Set<UserSetting>()
            .AsNoTracking()
            .Where(s => SyncedSettingKeys.Allowed.Contains(s.Key))
            .Select(s => new { s.Key, s.Value })
            .ToListAsync(ct);

        var settings = rows.ToDictionary(r => r.Key, r => r.Value, StringComparer.Ordinal);

        // TimeZoneId lives on ApplicationUser (AspNetUsers); read directly to avoid
        // depending on UserManager here. Default 'UTC' if the column is null.
        var tz = await db.Users
            .Where(u => u.Id == userId)
            .Select(u => u.TimeZoneId)
            .SingleAsync(ct);

        return TypedResults.Ok(new SettingsResponse(settings, string.IsNullOrEmpty(tz) ? "UTC" : tz));
    }

    private static async Task<Ok<SettingsResponse>> Save(
        SaveSettingsRequest req,
        AppDbContext db,
        ICurrentUser currentUser,
        CancellationToken ct)
    {
        var userId = currentUser.Id;

        var incoming = req.Settings
            .Where(kv => SyncedSettingKeys.Allowed.Contains(kv.Key))
            .ToList();

        if (incoming.Count > 0)
        {
            var keys = incoming.Select(kv => kv.Key).ToList();
            var existing = await db.Set<UserSetting>()
                .Where(s => keys.Contains(s.Key))
                .ToDictionaryAsync(s => s.Key, ct);

            foreach (var (key, value) in incoming)
            {
                if (existing.TryGetValue(key, out var row))
                {
                    row.Value = value;
                }
                else
                {
                    db.Add(new UserSetting
                    {
                        UserId = userId,
                        Key = key,
                        Value = value,
                    });
                }
            }

            await db.SaveChangesAsync(ct);
        }

        // Return the full current synced view (mirrors settings.get).
        return await Get(db, currentUser, ct);
    }
}
```
> Note: `db.Users` requires `AppDbContext` to derive from `IdentityDbContext<ApplicationUser, IdentityRole<Guid>, Guid>` (established in P1). If P1 exposes users differently, replace `db.Users` with `db.Set<ApplicationUser>()`.
- [ ] **Wire into Program.cs.**
```csharp
builder.Services.AddScoped<IValidator<SaveSettingsRequest>, SaveSettingsValidator>();
app.MapSettingsEndpoints();
```
(add `using Tmap.Api.Features.Settings;`.)
- [ ] **Run, expect PASS.** `dotnet test --filter "FullyQualifiedName~SettingsTests"`.
- [ ] **Commit.**
```
feat(api): add settings slice with key whitelist and timezone exposure

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P5-5: Reports slice — aggregation with timezone bucketing + soft-delete exclusion

`reports.getData(start,end)` maps to `GET /api/v1/reports?start=&end=`. Returns the `ReportData` projection: `completedTasks[{id, project, date}]`, `sessions[{project, minutes, date}]`, `dailyPlans[]`. `completedTasks[].project` is resolved from `TaskItem.ProjectId → Project.Name` (NULL project → empty string). `sessions[].project` is the `FocusSession.Project` name snapshot. **Date buckets are the user's local day**, computed by converting `CompletedAt` (a `timestamptz`) into the user's `TimeZoneId` and taking the calendar date; `focus_sessions.Date` and `daily_plans.Date` are already `DateOnly` (the client's local day at write time) and are filtered/returned as-is. Soft-deleted rows are excluded (the `SoftDelete` named filter handles this for tasks/sessions/plans; the join to `Project` also respects its filters).

- [ ] **Write failing test.** Create `backend/tests/Tmap.Api.Tests/ReportsTests.cs`:
```csharp
using System.Net;
using System.Net.Http.Json;
using FluentAssertions;
using Microsoft.EntityFrameworkCore;
using Tmap.Api.Features.Reports;
using Tmap.Api.Infrastructure.Entities;
using Xunit;

namespace Tmap.Api.Tests;

[Collection("db")]
public class ReportsTests(PostgresFixture fixture) : IntegrationTestBase(fixture)
{
    [Fact]
    public async Task GetData_AggregatesCompletedTasksSessionsAndPlans_WithProjectNames()
    {
        var user = await RegisterAsync();

        var projectId = Guid.CreateVersion7();
        var doneId = Guid.CreateVersion7();
        var noProjectDoneId = Guid.CreateVersion7();

        await using (var db = NewElevatedDbContext())
        {
            db.Add(new Project { Id = projectId, UserId = user.UserId, Name = "Acme", Color = "#fff", Emoji = "", Rank = "a0", ActualTimeMinutes = 0 });
            // Completed task with a project, completed at 2026-06-01T10:00Z (UTC -> 2026-06-01 local in UTC tz).
            db.Add(new TaskItem
            {
                Id = doneId, UserId = user.UserId, Title = "T1", Notes = "", ProjectId = projectId,
                Labels = new(), Source = "local", Status = TaskStatus.Done, Rank = "a0",
                DurationMinutes = 30, ActualTimeMinutes = 0, CompletedAt = DateTimeOffset.Parse("2026-06-01T10:00:00Z"),
            });
            // Completed task with NO project.
            db.Add(new TaskItem
            {
                Id = noProjectDoneId, UserId = user.UserId, Title = "T2", Notes = "", ProjectId = null,
                Labels = new(), Source = "local", Status = TaskStatus.Done, Rank = "a1",
                DurationMinutes = 30, ActualTimeMinutes = 0, CompletedAt = DateTimeOffset.Parse("2026-06-01T11:00:00Z"),
            });
            db.Add(new FocusSession
            {
                Id = Guid.CreateVersion7(), UserId = user.UserId, TaskId = doneId, Project = "Acme",
                StartedAt = DateTimeOffset.Parse("2026-06-01T09:00:00Z"),
                EndedAt = DateTimeOffset.Parse("2026-06-01T09:25:00Z"), Minutes = 25, Date = new DateOnly(2026, 6, 1),
            });
            db.Add(new DailyPlan
            {
                UserId = user.UserId, Date = new DateOnly(2026, 6, 1), CommittedAt = DateTimeOffset.UtcNow,
                PlannedTaskIds = new List<Guid> { doneId }, PlannedMinutes = 60,
            });
            await db.SaveChangesAsync();
        }

        var dto = await user.Client.GetFromJsonAsync<ReportDataResponse>(
            "/api/v1/reports?start=2026-06-01&end=2026-06-01");

        dto!.CompletedTasks.Should().HaveCount(2);
        dto.CompletedTasks.Should().ContainSingle(t => t.Id == doneId && t.Project == "Acme" && t.Date == new DateOnly(2026, 6, 1));
        dto.CompletedTasks.Should().ContainSingle(t => t.Id == noProjectDoneId && t.Project == "");
        dto.Sessions.Should().ContainSingle(s => s.Project == "Acme" && s.Minutes == 25 && s.Date == new DateOnly(2026, 6, 1));
        dto.DailyPlans.Should().ContainSingle(p => p.Date == new DateOnly(2026, 6, 1) && p.PlannedMinutes == 60);
    }

    [Fact]
    public async Task GetData_ExcludesSoftDeletedRows()
    {
        var user = await RegisterAsync();
        var liveId = Guid.CreateVersion7();
        var deletedId = Guid.CreateVersion7();

        await using (var db = NewElevatedDbContext())
        {
            db.Add(new TaskItem
            {
                Id = liveId, UserId = user.UserId, Title = "live", Notes = "", Labels = new(), Source = "local",
                Status = TaskStatus.Done, Rank = "a0", DurationMinutes = 1, ActualTimeMinutes = 0,
                CompletedAt = DateTimeOffset.Parse("2026-07-01T10:00:00Z"),
            });
            db.Add(new TaskItem
            {
                Id = deletedId, UserId = user.UserId, Title = "deleted", Notes = "", Labels = new(), Source = "local",
                Status = TaskStatus.Done, Rank = "a1", DurationMinutes = 1, ActualTimeMinutes = 0,
                CompletedAt = DateTimeOffset.Parse("2026-07-01T11:00:00Z"),
                DeletedAt = DateTimeOffset.UtcNow,
            });
            await db.SaveChangesAsync();
        }

        var dto = await user.Client.GetFromJsonAsync<ReportDataResponse>(
            "/api/v1/reports?start=2026-07-01&end=2026-07-01");

        dto!.CompletedTasks.Should().ContainSingle(t => t.Id == liveId);
        dto.CompletedTasks.Should().NotContain(t => t.Id == deletedId);
    }

    [Fact]
    public async Task GetData_BucketsCompletedAtInUserTimeZone()
    {
        var user = await RegisterAsync();

        // Set the user's tz to a positive offset so a late-UTC completion rolls into the NEXT local day.
        await using (var db = NewElevatedDbContext())
        {
            var u = await db.Users.SingleAsync(x => x.Id == user.UserId);
            u.TimeZoneId = "Asia/Tokyo"; // UTC+9, no DST
            // Completed 2026-08-10T20:00Z == 2026-08-11T05:00 Tokyo -> local day is 08-11.
            db.Add(new TaskItem
            {
                Id = Guid.CreateVersion7(), UserId = user.UserId, Title = "tz", Notes = "", Labels = new(),
                Source = "local", Status = TaskStatus.Done, Rank = "a0", DurationMinutes = 1, ActualTimeMinutes = 0,
                CompletedAt = DateTimeOffset.Parse("2026-08-10T20:00:00Z"),
            });
            await db.SaveChangesAsync();
        }

        // Query the LOCAL day 08-11; the UTC-day (08-10) must NOT match.
        var hit = await user.Client.GetFromJsonAsync<ReportDataResponse>(
            "/api/v1/reports?start=2026-08-11&end=2026-08-11");
        hit!.CompletedTasks.Should().HaveCount(1);
        hit.CompletedTasks[0].Date.Should().Be(new DateOnly(2026, 8, 11));

        var miss = await user.Client.GetFromJsonAsync<ReportDataResponse>(
            "/api/v1/reports?start=2026-08-10&end=2026-08-10");
        miss!.CompletedTasks.Should().BeEmpty();
    }

    [Fact]
    public async Task GetData_IsScopedPerUser()
    {
        var a = await RegisterAsync();
        var b = await RegisterAsync();
        await using (var db = NewElevatedDbContext())
        {
            db.Add(new TaskItem
            {
                Id = Guid.CreateVersion7(), UserId = a.UserId, Title = "a", Notes = "", Labels = new(),
                Source = "local", Status = TaskStatus.Done, Rank = "a0", DurationMinutes = 1, ActualTimeMinutes = 0,
                CompletedAt = DateTimeOffset.Parse("2026-09-01T10:00:00Z"),
            });
            await db.SaveChangesAsync();
        }

        var bDto = await b.Client.GetFromJsonAsync<ReportDataResponse>(
            "/api/v1/reports?start=2026-09-01&end=2026-09-01");
        bDto!.CompletedTasks.Should().BeEmpty();
    }

    [Fact]
    public async Task GetData_RequiresAuthentication()
    {
        var resp = await Client.GetAsync("/api/v1/reports?start=2026-06-01&end=2026-06-01");
        resp.StatusCode.Should().Be(HttpStatusCode.Unauthorized);
    }
}
```
- [ ] **Run, expect FAIL (compile error).** `dotnet test --filter "FullyQualifiedName~ReportsTests"` — expect `CS0246: ... 'ReportDataResponse'`.
- [ ] **Minimal impl — DTOs (full code).** Create `backend/src/Tmap.Api/Features/Reports/ReportsDtos.cs`:
```csharp
namespace Tmap.Api.Features.Reports;

public sealed record CompletedTaskReportItem(Guid Id, string Project, DateOnly Date);

public sealed record SessionReportItem(string Project, int Minutes, DateOnly Date);

public sealed record DailyPlanReportItem(
    DateOnly Date,
    DateTimeOffset CommittedAt,
    List<Guid> PlannedTaskIds,
    int PlannedMinutes);

public sealed record ReportDataResponse(
    List<CompletedTaskReportItem> CompletedTasks,
    List<SessionReportItem> Sessions,
    List<DailyPlanReportItem> DailyPlans);
```
- [ ] **Minimal impl — endpoints (full code).** Create `backend/src/Tmap.Api/Features/Reports/ReportsEndpoints.cs`:
```csharp
using Microsoft.AspNetCore.Http.HttpResults;
using Microsoft.EntityFrameworkCore;
using Tmap.Api.Common;
using Tmap.Api.Infrastructure;
using Tmap.Api.Infrastructure.Entities;

namespace Tmap.Api.Features.Reports;

public static class ReportsEndpoints
{
    public static IEndpointRouteBuilder MapReportsEndpoints(this IEndpointRouteBuilder app)
    {
        var group = app.MapGroup("/api/v1/reports").RequireAuthorization();
        group.MapGet("/", GetData).WithName("GetReportData");
        return app;
    }

    private static async Task<Results<Ok<ReportDataResponse>, ValidationProblem>> GetData(
        DateOnly start,
        DateOnly end,
        AppDbContext db,
        ICurrentUser currentUser,
        CancellationToken ct)
    {
        if (end < start)
        {
            return TypedResults.ValidationProblem(new Dictionary<string, string[]>
            {
                ["end"] = ["'end' must be on or after 'start'."],
            });
        }

        var userId = currentUser.Id;

        // Resolve the user's IANA timezone for completed-task day bucketing.
        var tzId = await db.Users
            .Where(u => u.Id == userId)
            .Select(u => u.TimeZoneId)
            .SingleAsync(ct);
        var tz = ResolveTimeZone(tzId);

        // Completed tasks: bucket CompletedAt into the user's LOCAL day in-memory.
        // (Pull the minimal columns; tenant + soft-delete filters apply.)
        var rawTasks = await db.Set<TaskItem>()
            .AsNoTracking()
            .Where(t => t.CompletedAt != null)
            .Select(t => new
            {
                t.Id,
                t.CompletedAt,
                ProjectName = t.ProjectId == null
                    ? null
                    : db.Set<Project>().Where(p => p.Id == t.ProjectId).Select(p => p.Name).FirstOrDefault(),
            })
            .ToListAsync(ct);

        var completedTasks = rawTasks
            .Select(t => new
            {
                t.Id,
                Project = t.ProjectName ?? "",
                Date = DateOnly.FromDateTime(
                    TimeZoneInfo.ConvertTime(t.CompletedAt!.Value, tz).DateTime),
            })
            .Where(t => t.Date >= start && t.Date <= end)
            .Select(t => new CompletedTaskReportItem(t.Id, t.Project, t.Date))
            .ToList();

        // Focus sessions: Date is already the local day at write time.
        var sessions = await db.Set<FocusSession>()
            .AsNoTracking()
            .Where(f => f.Date >= start && f.Date <= end)
            .OrderBy(f => f.Date)
            .Select(f => new SessionReportItem(f.Project ?? "", f.Minutes, f.Date))
            .ToListAsync(ct);

        // Daily plans: Date is the keyed local day.
        var dailyPlans = await db.Set<DailyPlan>()
            .AsNoTracking()
            .Where(p => p.Date >= start && p.Date <= end)
            .OrderBy(p => p.Date)
            .Select(p => new DailyPlanReportItem(p.Date, p.CommittedAt, p.PlannedTaskIds, p.PlannedMinutes))
            .ToListAsync(ct);

        return TypedResults.Ok(new ReportDataResponse(completedTasks, sessions, dailyPlans));
    }

    private static TimeZoneInfo ResolveTimeZone(string? tzId)
    {
        if (string.IsNullOrWhiteSpace(tzId)) return TimeZoneInfo.Utc;
        // .NET on both Linux and Windows resolves IANA ids via ICU; fall back to UTC.
        try { return TimeZoneInfo.FindSystemTimeZoneById(tzId); }
        catch (TimeZoneNotFoundException) { return TimeZoneInfo.Utc; }
        catch (InvalidTimeZoneException) { return TimeZoneInfo.Utc; }
    }
}
```
> Note: completed-task bucketing is done in memory (after a filtered DB read) because the bucket is computed in the user's IANA timezone and EF cannot translate `TimeZoneInfo.ConvertTime` to SQL. Sessions and plans filter in SQL since their `Date` is already a local `DateOnly`. If the completed-task volume per report window ever grows large, the SP3/SP5 follow-up is a Postgres `(timestamptz AT TIME ZONE :tz)::date` raw projection — out of scope here.
- [ ] **Wire into Program.cs.**
```csharp
app.MapReportsEndpoints();
```
(add `using Tmap.Api.Features.Reports;`. No validator: query-string validation is inline.)
- [ ] **Run, expect PASS.** `dotnet test --filter "FullyQualifiedName~ReportsTests"`.
- [ ] **Commit.**
```
feat(api): add reports slice with timezone bucketing and soft-delete exclusion

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P5-6: Recurrence slice — DTOs, validators, getRule + updateRule

The Recurrence slice is the largest. This task lands the rule read/write surface (`recurrence.getRule` → `GET /api/v1/recurrence/rules/{ruleId}`, `recurrence.updateRule` → `PATCH /api/v1/recurrence/rules/{ruleId}`). `updateRule` mutates the `RecurrenceRule` fields in place (field-level LWW) and **resets `GeneratedUntil` to NULL** (mirroring the legacy "force re-generation" behavior) and **soft-deletes (tombstones) future, non-detached, not-done instances** so they will be regenerated on the next `ensure-instances` call.

- [ ] **Write failing test.** Create `backend/tests/Tmap.Api.Tests/RecurrenceTests.cs` with the rule-CRUD cases first:
```csharp
using System.Net;
using System.Net.Http.Json;
using FluentAssertions;
using Microsoft.EntityFrameworkCore;
using Tmap.Api.Features.Recurrence;
using Tmap.Api.Infrastructure.Entities;
using Xunit;

namespace Tmap.Api.Tests;

[Collection("db")]
public class RecurrenceTests(PostgresFixture fixture) : IntegrationTestBase(fixture)
{
    private static CreateRecurringTaskRequest WeeklyMonWedFri(string? plannedDate = "2026-06-01") =>
        new(
            new RecurringTaskInput(
                Title: "Standup", Notes: "", ProjectId: null, Labels: new(), Source: "local",
                PlannedDate: plannedDate is null ? null : DateOnly.Parse(plannedDate),
                DurationMinutes: 15, Priority: null, ReminderMinutes: 0),
            new RecurrenceRuleInput(
                Frequency: RecurrenceFrequency.Weekly, Interval: 1, DaysOfWeek: new List<int> { 1, 3, 5 },
                EndType: RecurrenceEndType.Never, EndCount: null, EndDate: null));

    [Fact]
    public async Task GetRule_ReturnsCreatedRule()
    {
        var user = await RegisterAsync();
        var created = await (await user.Client.PostAsJsonAsync("/api/v1/recurrence", WeeklyMonWedFri()))
            .Content.ReadFromJsonAsync<RecurringTaskResponse>();

        var ruleId = created!.RecurrenceRuleId;
        var rule = await user.Client.GetFromJsonAsync<RecurrenceRuleResponse>($"/api/v1/recurrence/rules/{ruleId}");

        rule!.Id.Should().Be(ruleId);
        rule.Frequency.Should().Be(RecurrenceFrequency.Weekly);
        rule.DaysOfWeek.Should().BeEquivalentTo(new[] { 1, 3, 5 });
    }

    [Fact]
    public async Task GetRule_ReturnsNotFound_ForUnknownId()
    {
        var user = await RegisterAsync();
        var resp = await user.Client.GetAsync($"/api/v1/recurrence/rules/{Guid.NewGuid()}");
        resp.StatusCode.Should().Be(HttpStatusCode.NotFound);
    }

    [Fact]
    public async Task UpdateRule_MutatesFields_ResetsGeneratedUntil_AndTombstonesFutureInstances()
    {
        var user = await RegisterAsync();
        var created = await (await user.Client.PostAsJsonAsync("/api/v1/recurrence", WeeklyMonWedFri()))
            .Content.ReadFromJsonAsync<RecurringTaskResponse>();
        var ruleId = created!.RecurrenceRuleId;

        // Ensure some future instances exist.
        await user.Client.PostAsync("/api/v1/recurrence/ensure-instances?start=2026-06-01&end=2026-06-30", null);

        var patch = await user.Client.PatchAsJsonAsync($"/api/v1/recurrence/rules/{ruleId}",
            new UpdateRuleRequest(Frequency: RecurrenceFrequency.Daily, Interval: 2,
                DaysOfWeek: new List<int>(), EndType: RecurrenceEndType.Never, EndCount: null, EndDate: null));
        patch.StatusCode.Should().Be(HttpStatusCode.OK);
        var updated = await patch.Content.ReadFromJsonAsync<RecurrenceRuleResponse>();
        updated!.Frequency.Should().Be(RecurrenceFrequency.Daily);
        updated.Interval.Should().Be(2);
        updated.GeneratedUntil.Should().BeNull();

        await using var db = NewElevatedDbContext();
        // Future, non-detached, not-done instances should be tombstoned.
        var liveFuture = await db.Set<TaskItem>()
            .Where(t => t.RecurrenceRuleId == ruleId && !t.IsRecurrenceTemplate
                        && !t.RecurrenceDetached && t.DeletedAt == null)
            .CountAsync();
        liveFuture.Should().Be(0);
    }

    [Fact]
    public async Task GetRule_IsScopedPerUser()
    {
        var a = await RegisterAsync();
        var b = await RegisterAsync();
        var created = await (await a.Client.PostAsJsonAsync("/api/v1/recurrence", WeeklyMonWedFri()))
            .Content.ReadFromJsonAsync<RecurringTaskResponse>();

        var resp = await b.Client.GetAsync($"/api/v1/recurrence/rules/{created!.RecurrenceRuleId}");
        resp.StatusCode.Should().Be(HttpStatusCode.NotFound); // tenant filter hides it
    }
}
```
> The `create` and `ensure-instances` endpoints are implemented in Tasks P5-7 and P5-8; this test references them so it compiles, but its rule-CRUD assertions are the focus. Run the full class only after P5-8 lands; for THIS step, run just the `GetRule_*` and `UpdateRule_*` filters.
- [ ] **Run, expect FAIL (compile error).** `dotnet test --filter "FullyQualifiedName~RecurrenceTests.GetRule_ReturnsCreatedRule"` — expect `CS0246` for the DTO records and the missing endpoints.
- [ ] **Minimal impl — DTOs (full code).** Create `backend/src/Tmap.Api/Features/Recurrence/RecurrenceDtos.cs`:
```csharp
using Tmap.Api.Infrastructure.Entities;

namespace Tmap.Api.Features.Recurrence;

// ---- create(task + rule) ----
public sealed record RecurringTaskInput(
    string Title,
    string Notes,
    Guid? ProjectId,
    List<string> Labels,
    string Source,
    DateOnly? PlannedDate,
    int DurationMinutes,
    int? Priority,
    int? ReminderMinutes);

public sealed record RecurrenceRuleInput(
    RecurrenceFrequency Frequency,
    int Interval,
    List<int> DaysOfWeek,
    RecurrenceEndType EndType,
    int? EndCount,
    DateOnly? EndDate);

public sealed record CreateRecurringTaskRequest(
    RecurringTaskInput Task,
    RecurrenceRuleInput Rule);

// Response after create: the template task plus its rule id.
public sealed record RecurringTaskResponse(
    Guid Id,
    string Title,
    Guid RecurrenceRuleId,
    bool IsRecurrenceTemplate,
    DateOnly? PlannedDate);

// ---- rule read ----
public sealed record RecurrenceRuleResponse(
    Guid Id,
    RecurrenceFrequency Frequency,
    int Interval,
    List<int> DaysOfWeek,
    RecurrenceEndType EndType,
    int? EndCount,
    DateOnly? EndDate,
    DateOnly? GeneratedUntil,
    DateTimeOffset CreatedAt,
    DateTimeOffset UpdatedAt);

// ---- updateRule (whole-rule replace of mutable fields) ----
public sealed record UpdateRuleRequest(
    RecurrenceFrequency Frequency,
    int Interval,
    List<int> DaysOfWeek,
    RecurrenceEndType EndType,
    int? EndCount,
    DateOnly? EndDate);

// ---- updateSeries(ruleId, updates): field-level edits propagated to template + future live instances ----
public sealed record UpdateSeriesRequest(
    string? Title,
    string? Notes,
    Guid? ProjectId,
    List<string>? Labels,
    int? DurationMinutes,
    int? Priority,
    int? ReminderMinutes);

// ---- deleteSeriesFuture(ruleId, fromDate) ----
public sealed record DeleteSeriesFutureRequest(DateOnly FromDate);

// ---- ensure-instances result: the created instance tasks ----
public sealed record EnsureInstancesResponse(List<CreatedInstance> Created);

public sealed record CreatedInstance(
    Guid Id,
    Guid RecurrenceRuleId,
    DateOnly PlannedDate,
    string Title);
```
- [ ] **Minimal impl — validators (full code).** Create `backend/src/Tmap.Api/Features/Recurrence/RecurrenceValidators.cs`:
```csharp
using FluentValidation;
using Tmap.Api.Infrastructure.Entities;

namespace Tmap.Api.Features.Recurrence;

public sealed class CreateRecurringTaskValidator : AbstractValidator<CreateRecurringTaskRequest>
{
    public CreateRecurringTaskValidator()
    {
        RuleFor(x => x.Task).NotNull();
        RuleFor(x => x.Task.Title).NotEmpty().MaximumLength(500);
        RuleFor(x => x.Task.DurationMinutes).GreaterThanOrEqualTo(0);
        RuleFor(x => x.Rule).NotNull().SetValidator(new RuleInputValidator());
    }
}

public sealed class RuleInputValidator : AbstractValidator<RecurrenceRuleInput>
{
    public RuleInputValidator()
    {
        RuleFor(r => r.Interval).GreaterThanOrEqualTo(1);
        RuleFor(r => r.DaysOfWeek).NotNull();
        RuleForEach(r => r.DaysOfWeek).InclusiveBetween(0, 6);
        When(r => r.Frequency == RecurrenceFrequency.Weekly, () =>
            RuleFor(r => r.DaysOfWeek).NotEmpty()
                .WithMessage("Weekly recurrence requires at least one day of week."));
        When(r => r.EndType == RecurrenceEndType.Count, () =>
            RuleFor(r => r.EndCount).NotNull().GreaterThan(0));
        When(r => r.EndType == RecurrenceEndType.Date, () =>
            RuleFor(r => r.EndDate).NotNull());
    }
}

public sealed class UpdateRuleValidator : AbstractValidator<UpdateRuleRequest>
{
    public UpdateRuleValidator()
    {
        RuleFor(r => r.Interval).GreaterThanOrEqualTo(1);
        RuleFor(r => r.DaysOfWeek).NotNull();
        RuleForEach(r => r.DaysOfWeek).InclusiveBetween(0, 6);
        When(r => r.Frequency == RecurrenceFrequency.Weekly, () =>
            RuleFor(r => r.DaysOfWeek).NotEmpty());
        When(r => r.EndType == RecurrenceEndType.Count, () =>
            RuleFor(r => r.EndCount).NotNull().GreaterThan(0));
        When(r => r.EndType == RecurrenceEndType.Date, () =>
            RuleFor(r => r.EndDate).NotNull());
    }
}

public sealed class UpdateSeriesValidator : AbstractValidator<UpdateSeriesRequest>
{
    public UpdateSeriesValidator()
    {
        When(x => x.Title is not null, () => RuleFor(x => x.Title!).NotEmpty().MaximumLength(500));
        When(x => x.DurationMinutes is not null, () => RuleFor(x => x.DurationMinutes!.Value).GreaterThanOrEqualTo(0));
    }
}

public sealed class DeleteSeriesFutureValidator : AbstractValidator<DeleteSeriesFutureRequest>
{
    public DeleteSeriesFutureValidator()
    {
        RuleFor(x => x.FromDate).NotEmpty();
    }
}
```
- [ ] **Minimal impl — endpoints skeleton with getRule + updateRule (full code).** Create `backend/src/Tmap.Api/Features/Recurrence/RecurrenceEndpoints.cs`:
```csharp
using Microsoft.AspNetCore.Http.HttpResults;
using Microsoft.EntityFrameworkCore;
using Tmap.Api.Common;
using Tmap.Api.Common.Validation;
using Tmap.Api.Infrastructure;
using Tmap.Api.Infrastructure.Entities;

namespace Tmap.Api.Features.Recurrence;

public static class RecurrenceEndpoints
{
    public static IEndpointRouteBuilder MapRecurrenceEndpoints(this IEndpointRouteBuilder app)
    {
        var group = app.MapGroup("/api/v1/recurrence").RequireAuthorization();

        group.MapGet("/rules/{ruleId:guid}", GetRule).WithName("GetRecurrenceRule");
        group.MapPatch("/rules/{ruleId:guid}", UpdateRule)
            .AddEndpointFilter<ValidationFilter<UpdateRuleRequest>>()
            .WithName("UpdateRecurrenceRule");

        // (create, updateSeries, deleteSeries, deleteSeriesFuture, detachInstance,
        //  ensure-instances are mapped in P5-7 / P5-8 — added to THIS group.)
        MapSeriesOperations(group);
        MapEnsureInstances(group);

        return app;
    }

    private static async Task<Results<Ok<RecurrenceRuleResponse>, NotFound>> GetRule(
        Guid ruleId,
        AppDbContext db,
        ICurrentUser currentUser,
        CancellationToken ct)
    {
        var rule = await db.Set<RecurrenceRule>()
            .AsNoTracking()
            .FirstOrDefaultAsync(r => r.Id == ruleId, ct);

        return rule is null ? TypedResults.NotFound() : TypedResults.Ok(ToRuleResponse(rule));
    }

    private static async Task<Results<Ok<RecurrenceRuleResponse>, NotFound>> UpdateRule(
        Guid ruleId,
        UpdateRuleRequest req,
        AppDbContext db,
        ICurrentUser currentUser,
        CancellationToken ct)
    {
        var rule = await db.Set<RecurrenceRule>().FirstOrDefaultAsync(r => r.Id == ruleId, ct);
        if (rule is null) return TypedResults.NotFound();

        rule.Frequency = req.Frequency;
        rule.IntervalValue = req.Interval;
        rule.DaysOfWeek = req.DaysOfWeek;
        rule.EndType = req.EndType;
        rule.EndCount = req.EndType == RecurrenceEndType.Count ? req.EndCount : null;
        rule.EndDate = req.EndType == RecurrenceEndType.Date ? req.EndDate : null;
        // Force regeneration on the next ensure-instances (legacy behavior).
        rule.GeneratedUntil = null;

        // Tombstone future, non-detached, not-done instances so they regenerate.
        var today = DateOnly.FromDateTime(DateTime.UtcNow);
        var now = DateTimeOffset.UtcNow;
        await db.Set<TaskItem>()
            .Where(t => t.RecurrenceRuleId == ruleId
                        && !t.IsRecurrenceTemplate
                        && !t.RecurrenceDetached
                        && t.Status != TaskStatus.Done
                        && t.PlannedDate >= today)
            .ExecuteUpdateAsync(s => s.SetProperty(t => t.DeletedAt, now), ct);

        await db.SaveChangesAsync(ct);
        return TypedResults.Ok(ToRuleResponse(rule));
    }

    internal static RecurrenceRuleResponse ToRuleResponse(RecurrenceRule r) =>
        new(r.Id, r.Frequency, r.IntervalValue, r.DaysOfWeek, r.EndType, r.EndCount, r.EndDate,
            r.GeneratedUntil, r.CreatedAt, r.UpdatedAt);

    // Defined in P5-7.
    static partial void MapSeriesOperationsHook(RouteGroupBuilder group);
    private static void MapSeriesOperations(RouteGroupBuilder group) => MapSeriesOperationsImpl(group);

    // Defined in P5-8.
    private static void MapEnsureInstances(RouteGroupBuilder group) => MapEnsureInstancesImpl(group);
}
```
> Implementation note: to keep one file, P5-7 and P5-8 add their handler bodies and the `MapSeriesOperationsImpl` / `MapEnsureInstancesImpl` static methods **into this same `RecurrenceEndpoints` class** (shown as full file replacements in those tasks). For THIS step, temporarily stub them so the project compiles:
```csharp
// TEMPORARY STUBS — replaced in P5-7 / P5-8 (kept at the bottom of the class):
private static void MapSeriesOperationsImpl(RouteGroupBuilder group) { }
private static void MapEnsureInstancesImpl(RouteGroupBuilder group) { }
```
(Remove the unused `MapSeriesOperationsHook` partial line — it was illustrative; the real wiring is the two `*Impl` methods. Keep only the two stub methods above for this step.)
- [ ] **Wire into Program.cs.**
```csharp
builder.Services.AddScoped<IValidator<CreateRecurringTaskRequest>, CreateRecurringTaskValidator>();
builder.Services.AddScoped<IValidator<UpdateRuleRequest>, UpdateRuleValidator>();
builder.Services.AddScoped<IValidator<UpdateSeriesRequest>, UpdateSeriesValidator>();
builder.Services.AddScoped<IValidator<DeleteSeriesFutureRequest>, DeleteSeriesFutureValidator>();
app.MapRecurrenceEndpoints();
```
(add `using Tmap.Api.Features.Recurrence;`.)
- [ ] **Run, expect PASS (rule CRUD only).** `dotnet test --filter "FullyQualifiedName~RecurrenceTests.GetRule_ReturnsCreatedRule"` will still fail because `create`/`ensure-instances` are stubbed; instead run the unit-only assertion path is not possible here. **Run the build and the `UpdateRule`-independent compile:** `dotnet build`. Expect build PASS. (Endpoint-level green for GetRule/UpdateRule arrives once create lands in P5-7.)
- [ ] **Commit.**
```
feat(api): scaffold recurrence slice DTOs, validators, rule get/update

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P5-7: Recurrence — create(task+rule) + series operations (all soft deletes)

This task implements `recurrence.create` (`POST /api/v1/recurrence`), `recurrence.updateSeries` (`PATCH /api/v1/recurrence/rules/{ruleId}/series`), `recurrence.deleteSeries` (`DELETE /api/v1/recurrence/rules/{ruleId}`, **soft** — tombstones rule + its tasks + its exceptions), `recurrence.deleteSeriesFuture` (`POST /api/v1/recurrence/rules/{ruleId}/delete-future`, soft-tombstones future instances and caps the rule via `EndType=Date`), and `recurrence.detachInstance` (`PATCH /api/v1/recurrence/instances/{taskId}/detach`). `create` builds the **template** `TaskItem` (`IsRecurrenceTemplate=true`, `RecurrenceOriginalDate = startDate`) and its `RecurrenceRule`. **No instances are generated by `create`** — generation is server-authoritative via `ensure-instances` (P5-8), which keeps creation cheap and idempotent.

- [ ] **Write failing tests.** Append to `backend/tests/Tmap.Api.Tests/RecurrenceTests.cs`:
```csharp
    [Fact]
    public async Task Create_PersistsTemplateAndRule_NoInstancesYet()
    {
        var user = await RegisterAsync();
        var resp = await user.Client.PostAsJsonAsync("/api/v1/recurrence", WeeklyMonWedFri());
        resp.StatusCode.Should().Be(HttpStatusCode.Created);
        var dto = await resp.Content.ReadFromJsonAsync<RecurringTaskResponse>();
        dto!.IsRecurrenceTemplate.Should().BeTrue();
        dto.RecurrenceRuleId.Should().NotBe(Guid.Empty);

        await using var db = NewElevatedDbContext();
        var template = await db.Set<TaskItem>().SingleAsync(t => t.Id == dto.Id);
        template.IsRecurrenceTemplate.Should().BeTrue();
        template.RecurrenceOriginalDate.Should().Be(new DateOnly(2026, 6, 1));
        template.UserId.Should().Be(user.UserId);

        var instanceCount = await db.Set<TaskItem>()
            .CountAsync(t => t.RecurrenceRuleId == dto.RecurrenceRuleId && !t.IsRecurrenceTemplate);
        instanceCount.Should().Be(0); // generation is deferred to ensure-instances
    }

    [Fact]
    public async Task UpdateSeries_PropagatesToTemplateAndFutureLiveInstances()
    {
        var user = await RegisterAsync();
        var created = await (await user.Client.PostAsJsonAsync("/api/v1/recurrence", WeeklyMonWedFri()))
            .Content.ReadFromJsonAsync<RecurringTaskResponse>();
        var ruleId = created!.RecurrenceRuleId;
        await user.Client.PostAsync("/api/v1/recurrence/ensure-instances?start=2026-06-01&end=2026-06-30", null);

        var patch = await user.Client.PatchAsJsonAsync($"/api/v1/recurrence/rules/{ruleId}/series",
            new UpdateSeriesRequest(Title: "Renamed Standup", Notes: null, ProjectId: null,
                Labels: null, DurationMinutes: 45, Priority: null, ReminderMinutes: null));
        patch.StatusCode.Should().Be(HttpStatusCode.NoContent);

        await using var db = NewElevatedDbContext();
        var template = await db.Set<TaskItem>()
            .SingleAsync(t => t.RecurrenceRuleId == ruleId && t.IsRecurrenceTemplate);
        template.Title.Should().Be("Renamed Standup");
        template.DurationMinutes.Should().Be(45);

        var future = await db.Set<TaskItem>()
            .Where(t => t.RecurrenceRuleId == ruleId && !t.IsRecurrenceTemplate && t.DeletedAt == null)
            .ToListAsync();
        future.Should().OnlyContain(t => t.Title == "Renamed Standup" && t.DurationMinutes == 45);
    }

    [Fact]
    public async Task DeleteSeries_SoftDeletesRuleTasksAndExceptions()
    {
        var user = await RegisterAsync();
        var created = await (await user.Client.PostAsJsonAsync("/api/v1/recurrence", WeeklyMonWedFri()))
            .Content.ReadFromJsonAsync<RecurringTaskResponse>();
        var ruleId = created!.RecurrenceRuleId;
        await user.Client.PostAsync("/api/v1/recurrence/ensure-instances?start=2026-06-01&end=2026-06-30", null);

        var del = await user.Client.DeleteAsync($"/api/v1/recurrence/rules/{ruleId}");
        del.StatusCode.Should().Be(HttpStatusCode.NoContent);

        await using var db = NewElevatedDbContext();
        // No live rows remain; but rows still EXIST (tombstoned) when soft-delete filter is ignored.
        var liveTasks = await db.Set<TaskItem>().CountAsync(t => t.RecurrenceRuleId == ruleId);
        liveTasks.Should().Be(0);
        var allTasks = await db.Set<TaskItem>()
            .IgnoreQueryFilters(["SoftDelete"]).CountAsync(t => t.RecurrenceRuleId == ruleId);
        allTasks.Should().BeGreaterThan(0);

        var liveRule = await db.Set<RecurrenceRule>().CountAsync(r => r.Id == ruleId);
        liveRule.Should().Be(0);
        var tombstonedRule = await db.Set<RecurrenceRule>()
            .IgnoreQueryFilters(["SoftDelete"]).SingleAsync(r => r.Id == ruleId);
        tombstonedRule.DeletedAt.Should().NotBeNull();

        // getRule now 404s (soft-delete filter active).
        var getResp = await user.Client.GetAsync($"/api/v1/recurrence/rules/{ruleId}");
        getResp.StatusCode.Should().Be(HttpStatusCode.NotFound);
    }

    [Fact]
    public async Task DeleteSeriesFuture_TombstonesFutureInstances_AndCapsRule()
    {
        var user = await RegisterAsync();
        var created = await (await user.Client.PostAsJsonAsync("/api/v1/recurrence", WeeklyMonWedFri()))
            .Content.ReadFromJsonAsync<RecurringTaskResponse>();
        var ruleId = created!.RecurrenceRuleId;
        await user.Client.PostAsync("/api/v1/recurrence/ensure-instances?start=2026-06-01&end=2026-06-30", null);

        var resp = await user.Client.PostAsJsonAsync(
            $"/api/v1/recurrence/rules/{ruleId}/delete-future",
            new DeleteSeriesFutureRequest(new DateOnly(2026, 6, 15)));
        resp.StatusCode.Should().Be(HttpStatusCode.NoContent);

        await using var db = NewElevatedDbContext();
        var liveOnOrAfter = await db.Set<TaskItem>()
            .CountAsync(t => t.RecurrenceRuleId == ruleId && !t.IsRecurrenceTemplate
                             && t.PlannedDate >= new DateOnly(2026, 6, 15));
        liveOnOrAfter.Should().Be(0);

        var liveBefore = await db.Set<TaskItem>()
            .CountAsync(t => t.RecurrenceRuleId == ruleId && !t.IsRecurrenceTemplate
                             && t.PlannedDate < new DateOnly(2026, 6, 15));
        liveBefore.Should().BeGreaterThan(0);

        var rule = await db.Set<RecurrenceRule>().SingleAsync(r => r.Id == ruleId);
        rule.EndType.Should().Be(RecurrenceEndType.Date);
        rule.EndDate.Should().Be(new DateOnly(2026, 6, 14)); // day before fromDate
    }

    [Fact]
    public async Task DetachInstance_MarksInstanceDetached()
    {
        var user = await RegisterAsync();
        var created = await (await user.Client.PostAsJsonAsync("/api/v1/recurrence", WeeklyMonWedFri()))
            .Content.ReadFromJsonAsync<RecurringTaskResponse>();
        var ruleId = created!.RecurrenceRuleId;
        await user.Client.PostAsync("/api/v1/recurrence/ensure-instances?start=2026-06-01&end=2026-06-30", null);

        Guid instanceId;
        await using (var db = NewElevatedDbContext())
        {
            instanceId = await db.Set<TaskItem>()
                .Where(t => t.RecurrenceRuleId == ruleId && !t.IsRecurrenceTemplate)
                .OrderBy(t => t.PlannedDate).Select(t => t.Id).FirstAsync();
        }

        var resp = await user.Client.PatchAsync($"/api/v1/recurrence/instances/{instanceId}/detach", null);
        resp.StatusCode.Should().Be(HttpStatusCode.NoContent);

        await using var db2 = NewElevatedDbContext();
        var inst = await db2.Set<TaskItem>().SingleAsync(t => t.Id == instanceId);
        inst.RecurrenceDetached.Should().BeTrue();
    }
```
- [ ] **Run, expect FAIL.** `dotnet test --filter "FullyQualifiedName~RecurrenceTests.Create_PersistsTemplateAndRule_NoInstancesYet"` — expect a `404`/route-not-found assertion failure (handlers are stubbed): the POST returns 404, so `resp.StatusCode.Should().Be(HttpStatusCode.Created)` fails with `Expected ... Created, but found NotFound`.
- [ ] **Minimal impl — replace the stub region with real series operations (full code).** In `backend/src/Tmap.Api/Features/Recurrence/RecurrenceEndpoints.cs`, replace the temporary `MapSeriesOperationsImpl` stub with the following (full method bodies; `MapEnsureInstancesImpl` stays stubbed until P5-8):
```csharp
    private static void MapSeriesOperationsImpl(RouteGroupBuilder group)
    {
        group.MapPost("/", Create)
            .AddEndpointFilter<ValidationFilter<CreateRecurringTaskRequest>>()
            .WithName("CreateRecurringTask");

        group.MapPatch("/rules/{ruleId:guid}/series", UpdateSeries)
            .AddEndpointFilter<ValidationFilter<UpdateSeriesRequest>>()
            .WithName("UpdateRecurrenceSeries");

        group.MapDelete("/rules/{ruleId:guid}", DeleteSeries).WithName("DeleteRecurrenceSeries");

        group.MapPost("/rules/{ruleId:guid}/delete-future", DeleteSeriesFuture)
            .AddEndpointFilter<ValidationFilter<DeleteSeriesFutureRequest>>()
            .WithName("DeleteRecurrenceSeriesFuture");

        group.MapPatch("/instances/{taskId:guid}/detach", DetachInstance)
            .WithName("DetachRecurrenceInstance");
    }

    private static async Task<Created<RecurringTaskResponse>> Create(
        CreateRecurringTaskRequest req,
        AppDbContext db,
        ICurrentUser currentUser,
        CancellationToken ct)
    {
        var userId = currentUser.Id;
        var startDate = req.Task.PlannedDate ?? DateOnly.FromDateTime(DateTime.UtcNow);

        var rule = new RecurrenceRule
        {
            Id = Guid.CreateVersion7(),
            UserId = userId,
            Frequency = req.Rule.Frequency,
            IntervalValue = req.Rule.Interval < 1 ? 1 : req.Rule.Interval,
            DaysOfWeek = req.Rule.DaysOfWeek,
            EndType = req.Rule.EndType,
            EndCount = req.Rule.EndType == RecurrenceEndType.Count ? req.Rule.EndCount : null,
            EndDate = req.Rule.EndType == RecurrenceEndType.Date ? req.Rule.EndDate : null,
            GeneratedUntil = null,
        };
        db.Add(rule);

        var template = new TaskItem
        {
            Id = Guid.CreateVersion7(),
            UserId = userId,
            Title = req.Task.Title,
            Notes = req.Task.Notes,
            ProjectId = req.Task.ProjectId,
            Labels = req.Task.Labels,
            Source = req.Task.Source,
            Status = TaskStatus.Planned,
            PlannedDate = startDate,
            DurationMinutes = req.Task.DurationMinutes <= 0 ? 30 : req.Task.DurationMinutes,
            ActualTimeMinutes = 0,
            Priority = req.Task.Priority,
            ReminderMinutes = req.Task.ReminderMinutes ?? 0,
            Rank = "a0",
            RecurrenceRuleId = rule.Id,
            IsRecurrenceTemplate = true,
            RecurrenceDetached = false,
            RecurrenceOriginalDate = startDate,
        };
        db.Add(template);

        await db.SaveChangesAsync(ct);

        var dto = new RecurringTaskResponse(template.Id, template.Title, rule.Id, true, template.PlannedDate);
        return TypedResults.Created($"/api/v1/tasks/{template.Id}", dto);
    }

    private static async Task<Results<NoContent, NotFound>> UpdateSeries(
        Guid ruleId,
        UpdateSeriesRequest req,
        AppDbContext db,
        ICurrentUser currentUser,
        CancellationToken ct)
    {
        var ruleExists = await db.Set<RecurrenceRule>().AnyAsync(r => r.Id == ruleId, ct);
        if (!ruleExists) return TypedResults.NotFound();

        var today = DateOnly.FromDateTime(DateTime.UtcNow);

        // Propagate to the template (always) + future, non-detached, not-done live instances.
        var targets = await db.Set<TaskItem>()
            .Where(t => t.RecurrenceRuleId == ruleId
                        && (t.IsRecurrenceTemplate
                            || (!t.RecurrenceDetached && t.Status != TaskStatus.Done && t.PlannedDate >= today)))
            .ToListAsync(ct);

        foreach (var t in targets)
        {
            if (req.Title is not null) t.Title = req.Title;
            if (req.Notes is not null) t.Notes = req.Notes;
            if (req.ProjectId is not null) t.ProjectId = req.ProjectId;
            if (req.Labels is not null) t.Labels = req.Labels;
            if (req.DurationMinutes is not null) t.DurationMinutes = req.DurationMinutes.Value;
            if (req.Priority is not null) t.Priority = req.Priority;
            if (req.ReminderMinutes is not null) t.ReminderMinutes = req.ReminderMinutes;
        }

        await db.SaveChangesAsync(ct);
        return TypedResults.NoContent();
    }

    private static async Task<Results<NoContent, NotFound>> DeleteSeries(
        Guid ruleId,
        AppDbContext db,
        ICurrentUser currentUser,
        CancellationToken ct)
    {
        var rule = await db.Set<RecurrenceRule>().FirstOrDefaultAsync(r => r.Id == ruleId, ct);
        if (rule is null) return TypedResults.NotFound();

        var now = DateTimeOffset.UtcNow;

        // SOFT delete: tombstone the rule, all its tasks (template + instances),
        // and all its exceptions. ExecuteUpdate is allowed because the change_seq /
        // updated_at TRIGGER (not an interceptor) advances on bulk writes too.
        await db.Set<TaskItem>()
            .Where(t => t.RecurrenceRuleId == ruleId)
            .ExecuteUpdateAsync(s => s.SetProperty(t => t.DeletedAt, now), ct);

        await db.Set<RecurrenceException>()
            .Where(e => e.RecurrenceRuleId == ruleId)
            .ExecuteUpdateAsync(s => s.SetProperty(e => e.DeletedAt, now), ct);

        rule.DeletedAt = now;
        await db.SaveChangesAsync(ct);

        return TypedResults.NoContent();
    }

    private static async Task<Results<NoContent, NotFound>> DeleteSeriesFuture(
        Guid ruleId,
        DeleteSeriesFutureRequest req,
        AppDbContext db,
        ICurrentUser currentUser,
        CancellationToken ct)
    {
        var rule = await db.Set<RecurrenceRule>().FirstOrDefaultAsync(r => r.Id == ruleId, ct);
        if (rule is null) return TypedResults.NotFound();

        var now = DateTimeOffset.UtcNow;

        // SOFT delete future non-template, not-done instances on/after fromDate.
        await db.Set<TaskItem>()
            .Where(t => t.RecurrenceRuleId == ruleId
                        && !t.IsRecurrenceTemplate
                        && t.Status != TaskStatus.Done
                        && t.PlannedDate >= req.FromDate)
            .ExecuteUpdateAsync(s => s.SetProperty(t => t.DeletedAt, now), ct);

        // Cap the rule so future generation stops: EndType=Date, EndDate = fromDate - 1 day.
        rule.EndType = RecurrenceEndType.Date;
        rule.EndDate = req.FromDate.AddDays(-1);
        rule.EndCount = null;
        await db.SaveChangesAsync(ct);

        return TypedResults.NoContent();
    }

    private static async Task<Results<NoContent, NotFound>> DetachInstance(
        Guid taskId,
        AppDbContext db,
        ICurrentUser currentUser,
        CancellationToken ct)
    {
        var task = await db.Set<TaskItem>()
            .FirstOrDefaultAsync(t => t.Id == taskId && !t.IsRecurrenceTemplate, ct);
        if (task is null) return TypedResults.NotFound();

        task.RecurrenceDetached = true;
        await db.SaveChangesAsync(ct);
        return TypedResults.NoContent();
    }
```
> Keep the `MapEnsureInstancesImpl` stub as-is (empty) for this step — P5-8 replaces it.
- [ ] **Run, expect PASS (series ops + create + rule CRUD from P5-6).** Now that `create` exists, run the create/series subset and the P5-6 rule cases (they depend on `ensure-instances`, which is still stubbed; the `UpdateRule_*`/`DeleteSeries_*`/`DeleteSeriesFuture_*`/`UpdateSeries_*`/`DetachInstance_*` tests call `ensure-instances` and would generate zero rows). Run only the ones that do NOT require generated instances yet:
  - `dotnet test --filter "FullyQualifiedName~RecurrenceTests.Create_PersistsTemplateAndRule_NoInstancesYet"`
  - `dotnet test --filter "FullyQualifiedName~RecurrenceTests.GetRule_ReturnsCreatedRule"`
  - `dotnet test --filter "FullyQualifiedName~RecurrenceTests.GetRule_ReturnsNotFound_ForUnknownId"`
  - `dotnet test --filter "FullyQualifiedName~RecurrenceTests.GetRule_IsScopedPerUser"`
  
  Expect all four PASS. (Tests asserting on generated instances stay red until P5-8; that is expected and is resolved in the next task.)
- [ ] **Commit.**
```
feat(api): recurrence create + soft-delete series operations

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P5-8: Recurrence — ensure-instances (server-authoritative, idempotent, advisory-locked)

`recurrence.ensureInstances(start,end)` maps to `POST /api/v1/recurrence/ensure-instances?start=&end=`. It is **server-authoritative and idempotent against `recurrence_rules.generated_until`**: for every live rule whose `GeneratedUntil` is NULL or `< end`, it generates the missing occurrence dates (via `OccurrenceGenerator`), creates one `TaskItem` instance per date (copying template fields, `RecurrenceOriginalDate = date`, `IsRecurrenceTemplate=false`), and advances `GeneratedUntil = end`. **Concurrency:** the whole operation runs in one transaction and takes a **per-rule Postgres `pg_advisory_xact_lock`** keyed on the rule id, so two concurrent calls serialize per rule and never double-create. Idempotency is reinforced by skipping dates that already have a live (non-tombstoned) instance with that `RecurrenceOriginalDate`.

- [ ] **Write failing tests.** Append to `backend/tests/Tmap.Api.Tests/RecurrenceTests.cs`:
```csharp
    [Fact]
    public async Task EnsureInstances_GeneratesExpectedWeeklyDates()
    {
        var user = await RegisterAsync();
        var created = await (await user.Client.PostAsJsonAsync("/api/v1/recurrence", WeeklyMonWedFri()))
            .Content.ReadFromJsonAsync<RecurringTaskResponse>();
        var ruleId = created!.RecurrenceRuleId;

        var resp = await user.Client.PostAsync(
            "/api/v1/recurrence/ensure-instances?start=2026-06-01&end=2026-06-07", null);
        resp.StatusCode.Should().Be(HttpStatusCode.OK);
        var body = await resp.Content.ReadFromJsonAsync<EnsureInstancesResponse>();

        // Mon 06-01, Wed 06-03, Fri 06-05 within the first week.
        body!.Created.Select(c => c.PlannedDate).Should()
            .BeEquivalentTo(new[] { new DateOnly(2026, 6, 1), new DateOnly(2026, 6, 3), new DateOnly(2026, 6, 5) });

        await using var db = NewElevatedDbContext();
        var rule = await db.Set<RecurrenceRule>().SingleAsync(r => r.Id == ruleId);
        rule.GeneratedUntil.Should().Be(new DateOnly(2026, 6, 7));
    }

    [Fact]
    public async Task EnsureInstances_IsIdempotent_NoDuplicatesOnSecondCall()
    {
        var user = await RegisterAsync();
        var created = await (await user.Client.PostAsJsonAsync("/api/v1/recurrence", WeeklyMonWedFri()))
            .Content.ReadFromJsonAsync<RecurringTaskResponse>();
        var ruleId = created!.RecurrenceRuleId;

        var first = await user.Client.PostAsync(
            "/api/v1/recurrence/ensure-instances?start=2026-06-01&end=2026-06-07", null);
        var firstBody = await first.Content.ReadFromJsonAsync<EnsureInstancesResponse>();
        firstBody!.Created.Should().HaveCount(3);

        // Second identical call must create NOTHING new.
        var second = await user.Client.PostAsync(
            "/api/v1/recurrence/ensure-instances?start=2026-06-01&end=2026-06-07", null);
        var secondBody = await second.Content.ReadFromJsonAsync<EnsureInstancesResponse>();
        secondBody!.Created.Should().BeEmpty();

        await using var db = NewElevatedDbContext();
        var total = await db.Set<TaskItem>()
            .CountAsync(t => t.RecurrenceRuleId == ruleId && !t.IsRecurrenceTemplate);
        total.Should().Be(3); // exactly the first batch
    }

    [Fact]
    public async Task EnsureInstances_ConcurrentCalls_DoNotDoubleCreate()
    {
        var user = await RegisterAsync();
        var created = await (await user.Client.PostAsJsonAsync("/api/v1/recurrence", WeeklyMonWedFri()))
            .Content.ReadFromJsonAsync<RecurringTaskResponse>();
        var ruleId = created!.RecurrenceRuleId;

        const string url = "/api/v1/recurrence/ensure-instances?start=2026-06-01&end=2026-06-30";
        // Fire two concurrent calls on the same authed client.
        var t1 = user.Client.PostAsync(url, null);
        var t2 = user.Client.PostAsync(url, null);
        await Task.WhenAll(t1, t2);

        await using var db = NewElevatedDbContext();
        // One row per unique occurrence date, never two for the same date.
        var dates = await db.Set<TaskItem>()
            .Where(t => t.RecurrenceRuleId == ruleId && !t.IsRecurrenceTemplate)
            .Select(t => t.RecurrenceOriginalDate)
            .ToListAsync();
        dates.Should().OnlyHaveUniqueItems();
    }

    [Fact]
    public async Task EnsureInstances_RequiresAuthentication()
    {
        var resp = await Client.PostAsync(
            "/api/v1/recurrence/ensure-instances?start=2026-06-01&end=2026-06-07", null);
        resp.StatusCode.Should().Be(HttpStatusCode.Unauthorized);
    }
```
- [ ] **Run, expect FAIL.** `dotnet test --filter "FullyQualifiedName~RecurrenceTests.EnsureInstances_GeneratesExpectedWeeklyDates"` — expect failure: the stubbed `MapEnsureInstancesImpl` maps no route, so POST returns `404`, and `resp.StatusCode.Should().Be(HttpStatusCode.OK)` fails with `Expected ... OK, but found NotFound`.
- [ ] **Minimal impl — replace the ensure-instances stub (full code).** In `backend/src/Tmap.Api/Features/Recurrence/RecurrenceEndpoints.cs`, replace the empty `MapEnsureInstancesImpl` with the real implementation:
```csharp
    private static void MapEnsureInstancesImpl(RouteGroupBuilder group)
    {
        group.MapPost("/ensure-instances", EnsureInstances).WithName("EnsureRecurrenceInstances");
    }

    private static async Task<Results<Ok<EnsureInstancesResponse>, ValidationProblem>> EnsureInstances(
        DateOnly start,
        DateOnly end,
        AppDbContext db,
        ICurrentUser currentUser,
        CancellationToken ct)
    {
        if (end < start)
        {
            return TypedResults.ValidationProblem(new Dictionary<string, string[]>
            {
                ["end"] = ["'end' must be on or after 'start'."],
            });
        }

        var userId = currentUser.Id;
        var created = new List<CreatedInstance>();

        // One transaction for the whole call; advisory locks are xact-scoped.
        await using var tx = await db.Database.BeginTransactionAsync(ct);

        // Rules needing generation extended (live only; tenant filter scopes to user).
        var ruleIds = await db.Set<RecurrenceRule>()
            .Where(r => r.GeneratedUntil == null || r.GeneratedUntil < end)
            .Select(r => r.Id)
            .ToListAsync(ct);

        foreach (var ruleId in ruleIds)
        {
            // Per-rule advisory lock: two concurrent calls serialize here, so the
            // "read existing dates -> insert missing" sequence is never interleaved.
            // Key the lock on the low 63 bits of the rule's GUID for a stable bigint.
            var lockKey = AdvisoryLockKey(ruleId);
            await db.Database.ExecuteSqlInterpolatedAsync(
                $"SELECT pg_advisory_xact_lock({lockKey})", ct);

            var rule = await db.Set<RecurrenceRule>().FirstOrDefaultAsync(r => r.Id == ruleId, ct);
            if (rule is null) continue; // tombstoned between the list query and the lock

            var template = await db.Set<TaskItem>()
                .FirstOrDefaultAsync(t => t.RecurrenceRuleId == ruleId && t.IsRecurrenceTemplate, ct);
            if (template is null) continue;

            // Live instance dates already present (ignore tombstones so a deleted
            // occurrence is NOT regenerated — it is treated as "exists").
            var existingDates = await db.Set<TaskItem>()
                .IgnoreQueryFilters(["SoftDelete"])
                .Where(t => t.RecurrenceRuleId == ruleId
                            && !t.IsRecurrenceTemplate
                            && t.RecurrenceOriginalDate != null)
                .Select(t => t.RecurrenceOriginalDate!.Value)
                .ToListAsync(ct);

            var exceptionDates = await db.Set<RecurrenceException>()
                .Where(e => e.RecurrenceRuleId == ruleId)
                .Select(e => e.ExceptionDate)
                .ToListAsync(ct);

            var ruleData = new OccurrenceRuleData(
                rule.Frequency, rule.IntervalValue, rule.DaysOfWeek,
                rule.EndType, rule.EndCount, rule.EndDate);

            var templateStart = template.RecurrenceOriginalDate ?? template.PlannedDate ?? start;

            var dates = OccurrenceGenerator.Generate(
                ruleData,
                templateStart: templateStart,
                rangeStart: start,
                rangeEnd: end,
                existingDates: new HashSet<DateOnly>(existingDates),
                exceptionDates: new HashSet<DateOnly>(exceptionDates));

            foreach (var date in dates)
            {
                var instance = new TaskItem
                {
                    Id = Guid.CreateVersion7(),
                    UserId = userId,
                    Title = template.Title,
                    Notes = template.Notes,
                    ProjectId = template.ProjectId,
                    Labels = new List<string>(template.Labels),
                    Source = template.Source,
                    Status = TaskStatus.Planned,
                    PlannedDate = date,
                    ScheduledStart = template.ScheduledStart,
                    ScheduledEnd = template.ScheduledEnd,
                    DurationMinutes = template.DurationMinutes,
                    ActualTimeMinutes = 0,
                    Priority = template.Priority,
                    ReminderMinutes = template.ReminderMinutes,
                    Rank = template.Rank,
                    RecurrenceRuleId = ruleId,
                    IsRecurrenceTemplate = false,
                    RecurrenceDetached = false,
                    RecurrenceOriginalDate = date,
                };
                db.Add(instance);
                created.Add(new CreatedInstance(instance.Id, ruleId, date, instance.Title));
            }

            // Advance the generation cursor idempotently.
            if (rule.GeneratedUntil == null || rule.GeneratedUntil < end)
            {
                rule.GeneratedUntil = end;
            }

            await db.SaveChangesAsync(ct);
        }

        await tx.CommitAsync(ct);
        return TypedResults.Ok(new EnsureInstancesResponse(created));
    }

    /// <summary>
    /// Stable signed 64-bit key for pg_advisory_xact_lock derived from a GUID.
    /// Uses the first 8 bytes; collisions only cost extra serialization, never
    /// correctness (idempotency is also guaranteed by the existing-date skip).
    /// </summary>
    private static long AdvisoryLockKey(Guid id)
    {
        Span<byte> bytes = stackalloc byte[16];
        id.TryWriteBytes(bytes);
        return BitConverter.ToInt64(bytes[..8]);
    }
```
> Notes: (1) `ExecuteSqlInterpolatedAsync($"SELECT pg_advisory_xact_lock({lockKey})")` parameterizes `lockKey` safely (it is a `long`). (2) The advisory lock is held for the whole transaction (`_xact_` variant) and released on commit/rollback — no manual unlock. (3) Idempotency has two layers: the per-rule lock serializes concurrent callers, and the `existingDates` skip (read with `IgnoreQueryFilters(["SoftDelete"])`) makes re-runs and tombstoned occurrences no-ops. (4) `IgnoreQueryFilters(["SoftDelete"])` keeps the **Tenant** filter active, so cross-user rows are never read — consistent with the spec's named-filter split.
- [ ] **Run, expect PASS (whole Recurrence class).** Now all instance-dependent tests can pass. Run the full class:
  `dotnet test --filter "FullyQualifiedName~RecurrenceTests"` — expect every case PASS (including the P5-6/P5-7 cases that depend on generated instances: `UpdateRule_*`, `UpdateSeries_*`, `DeleteSeries_*`, `DeleteSeriesFuture_*`, `DetachInstance_*`).
- [ ] **Commit.**
```
feat(api): server-authoritative idempotent recurrence ensure-instances

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P5-9: Full-phase green + per-user isolation sweep for the five slices

Final verification gate: build clean and run every test class authored in this phase together, confirming all slices are green and per-user scoped (the isolation assertions are embedded in each class above: `FocusSessions`/`DailyPlans`/`Settings`/`Reports` each have a per-user test, and `GetRule_IsScopedPerUser` covers Recurrence).

- [ ] **Run the full phase build.** `dotnet build` — expect PASS, zero warnings introduced by this phase.
- [ ] **Run all five slice test classes + the generator unit tests.**
```
dotnet test --filter "FullyQualifiedName~OccurrenceGeneratorTests|FullyQualifiedName~FocusSessionsTests|FullyQualifiedName~DailyPlansTests|FullyQualifiedName~SettingsTests|FullyQualifiedName~ReportsTests|FullyQualifiedName~RecurrenceTests"
```
Expect: all PASS.
- [ ] **Confirm Program.cs wiring is complete.** Verify these five lines are present (mapping) and the five validator registrations exist:
```csharp
app.MapRecurrenceEndpoints();
app.MapFocusSessionsEndpoints();
app.MapDailyPlansEndpoints();
app.MapSettingsEndpoints();
app.MapReportsEndpoints();
```
- [ ] **Commit (phase close).**
```
test(api): green sweep for recurrence/focus/daily-plans/settings/reports slices

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

## Phase P6 — API cross-cutting: OpenAPI 3.1, CORS, /api/v1 versioning, packages/api-client generation

This phase wires the API's cross-cutting HTTP concerns and the TypeScript client generation. It assumes P0 (test harness, solution, `public partial class Program {}`), P1 (Infrastructure: `AppDbContext`, `ICurrentUser`, JWT auth registered with the `"Bearer"` authentication scheme, ProblemDetails), P3 (validation filter), and the feature slices (P2 Auth at `/api/v1/auth/*`, P4/P5 data slices at `/api/v1/tasks` etc.) already exist and are mapped. P6 only adds the OpenAPI document, CORS, the top-level `/api/v1` versioning group, HTTPS/HSTS hardening, and the `packages/api-client` npm package — it does **not** define new endpoints or entities.

**Files this phase creates:**
- `backend/src/Tmap.Api/Common/OpenApi/BearerSecuritySchemeTransformer.cs`
- `backend/src/Tmap.Api/Common/OpenApi/DocumentInfoTransformer.cs`
- `backend/src/Tmap.Api/Common/Cors/CorsOptions.cs`
- `backend/src/Tmap.Api/Common/Cors/CorsServiceCollectionExtensions.cs`
- `backend/tests/Tmap.Api.Tests/OpenApiDocumentTests.cs`
- `backend/tests/Tmap.Api.Tests/CorsTests.cs`
- `packages/api-client/package.json`
- `packages/api-client/tsconfig.json`
- `packages/api-client/src/client.ts`
- `packages/api-client/src/index.ts`
- `packages/api-client/openapi.json` (generated; committed lockfile of the contract)
- `packages/api-client/src/schema.d.ts` (generated by `openapi-typescript`)
- `packages/api-client/.gitignore`
- `packages/api-client/README.md`

**Files this phase modifies:**
- `backend/src/Tmap.Api/Program.cs` (register OpenAPI + CORS + HTTPS/HSTS + the top-level `/api/v1` group)
- `backend/src/Tmap.Api/Tmap.Api.csproj` (add `Microsoft.AspNetCore.OpenApi`; add the build-time OpenAPI document emit)
- `backend/src/Tmap.Api/appsettings.json` and `appsettings.Development.json` (Cors section)
- `package.json` (root workspaces — add `packages/*` glob + a top-level `gen:api-client` script)

**Contract-gap notes (read first):**
1. The SHARED CONTRACT says data endpoints `.RequireAuthorization()` and routes live under `/api/v1`. It does **not** name the top-level group variable. P6 introduces a local in `Program.cs` named `apiV1` (a `RouteGroupBuilder` for `MapGroup("/api/v1")`) and assumes each slice already exposes a `public static RouteGroupBuilder MapXxx(this IEndpointRouteBuilder app)` (or `MapXxxEndpoints`) that maps **relative** routes (e.g. `"auth"`, `"tasks"`). If the slices instead hard-code the absolute `"/api/v1/..."` prefix themselves, the `apiV1` group in Task P6-4 is a no-op wrapper and the slice calls stay as-is — either way every route resolves under `/api/v1`. This is a wiring convention, not a new shared symbol.
2. The JWT bearer auth scheme registered in P1 is assumed to use the scheme name `"Bearer"` (`JwtBearerDefaults.AuthenticationScheme`). The `BearerSecuritySchemeTransformer` keys off that exact name.
3. `.NET 10` ships `Microsoft.OpenApi` v2.x where security schemes are referenced via `IOpenApiSecurityScheme` / `OpenApiSecuritySchemeReference` (not the v1 `OpenApiSecurityScheme` reference object). Code below uses the v2 API.

---

### Task P6-1: Add OpenAPI package + the failing OpenAPI document test

- [ ] Add the OpenAPI package reference to `backend/src/Tmap.Api/Tmap.Api.csproj`. Inside the existing `<ItemGroup>` of `PackageReference`s add:
  ```xml
  <PackageReference Include="Microsoft.AspNetCore.OpenApi" Version="10.0.0" />
  ```
- [ ] Write the failing test `backend/tests/Tmap.Api.Tests/OpenApiDocumentTests.cs`. It uses the existing `IntegrationTestBase` (collection `"db"`) so it boots the real app + migrated Postgres, then fetches the OpenAPI JSON and asserts it is OpenAPI 3.1 and contains the auth + tasks paths and the Bearer scheme:
  ```csharp
  using System.Net;
  using System.Text.Json;
  using FluentAssertions;
  using Xunit;

  namespace Tmap.Api.Tests;

  [Collection("db")]
  public sealed class OpenApiDocumentTests : IntegrationTestBase
  {
      public OpenApiDocumentTests(PostgresFixture fixture) : base(fixture) { }

      private async Task<JsonDocument> GetOpenApiAsync()
      {
          var response = await Client.GetAsync("/openapi/v1.json");
          response.StatusCode.Should().Be(HttpStatusCode.OK);
          var stream = await response.Content.ReadAsStreamAsync();
          return await JsonDocument.ParseAsync(stream);
      }

      [Fact]
      public async Task Document_is_openapi_3_1()
      {
          using var doc = await GetOpenApiAsync();
          doc.RootElement.GetProperty("openapi").GetString()
              .Should().StartWith("3.1");
      }

      [Fact]
      public async Task Document_has_title_and_version()
      {
          using var doc = await GetOpenApiAsync();
          var info = doc.RootElement.GetProperty("info");
          info.GetProperty("title").GetString().Should().Be("TMap API");
          info.GetProperty("version").GetString().Should().Be("v1");
      }

      [Theory]
      [InlineData("/api/v1/auth/register")]
      [InlineData("/api/v1/auth/login")]
      [InlineData("/api/v1/auth/refresh")]
      [InlineData("/api/v1/auth/me")]
      [InlineData("/api/v1/tasks")]
      public async Task Document_contains_path(string path)
      {
          using var doc = await GetOpenApiAsync();
          doc.RootElement.GetProperty("paths")
              .TryGetProperty(path, out _)
              .Should().BeTrue($"the OpenAPI document should expose {path}");
      }

      [Fact]
      public async Task Document_defines_bearer_security_scheme()
      {
          using var doc = await GetOpenApiAsync();
          var scheme = doc.RootElement
              .GetProperty("components")
              .GetProperty("securitySchemes")
              .GetProperty("Bearer");
          scheme.GetProperty("type").GetString().Should().Be("http");
          scheme.GetProperty("scheme").GetString().Should().Be("bearer");
          scheme.GetProperty("bearerFormat").GetString().Should().Be("JWT");
      }

      [Fact]
      public async Task Protected_endpoint_carries_security_requirement()
      {
          using var doc = await GetOpenApiAsync();
          var get = doc.RootElement
              .GetProperty("paths")
              .GetProperty("/api/v1/tasks")
              .GetProperty("get");
          get.TryGetProperty("security", out var security).Should().BeTrue();
          security.GetArrayLength().Should().BeGreaterThan(0);
          security[0].TryGetProperty("Bearer", out _).Should().BeTrue();
      }
  }
  ```
- [ ] Run the test and confirm it FAILS. Because OpenAPI is not yet registered, `/openapi/v1.json` returns 404, so the first assertion fails with the exact message:
  ```
  Expected response.StatusCode to be HttpStatusCode.OK {value: 200}, but found HttpStatusCode.NotFound {value: 404}.
  ```
  Command:
  ```
  dotnet test --filter "FullyQualifiedName~OpenApiDocumentTests"
  ```

---

### Task P6-2: Implement the OpenAPI document transformers (Bearer scheme + Info)

- [ ] Create `backend/src/Tmap.Api/Common/OpenApi/BearerSecuritySchemeTransformer.cs`. It adds the JWT bearer scheme to `components.securitySchemes` and attaches a security requirement to every operation, but only if the `"Bearer"` authentication scheme is registered (it is, from P1):
  ```csharp
  using Microsoft.AspNetCore.Authentication;
  using Microsoft.AspNetCore.OpenApi;
  using Microsoft.OpenApi.Models;
  using Microsoft.OpenApi.Models.Interfaces;
  using Microsoft.OpenApi.Models.References;

  namespace Tmap.Api.Common.OpenApi;

  /// <summary>
  /// Adds the JWT bearer security scheme to the OpenAPI document and applies it
  /// to every operation, so generated clients know to send the Authorization header.
  /// </summary>
  internal sealed class BearerSecuritySchemeTransformer(
      IAuthenticationSchemeProvider authSchemeProvider) : IOpenApiDocumentTransformer
  {
      public async Task TransformAsync(
          OpenApiDocument document,
          OpenApiDocumentTransformerContext context,
          CancellationToken cancellationToken)
      {
          var schemes = await authSchemeProvider.GetAllSchemesAsync();
          if (schemes.All(s => s.Name != "Bearer"))
          {
              return;
          }

          document.Components ??= new OpenApiComponents();
          document.Components.SecuritySchemes ??=
              new Dictionary<string, IOpenApiSecurityScheme>();
          document.Components.SecuritySchemes["Bearer"] = new OpenApiSecurityScheme
          {
              Type = SecuritySchemeType.Http,
              Scheme = "bearer",
              BearerFormat = "JWT",
              In = ParameterLocation.Header,
              Description = "JWT access token. Format: \"Bearer {token}\".",
          };

          var requirement = new OpenApiSecurityRequirement
          {
              [new OpenApiSecuritySchemeReference("Bearer", document)] = [],
          };

          foreach (var pathItem in document.Paths.Values)
          {
              foreach (var operation in pathItem.Operations.Values)
              {
                  operation.Security ??= [];
                  operation.Security.Add(requirement);
              }
          }
      }
  }
  ```
- [ ] Create `backend/src/Tmap.Api/Common/OpenApi/DocumentInfoTransformer.cs` to set the document title/version/description deterministically (so the test's `"TMap API"` / `"v1"` assertions pass regardless of assembly metadata):
  ```csharp
  using Microsoft.AspNetCore.OpenApi;
  using Microsoft.OpenApi.Models;

  namespace Tmap.Api.Common.OpenApi;

  /// <summary>Sets stable Info (title/version/description) on the OpenAPI document.</summary>
  internal sealed class DocumentInfoTransformer : IOpenApiDocumentTransformer
  {
      public Task TransformAsync(
          OpenApiDocument document,
          OpenApiDocumentTransformerContext context,
          CancellationToken cancellationToken)
      {
          document.Info = new OpenApiInfo
          {
              Title = "TMap API",
              Version = "v1",
              Description =
                  "TMap backend API. JWT-authenticated daily planning and task management. "
                  + "All routes are under /api/v1; errors use RFC 9457 ProblemDetails.",
          };
          return Task.CompletedTask;
      }
  }
  ```
- [ ] Do not run tests yet — wiring happens in P6-3. (This step is pure code addition; it compiles but is not referenced until `AddOpenApi` registers the transformers.)

---

### Task P6-3: Wire `AddOpenApi` + `MapOpenApi` in Program.cs and make the OpenAPI test pass

- [ ] In `backend/src/Tmap.Api/Program.cs`, register the OpenAPI document with the two transformers. Add this in the service-registration section (before `builder.Build()`), importing the transformer namespace:
  ```csharp
  using Tmap.Api.Common.OpenApi;
  // ...
  builder.Services.AddOpenApi("v1", options =>
  {
      options.AddDocumentTransformer<DocumentInfoTransformer>();
      options.AddDocumentTransformer<BearerSecuritySchemeTransformer>();
  });
  ```
- [ ] In the middleware/endpoint section (after `var app = builder.Build();`), map the OpenAPI endpoint. Serve it in **all** environments (not just Development) so the test harness — which runs the default environment — and the build-time generator both reach it. The document name `"v1"` produces the route `/openapi/v1.json`:
  ```csharp
  app.MapOpenApi();
  ```
  > `MapOpenApi()` with no argument serves `/openapi/{documentName}.json`; with the document registered as `"v1"` the concrete path is `/openapi/v1.json`, matching the test.
- [ ] Run the OpenAPI test; it must now PASS:
  ```
  dotnet test --filter "FullyQualifiedName~OpenApiDocumentTests"
  ```
  Expected: all `OpenApiDocumentTests` facts/theories green. If `Document_contains_path` fails for `/api/v1/tasks`, the slices are not yet mapped under `/api/v1` — that is fixed structurally in P6-4; rerun after P6-4.
- [ ] Commit:
  ```
  git add backend/src/Tmap.Api/Tmap.Api.csproj backend/src/Tmap.Api/Common/OpenApi backend/src/Tmap.Api/Program.cs backend/tests/Tmap.Api.Tests/OpenApiDocumentTests.cs
  git commit -m "feat(api): emit OpenAPI 3.1 document with JWT bearer scheme

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
  ```

---

### Task P6-4: Enforce a single top-level `/api/v1` versioning group

- [ ] Write the failing test asserting versioning is structural — every mapped route lives under `/api/v1` and there is no un-versioned data path. Add to `backend/tests/Tmap.Api.Tests/OpenApiDocumentTests.cs`:
  ```csharp
      [Fact]
      public async Task All_paths_are_under_api_v1()
      {
          using var doc = await GetOpenApiAsync();
          foreach (var path in doc.RootElement.GetProperty("paths").EnumerateObject())
          {
              path.Name.Should().StartWith("/api/v1/",
                  "every API route must be under the /api/v1 version group");
          }
      }
  ```
- [ ] Run it. If any slice currently maps outside `/api/v1` (or the `/health` endpoint shows up in the document) this FAILS with a message like:
  ```
  Expected path.Name to start with "/api/v1/" because every API route must be under the /api/v1 version group, but "/health" does not.
  ```
- [ ] Fix in `Program.cs`: introduce one top-level group and hang every slice off it. Replace the per-slice `app.MapXxx(...)` calls with:
  ```csharp
  var apiV1 = app.MapGroup("/api/v1");

  apiV1.MapAuthEndpoints();
  apiV1.MapTasksEndpoints();
  apiV1.MapSubtasksEndpoints();
  apiV1.MapProjectsEndpoints();
  apiV1.MapNoteGroupsEndpoints();
  apiV1.MapNotesEndpoints();
  apiV1.MapRecurrenceEndpoints();
  apiV1.MapFocusSessionsEndpoints();
  apiV1.MapDailyPlansEndpoints();
  apiV1.MapReportsEndpoints();
  apiV1.MapSettingsEndpoints();
  ```
  > Each `MapXxxEndpoints` must map **relative** routes (e.g. `group.MapGroup("auth")` or `group.MapGet("tasks", ...)`). If a slice from an earlier phase hard-codes `"/api/v1/..."`, remove the `/api/v1` prefix from that slice so the route is not doubled — the prefix now belongs solely to `apiV1`. The exact slice method names come from P2/P4/P5; match their signatures.
- [ ] Exclude `/health` from the OpenAPI document so the all-paths test stays true. On the health endpoint mapping add `.ExcludeFromDescription()`:
  ```csharp
  app.MapHealthChecks("/health").ExcludeFromDescription();
  ```
  > If P0/P1 mapped `/health` differently (e.g. `app.MapGet("/health", ...)`), append `.ExcludeFromDescription()` to that call instead. The OpenAPI endpoint `/openapi/v1.json` is itself never listed in `paths`, so it needs no exclusion.
- [ ] Run both the new test and the path-presence theory; all PASS:
  ```
  dotnet test --filter "FullyQualifiedName~OpenApiDocumentTests"
  ```
- [ ] Commit:
  ```
  git add backend/src/Tmap.Api/Program.cs backend/tests/Tmap.Api.Tests/OpenApiDocumentTests.cs
  git commit -m "feat(api): route every slice through a single /api/v1 version group

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
  ```

---

### Task P6-5: CORS — config-bound per-environment origin allowlist (failing test first)

- [ ] Write the failing test `backend/tests/Tmap.Api.Tests/CorsTests.cs`. It proves (a) a named allowed origin gets reflected back with credentials allowed, and (b) a non-allowed origin gets **no** `Access-Control-Allow-Origin` header (so the browser blocks it). The `IntegrationTestBase` factory must be configurable to inject test origins — see the override step below:
  ```csharp
  using System.Net.Http;
  using FluentAssertions;
  using Xunit;

  namespace Tmap.Api.Tests;

  [Collection("db")]
  public sealed class CorsTests : IntegrationTestBase
  {
      public CorsTests(PostgresFixture fixture) : base(fixture) { }

      [Fact]
      public async Task Preflight_from_allowed_origin_is_reflected_with_credentials()
      {
          var request = new HttpRequestMessage(HttpMethod.Options, "/api/v1/auth/login");
          request.Headers.Add("Origin", "https://app.tmap.test");
          request.Headers.Add("Access-Control-Request-Method", "POST");
          request.Headers.Add("Access-Control-Request-Headers", "content-type,authorization");

          var response = await Client.SendAsync(request);

          response.Headers.GetValues("Access-Control-Allow-Origin")
              .Should().ContainSingle().Which.Should().Be("https://app.tmap.test");
          response.Headers.GetValues("Access-Control-Allow-Credentials")
              .Should().ContainSingle().Which.Should().Be("true");
      }

      [Fact]
      public async Task Preflight_from_disallowed_origin_has_no_allow_origin_header()
      {
          var request = new HttpRequestMessage(HttpMethod.Options, "/api/v1/auth/login");
          request.Headers.Add("Origin", "https://evil.example.com");
          request.Headers.Add("Access-Control-Request-Method", "POST");

          var response = await Client.SendAsync(request);

          response.Headers.Contains("Access-Control-Allow-Origin").Should().BeFalse();
      }
  }
  ```
- [ ] Make the test's allowed origin deterministic by overriding configuration in `WebApplicationFactory<Program>`. In `backend/tests/Tmap.Api.Tests/IntegrationTestBase.cs` (the factory that P0 created), in the existing `ConfigureWebHost`/`ConfigureAppConfiguration` hook, add the test CORS origin alongside the existing connection-string override:
  ```csharp
  builder.ConfigureAppConfiguration((_, config) =>
  {
      config.AddInMemoryCollection(new Dictionary<string, string?>
      {
          ["Cors:AllowedOrigins:0"] = "https://app.tmap.test",
      });
  });
  ```
  > Keep this additive — do not remove the connection-string override P0 already set. If P0 built the in-memory dictionary in one place, add the `Cors:AllowedOrigins:0` key to that same dictionary.
- [ ] Run the test; it FAILS because CORS is not yet configured. The disallowed-origin test may pass vacuously, but `Preflight_from_allowed_origin_is_reflected_with_credentials` fails with:
  ```
  Expected response to contain header "Access-Control-Allow-Origin", but it was not found.
  ```
  Command:
  ```
  dotnet test --filter "FullyQualifiedName~CorsTests"
  ```

---

### Task P6-6: Implement CORS options + extension and wire it (make CORS test pass)

- [ ] Create `backend/src/Tmap.Api/Common/Cors/CorsOptions.cs` — the strongly-typed config section:
  ```csharp
  namespace Tmap.Api.Common.Cors;

  /// <summary>
  /// Bound from the "Cors" configuration section. AllowedOrigins is an explicit
  /// per-environment allowlist; it must be non-empty in any environment that serves
  /// browser clients. Desktop clients send no Origin and are unaffected by CORS.
  /// </summary>
  public sealed class CorsOptions
  {
      public const string SectionName = "Cors";

      /// <summary>Exact origins (scheme+host+port) permitted to send credentialed requests.</summary>
      public string[] AllowedOrigins { get; init; } = [];
  }
  ```
- [ ] Create `backend/src/Tmap.Api/Common/Cors/CorsServiceCollectionExtensions.cs`. It binds the options and registers a single named policy. Credentials are allowed **only** because origins are explicitly named — it never combines `AllowAnyOrigin()` with `AllowCredentials()`:
  ```csharp
  using Microsoft.Extensions.Options;
  using Tmap.Api.Common.Cors;

  namespace Tmap.Api.Common.Cors;

  public static class CorsServiceCollectionExtensions
  {
      /// <summary>The single CORS policy name applied app-wide.</summary>
      public const string PolicyName = "TmapCors";

      public static IServiceCollection AddTmapCors(
          this IServiceCollection services,
          IConfiguration configuration)
      {
          services
              .AddOptions<CorsOptions>()
              .Bind(configuration.GetSection(CorsOptions.SectionName))
              .ValidateOnStart();

          services.AddCors(options =>
          {
              options.AddPolicy(PolicyName, policy =>
              {
                  var corsOptions = configuration
                      .GetSection(CorsOptions.SectionName)
                      .Get<CorsOptions>() ?? new CorsOptions();

                  if (corsOptions.AllowedOrigins.Length > 0)
                  {
                      policy
                          .WithOrigins(corsOptions.AllowedOrigins)
                          .AllowAnyHeader()
                          .AllowAnyMethod()
                          .AllowCredentials();
                  }
                  // No named origins => no cross-origin browser access is granted.
                  // Desktop clients send no Origin header and are never gated by CORS.
              });
          });

          return services;
      }
  }
  ```
- [ ] In `Program.cs`, register and apply CORS. In service registration:
  ```csharp
  using Tmap.Api.Common.Cors;
  // ...
  builder.Services.AddTmapCors(builder.Configuration);
  ```
  In the middleware pipeline, place `UseCors` **after** routing and **before** authentication/authorization:
  ```csharp
  app.UseCors(CorsServiceCollectionExtensions.PolicyName);
  ```
  > Pipeline order in `Program.cs` should read: `UseHsts`/`UseHttpsRedirection` (P6-8) → `UseRouting` (implicit) → `UseCors` → `UseAuthentication` → `UseAuthorization` → endpoint mappings. Applying the policy globally via `UseCors(name)` means every endpoint, including the OPTIONS preflight, is covered without per-endpoint `.RequireCors(...)`.
- [ ] Add the `Cors` section to `backend/src/Tmap.Api/appsettings.json` with an empty allowlist (safe default — no browser CORS in prod until origins are configured by the deployer):
  ```json
  "Cors": {
    "AllowedOrigins": []
  }
  ```
- [ ] Add a dev allowlist to `backend/src/Tmap.Api/appsettings.Development.json` (Vite dev server origins for the future web build):
  ```json
  "Cors": {
    "AllowedOrigins": [
      "http://localhost:5173",
      "http://127.0.0.1:5173"
    ]
  }
  ```
- [ ] Run the CORS test; both facts PASS:
  ```
  dotnet test --filter "FullyQualifiedName~CorsTests"
  ```
- [ ] Commit:
  ```
  git add backend/src/Tmap.Api/Common/Cors backend/src/Tmap.Api/Program.cs backend/src/Tmap.Api/appsettings.json backend/src/Tmap.Api/appsettings.Development.json backend/tests/Tmap.Api.Tests/CorsTests.cs backend/tests/Tmap.Api.Tests/IntegrationTestBase.cs
  git commit -m "feat(api): config-bound CORS allowlist with credentials for named origins only

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
  ```

---

### Task P6-7: Guard against the AllowAnyOrigin+credentials misconfiguration

- [ ] Add a regression test that an empty allowlist grants **no** cross-origin access (i.e. the policy never silently degrades into `AllowAnyOrigin`). This protects against a future edit that "fixes" CORS by widening it. Add to `backend/tests/Tmap.Api.Tests/CorsTests.cs` a nested factory that overrides the allowlist to empty:
  ```csharp
      [Fact]
      public async Task Empty_allowlist_grants_no_cross_origin_access()
      {
          using var factory = NewFactoryWithConfig(new Dictionary<string, string?>
          {
              // Wipe the allowlist seeded by IntegrationTestBase.
              ["Cors:AllowedOrigins:0"] = null,
          });
          using var client = factory.CreateClient();

          var request = new HttpRequestMessage(HttpMethod.Options, "/api/v1/auth/login");
          request.Headers.Add("Origin", "https://app.tmap.test");
          request.Headers.Add("Access-Control-Request-Method", "POST");

          var response = await client.SendAsync(request);

          response.Headers.Contains("Access-Control-Allow-Origin").Should().BeFalse();
      }
  ```
- [ ] Add the `NewFactoryWithConfig` helper to `IntegrationTestBase` (it builds a derived `WebApplicationFactory<Program>` that layers extra in-memory config on top of the base test config, pointed at the same Postgres fixture):
  ```csharp
  protected WebApplicationFactory<Program> NewFactoryWithConfig(
      IDictionary<string, string?> overrides) =>
      Factory.WithWebHostBuilder(builder =>
          builder.ConfigureAppConfiguration((_, config) =>
              config.AddInMemoryCollection(overrides)));
  ```
  > `Factory` is the base `WebApplicationFactory<Program>` field P0 exposes. Setting `Cors:AllowedOrigins:0` to `null` removes the only seeded origin, so the bound `AllowedOrigins` is empty and the policy adds no origins. If P0 named the field differently, use that name.
- [ ] Run the test; it PASSES (empty allowlist => no `Access-Control-Allow-Origin`). Then run the full `CorsTests` class to confirm no regression:
  ```
  dotnet test --filter "FullyQualifiedName~CorsTests"
  ```
- [ ] Commit:
  ```
  git add backend/tests/Tmap.Api.Tests/CorsTests.cs backend/tests/Tmap.Api.Tests/IntegrationTestBase.cs
  git commit -m "test(api): assert empty CORS allowlist grants no cross-origin access

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
  ```

---

### Task P6-8: HTTPS redirection + HSTS for non-dev environments

- [ ] Write a failing test asserting HSTS is enabled outside Development. Create `backend/tests/Tmap.Api.Tests/TransportSecurityTests.cs`. It boots a factory in the `Production` environment over HTTPS and asserts a `Strict-Transport-Security` header is present; and asserts that in the default/test environment HSTS is **absent** (so local dev/tests are not forced to HTTPS):
  ```csharp
  using System.Net.Http;
  using FluentAssertions;
  using Microsoft.AspNetCore.Hosting;
  using Microsoft.Extensions.Hosting;
  using Xunit;

  namespace Tmap.Api.Tests;

  [Collection("db")]
  public sealed class TransportSecurityTests : IntegrationTestBase
  {
      public TransportSecurityTests(PostgresFixture fixture) : base(fixture) { }

      [Fact]
      public async Task Production_responses_include_hsts_header()
      {
          using var factory = Factory.WithWebHostBuilder(b => b.UseEnvironment(Environments.Production));
          using var client = factory.CreateClient(new WebApplicationFactoryClientOptions
          {
              BaseAddress = new Uri("https://localhost"),
          });

          var response = await client.GetAsync("/openapi/v1.json");

          response.Headers.Contains("Strict-Transport-Security").Should().BeTrue();
      }

      [Fact]
      public async Task Development_responses_omit_hsts_header()
      {
          // Default test environment is not Production -> HSTS middleware not added.
          var response = await Client.GetAsync("/openapi/v1.json");
          response.Headers.Contains("Strict-Transport-Security").Should().BeFalse();
      }
  }
  ```
  > `WebApplicationFactoryClientOptions` lives in `Microsoft.AspNetCore.Mvc.Testing`. The HSTS header is only emitted by Kestrel over an HTTPS request, which the in-process test server treats by the `https://` base address.
- [ ] Run it and confirm `Production_responses_include_hsts_header` FAILS with:
  ```
  Expected response.Headers to contain "Strict-Transport-Security", but found ...
  ```
  Command:
  ```
  dotnet test --filter "FullyQualifiedName~TransportSecurityTests"
  ```
- [ ] In `Program.cs`, add HSTS + HTTPS redirection guarded by environment, as the **first** middleware after `var app = builder.Build();` (before `UseCors`):
  ```csharp
  if (!app.Environment.IsDevelopment())
  {
      app.UseHsts();
      app.UseHttpsRedirection();
  }
  ```
  > Guarding with `IsDevelopment()` keeps the test/default and Development environments HTTP so the existing integration tests (and the OpenAPI/CORS tests above, which use the default `http://localhost`) keep working. `UseHsts` adds the `Strict-Transport-Security` header; `UseHttpsRedirection` 307-redirects HTTP→HTTPS.
- [ ] Run the test; both facts PASS.
- [ ] Run the full backend test suite to confirm HTTPS redirection did not break other slices (they run in the default environment, so redirection is off):
  ```
  dotnet test
  ```
- [ ] Commit:
  ```
  git add backend/src/Tmap.Api/Program.cs backend/tests/Tmap.Api.Tests/TransportSecurityTests.cs
  git commit -m "feat(api): enable HSTS + HTTPS redirection outside Development

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
  ```

---

### Task P6-9: Pick the build-time OpenAPI emit approach and wire it into the csproj

> **Chosen approach (one, concrete):** `Microsoft.Extensions.ApiDescription.Server`. It writes the OpenAPI JSON to disk during `dotnet build` with **no running server and no Postgres**, which is exactly what the TS generator needs in CI. (The alternative — `dotnet run -- --emit` via the `--openapi` CLI — requires the app to start, which would touch the DB; rejected for the generator path.)

- [ ] Add the build-time emit packages/properties to `backend/src/Tmap.Api/Tmap.Api.csproj`. Add the package reference (build-only) and the output properties so the document lands in `packages/api-client/openapi.json` on every build:
  ```xml
  <ItemGroup>
    <PackageReference Include="Microsoft.Extensions.ApiDescription.Server" Version="10.0.0">
      <PrivateAssets>all</PrivateAssets>
      <IncludeAssets>runtime; build; native; contentfiles; analyzers; buildtransitive</IncludeAssets>
    </PackageReference>
  </ItemGroup>

  <PropertyGroup>
    <OpenApiDocumentsDirectory>$(MSBuildProjectDirectory)/../../../packages/api-client</OpenApiDocumentsDirectory>
    <OpenApiGenerateDocuments>true</OpenApiGenerateDocuments>
    <OpenApiGenerateDocumentsOnBuild>true</OpenApiGenerateDocumentsOnBuild>
  </PropertyGroup>
  ```
  > The package emits `<AssemblyName>_<documentName>.json` by default (e.g. `Tmap.Api_v1.json`). To get the exact filename `openapi.json` the TS package expects, add the explicit file-name property:
  ```xml
  <PropertyGroup>
    <OpenApiDocumentName>v1</OpenApiDocumentName>
  </PropertyGroup>
  ```
  and rename in the npm `gen` script (P6-11) rather than fighting MSBuild naming — the script copies/normalizes `Tmap.Api_v1.json` → `openapi.json`. (Keeping the rename in the npm script is simpler and avoids a custom MSBuild target.)
- [ ] Verify the build emits the document. Run a clean build of the API project and confirm the file appears:
  ```
  dotnet build backend/src/Tmap.Api/Tmap.Api.csproj
  ```
  Then confirm `packages/api-client/Tmap.Api_v1.json` exists and starts with `{"openapi":"3.1`. (If the directory does not yet exist, MSBuild creates it; the `packages/api-client` files are added in P6-10.)
- [ ] Commit (the emitted JSON itself is git-ignored via P6-10's `.gitignore`, except the committed `openapi.json` contract — see P6-12):
  ```
  git add backend/src/Tmap.Api/Tmap.Api.csproj
  git commit -m "chore(api): emit OpenAPI document at build time via ApiDescription.Server

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
  ```

---

### Task P6-10: Scaffold the `packages/api-client` npm package

- [ ] Create `packages/api-client/package.json`. It depends on `openapi-fetch` at runtime and `openapi-typescript` + `typescript` as dev deps, and exposes `gen`, `typecheck`, and `build` scripts. The `gen` script copies the MSBuild-emitted document to `openapi.json`, then runs `openapi-typescript` to produce `src/schema.d.ts`:
  ```json
  {
    "name": "@tmap/api-client",
    "version": "0.1.0",
    "private": true,
    "type": "module",
    "main": "./src/index.ts",
    "types": "./src/index.ts",
    "exports": {
      ".": {
        "types": "./src/index.ts",
        "import": "./src/index.ts"
      }
    },
    "scripts": {
      "emit:openapi": "dotnet build ../../backend/src/Tmap.Api/Tmap.Api.csproj -p:OpenApiGenerateDocumentsOnBuild=true",
      "copy:openapi": "node ./scripts/copy-openapi.mjs",
      "gen:types": "openapi-typescript ./openapi.json -o ./src/schema.d.ts",
      "gen": "npm run emit:openapi && npm run copy:openapi && npm run gen:types",
      "typecheck": "tsc --noEmit"
    },
    "dependencies": {
      "openapi-fetch": "^0.13.0"
    },
    "devDependencies": {
      "openapi-typescript": "^7.4.0",
      "typescript": "^5.6.0"
    }
  }
  ```
- [ ] Create the copy/normalize helper `packages/api-client/scripts/copy-openapi.mjs` (renames the MSBuild output `Tmap.Api_v1.json` → `openapi.json` in this package, and fails loudly if the emit step did not run):
  ```js
  import { copyFileSync, existsSync } from 'node:fs';
  import { fileURLToPath } from 'node:url';
  import { dirname, join } from 'node:path';

  const here = dirname(fileURLToPath(import.meta.url));
  const pkgRoot = join(here, '..');
  const emitted = join(pkgRoot, 'Tmap.Api_v1.json');
  const target = join(pkgRoot, 'openapi.json');

  if (!existsSync(emitted)) {
    console.error(
      `OpenAPI document not found at ${emitted}. ` +
        `Run "npm run emit:openapi" (or "dotnet build") first.`,
    );
    process.exit(1);
  }

  copyFileSync(emitted, target);
  console.log(`Copied ${emitted} -> ${target}`);
  ```
- [ ] Create `packages/api-client/tsconfig.json` (strict, no emit — types-only package consumed by source):
  ```json
  {
    "compilerOptions": {
      "target": "ES2022",
      "module": "ESNext",
      "moduleResolution": "Bundler",
      "lib": ["ES2022", "DOM"],
      "strict": true,
      "noEmit": true,
      "esModuleInterop": true,
      "skipLibCheck": true,
      "types": []
    },
    "include": ["src/**/*.ts", "src/**/*.d.ts"]
  }
  ```
- [ ] Create the typed client `packages/api-client/src/client.ts` using `openapi-fetch` over the generated `schema.d.ts` types:
  ```ts
  import createClient, { type Client } from 'openapi-fetch';
  import type { paths } from './schema';

  export interface TmapClientOptions {
    /** Base URL of the API, e.g. "https://api.tmap.app". */
    baseUrl: string;
    /** Returns the current JWT access token, or null when unauthenticated. */
    getAccessToken?: () => string | null | undefined;
    /** Forwarded to fetch; web clients pass "include" to send the refresh cookie. */
    credentials?: RequestCredentials;
  }

  export type TmapClient = Client<paths>;

  /**
   * Creates a typed TMap API client. Attaches the Bearer access token (if any)
   * to every request via a middleware hook.
   */
  export function createTmapClient(options: TmapClientOptions): TmapClient {
    const client = createClient<paths>({
      baseUrl: options.baseUrl,
      credentials: options.credentials,
    });

    if (options.getAccessToken) {
      client.use({
        onRequest({ request }) {
          const token = options.getAccessToken?.();
          if (token) {
            request.headers.set('Authorization', `Bearer ${token}`);
          }
          return request;
        },
      });
    }

    return client;
  }
  ```
- [ ] Create the barrel `packages/api-client/src/index.ts`:
  ```ts
  export { createTmapClient } from './client';
  export type { TmapClient, TmapClientOptions } from './client';
  export type { paths, components, operations } from './schema';
  ```
- [ ] Create `packages/api-client/.gitignore` (ignore the raw MSBuild emit but keep the normalized contract `openapi.json` and the generated `schema.d.ts` committed for offline CI typecheck):
  ```gitignore
  # Raw MSBuild emit (renamed to openapi.json by scripts/copy-openapi.mjs)
  Tmap.Api_*.json
  node_modules/
  ```
- [ ] Create `packages/api-client/README.md`:
  ```markdown
  # @tmap/api-client

  Typed TypeScript client for the TMap API. Types are generated from the backend's
  OpenAPI 3.1 document with `openapi-typescript`; runtime calls use `openapi-fetch`.

  ## Regenerate after backend API changes

  ```bash
  npm run gen --workspace @tmap/api-client
  ```

  This builds `backend/src/Tmap.Api` (emitting `Tmap.Api_v1.json` via
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
  ```
- [ ] Register the workspace in the root `package.json`. Add (or extend) the `workspaces` array and a convenience script. Edit `C:/Users/aboab/Desktop/Projects/sunsama clone/package.json` to include:
  ```json
  "workspaces": [
    "apps/*",
    "packages/*"
  ],
  "scripts": {
    "gen:api-client": "npm run gen --workspace @tmap/api-client"
  }
  ```
  > Merge with the existing `scripts`; do not drop the desktop app's existing scripts. `apps/*` is listed because the final phase moves the Electron app to `apps/desktop`; if that move has not happened yet the glob simply matches nothing.
- [ ] Commit the package skeleton (without generated artifacts yet):
  ```
  git add packages/api-client/package.json packages/api-client/tsconfig.json packages/api-client/src/client.ts packages/api-client/src/index.ts packages/api-client/scripts/copy-openapi.mjs packages/api-client/.gitignore packages/api-client/README.md package.json
  git commit -m "feat(api-client): scaffold @tmap/api-client (openapi-typescript + openapi-fetch)

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
  ```

---

### Task P6-11: Generate types and prove the client compiles (`tsc --noEmit`)

- [ ] Install workspace dependencies from the repo root so `openapi-typescript`, `openapi-fetch`, and `typescript` resolve for the new package:
  ```
  npm install
  ```
- [ ] Generate the OpenAPI document, copy it, and produce the types in one shot:
  ```
  npm run gen --workspace @tmap/api-client
  ```
  Expected: `dotnet build` emits `packages/api-client/Tmap.Api_v1.json`; `copy-openapi.mjs` writes `packages/api-client/openapi.json`; `openapi-typescript` writes `packages/api-client/src/schema.d.ts` containing a `paths` interface with members for `"/api/v1/tasks"`, `"/api/v1/auth/login"`, etc.
- [ ] Run the typecheck — this is the phase's "client compiles" gate (acceptance criterion #4):
  ```
  npm run typecheck --workspace @tmap/api-client
  ```
  Expected: `tsc --noEmit` exits 0 with no errors. If `src/client.ts` references a `paths` member that does not exist, the generation step is stale — rerun `npm run gen`. If `openapi-fetch`'s `Client<paths>` type rejects `paths`, the emitted document is not 3.1-clean; inspect `openapi.json`'s `"openapi"` field (must be `3.1.x`).
- [ ] Commit the generated contract + types (kept in-repo so CI can typecheck without a build, and so the contract is reviewable in diffs):
  ```
  git add packages/api-client/openapi.json packages/api-client/src/schema.d.ts
  git commit -m "chore(api-client): generate schema types from OpenAPI 3.1 document

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
  ```

---

### Task P6-12: End-to-end verification of the phase

- [ ] Run the full backend test suite to confirm OpenAPI, versioning, CORS, and transport-security work together and nothing regressed:
  ```
  dotnet test
  ```
  Expected: all tests green, including `OpenApiDocumentTests`, `CorsTests`, `TransportSecurityTests`, plus every prior slice test (which run in the default environment with HTTPS redirection off and CORS limited to the seeded test origin).
- [ ] Re-run the client generation + typecheck from a clean state to prove the build-time contract → TS pipeline is reproducible:
  ```
  npm run gen --workspace @tmap/api-client
  npm run typecheck --workspace @tmap/api-client
  ```
  Expected: both exit 0; `git diff -- packages/api-client/openapi.json packages/api-client/src/schema.d.ts` shows no changes (the committed contract matches a fresh emit).
- [ ] Run formatting so the new C# matches the repo's Prettier/`dotnet format` conventions (per the shared contract's conventions):
  ```
  dotnet format backend/Tmap.sln
  ```
- [ ] Final commit if formatting changed anything:
  ```
  git add -A
  git commit -m "chore: format P6 cross-cutting code

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
  ```

---

## Phase P7 — Monorepo move: relocate Electron app to `apps/desktop`

**Contract gap note (read first):** The shared contract specifies the JS workspace uses **npm workspaces** with members `apps/desktop`, `packages/api-client`, and a root `package.json`. It does **not** pin: (a) the exact `extraResources.from` path for `sql-wasm.wasm` after npm hoists `node_modules` to the monorepo root, or (b) whether `vitest`/`vitest.config.ts` survive the move (the desktop app has a `test`/`test:run` script and a `vitest.config.ts`, but CLAUDE.md says "No test framework is configured"). This phase **preserves** the existing desktop scripts/config verbatim (only relocating + repathing) and resolves the wasm path explicitly below; no new shared symbol is invented.

**Why this phase runs last:** This is the FINAL phase and must run **only after all backend phases (P0–P6) are green**. The move touches only file *locations* and *build config* (zero app logic), so isolating it after the backend is verified keeps a broken move trivially revertible (`git revert` of one commit) without entangling backend work.

This phase **creates**: root `/package.json` (npm workspaces), root `/.gitignore` (updated), `apps/desktop/.gitignore`. It **moves** (via `git mv`, preserving history) into `apps/desktop/`: `electron/`, `src/`, `public/`, `build/`, `index.html`, `package.json`, `vite.config.ts`, `vitest.config.ts`, `tsconfig.json`, `tsconfig.electron.json`, `tailwind.config.js`, `postcss.config.js`, `.prettierrc`, `.prettierignore`, `test-electron.js`. It **modifies** after the move: `apps/desktop/package.json` (`build.extraResources.from` wasm path), and leaves `vite.config.ts`/`tsconfig*.json`/the `@/` alias **path-stable** (they use `__dirname`/relative roots that survive the move) — verified, not assumed. `apps/desktop/electron/main.ts` paths are confirmed `__dirname`-relative and need **no change**.

---

### Task P7-1: Snapshot the pre-move build/run as the regression baseline

This phase has no unit-test framework for build config, so the "failing test → passing test" loop is expressed as **executable build/run gates**. First, capture the current passing state from the repo root so we can prove the move preserves it.

- [ ] Verify clean working tree before starting (this phase must begin from a committed state):
  ```bash
  git -C "C:/Users/aboab/Desktop/Projects/sunsama clone" status --porcelain
  ```
  Expect: **empty output** (clean tree). If not empty, stop and commit/stash unrelated work first.
- [ ] Confirm backend phases are green (gate for running this phase). From repo root:
  ```bash
  dotnet test backend/Tmap.sln
  ```
  Expect: **all tests pass** (`Passed!` summary, exit code 0). If backend is red, **STOP** — do not perform the move.
- [ ] Establish the baseline build still works **at the current (pre-move) root**:
  ```bash
  npm run build
  ```
  Expect: **PASS** — `tsc && vite build && tsc -p tsconfig.electron.json` completes, producing `dist/` and `dist-electron/`. Record that `dist/index.html`, `dist/assets/*`, `dist-electron/main.js`, and `dist-electron/preload.js` exist:
  ```bash
  ls "C:/Users/aboab/Desktop/Projects/sunsama clone/dist/index.html" "C:/Users/aboab/Desktop/Projects/sunsama clone/dist-electron/main.js" "C:/Users/aboab/Desktop/Projects/sunsama clone/dist-electron/preload.js"
  ```
  Expect: all three paths listed. This is the regression target the post-move build must reproduce.
- [ ] Clean build artifacts so they are not accidentally moved or committed (they are gitignored, but `git mv` of a folder is cleaner without them):
  ```bash
  git -C "C:/Users/aboab/Desktop/Projects/sunsama clone" clean -fdX -- dist dist-electron out
  ```
  Expect: removes `dist/`, `dist-electron/`, `out/` if present (only ignored files; `-X` restricts to ignored).
- [ ] Commit nothing yet (baseline is the existing HEAD). Proceed to the move.

---

### Task P7-2: Create the `apps/desktop` directory and `git mv` every desktop file (preserve history)

Relocate the entire Electron+React app into `apps/desktop/`. Use `git mv` so file history is preserved. The `backend/`, `packages/`, and `docker-compose.yml` from prior phases stay at the root.

- [ ] Create the target directory:
  ```bash
  mkdir -p "C:/Users/aboab/Desktop/Projects/sunsama clone/apps/desktop"
  ```
- [ ] Move the source folders (history-preserving). Run each from the repo root:
  ```bash
  cd "C:/Users/aboab/Desktop/Projects/sunsama clone"
  git mv electron apps/desktop/electron
  git mv src apps/desktop/src
  git mv public apps/desktop/public
  git mv build apps/desktop/build
  ```
- [ ] Move the desktop config + entry files:
  ```bash
  cd "C:/Users/aboab/Desktop/Projects/sunsama clone"
  git mv index.html apps/desktop/index.html
  git mv package.json apps/desktop/package.json
  git mv vite.config.ts apps/desktop/vite.config.ts
  git mv vitest.config.ts apps/desktop/vitest.config.ts
  git mv tsconfig.json apps/desktop/tsconfig.json
  git mv tsconfig.electron.json apps/desktop/tsconfig.electron.json
  git mv tailwind.config.js apps/desktop/tailwind.config.js
  git mv postcss.config.js apps/desktop/postcss.config.js
  git mv test-electron.js apps/desktop/test-electron.js
  ```
- [ ] Move the desktop-scoped Prettier config files (these become the desktop workspace's own config; the root will not re-declare them):
  ```bash
  cd "C:/Users/aboab/Desktop/Projects/sunsama clone"
  git mv .prettierrc apps/desktop/.prettierrc
  git mv .prettierignore apps/desktop/.prettierignore
  ```
- [ ] **Do NOT** move `package-lock.json`, `.gitignore`, `CLAUDE.md`, `docs/`, `backend/`, `packages/`, or `docker-compose.yml` — those stay at the root (the root `package-lock.json` is regenerated by the workspace `npm install` in P7-7).
- [ ] Verify the move via git (expect renames `R`, not delete+add):
  ```bash
  git -C "C:/Users/aboab/Desktop/Projects/sunsama clone" status --short
  ```
  Expect: a list of `R  electron/... -> apps/desktop/electron/...`, `R  package.json -> apps/desktop/package.json`, etc. No `D`/`??` for these tracked files (renames preserve history).
- [ ] Confirm `node_modules/` was **not** moved (it stays at root and is recreated by the workspace install):
  ```bash
  ls "C:/Users/aboab/Desktop/Projects/sunsama clone/apps/desktop" | grep -i node_modules || echo "OK: no node_modules in apps/desktop"
  ```
  Expect: `OK: no node_modules in apps/desktop`.

---

### Task P7-3: Verify path-stable configs need NO change, and document why

Before editing anything, prove which moved configs are already correct because they use `__dirname`/relative roots. This is the "expect FAIL first" check: at this point the build **will** be broken at the new location only by the missing root workspace wiring (P7-5) and the wasm path (P7-4) — **not** by these files.

- [ ] Confirm `apps/desktop/vite.config.ts` is path-stable. It uses `path.resolve(__dirname, './src')` for the `@` alias and relative `outDir: 'dist'` / `outDir: 'dist-electron'` and relative entries `electron/main.ts` / `electron/preload.ts`. Since `__dirname` and Vite's `root` both become `apps/desktop/`, **every path still resolves**. Read it to confirm no absolute or `../`-escaping paths:
  ```bash
  git -C "C:/Users/aboab/Desktop/Projects/sunsama clone" show :apps/desktop/vite.config.ts
  ```
  Expect: alias `'@': path.resolve(__dirname, './src')`, entries `'electron/main.ts'`/`'electron/preload.ts'`, outDirs `'dist-electron'` and `'dist'` — all relative. **No edit required.**
- [ ] Confirm `apps/desktop/vitest.config.ts` is path-stable: `path.resolve(__dirname, './src')` + `include: ['src/**/*.test.ts', ...]`. Both resolve under `apps/desktop/`. **No edit required.**
- [ ] Confirm `apps/desktop/tsconfig.json` is path-stable: `outDir: "./dist"`, `rootDir: "./src"`, `paths: { "@/*": ["./src/*"] }`, `include: ["src/**/*", ...]`, reference `"./tsconfig.electron.json"`. All `./`-relative to the tsconfig's own folder. **No edit required.**
- [ ] Confirm `apps/desktop/tsconfig.electron.json` is path-stable: `outDir: "./dist-electron"`, `rootDir: "./electron"`, `include: ["electron/**/*"]`. All relative. **No edit required.**
- [ ] Confirm `apps/desktop/tailwind.config.js` is path-stable: `content: ['./index.html', './src/**/*.{js,ts,jsx,tsx}']` — relative to the config's folder. **No edit required.**
- [ ] Confirm `apps/desktop/index.html` is path-stable: `<script type="module" src="/src/main.tsx">`. The `/`-rooted path is resolved by Vite against its `root` (= `apps/desktop/`), so it points at `apps/desktop/src/main.tsx`. **No edit required.**
- [ ] Confirm `apps/desktop/electron/main.ts` runtime paths are path-stable: all use `path.join(__dirname, '../public/...')`, `'../dist/index.html'`, `'../build/icon.png'`, `'preload.js'`. `__dirname` at runtime is the compiled `apps/desktop/dist-electron/`, so `../public`, `../dist`, `../build` resolve inside `apps/desktop/`. **No edit required** (verified — do not touch app logic).
- [ ] Result of this task: only **two** things actually break and need fixing — the **root workspace wiring** (P7-5, new file) and the **electron-builder wasm `extraResources.from`** (P7-4, because npm hoists `node_modules` to the monorepo root). Everything else is confirmed correct by inspection above.

---

### Task P7-4: Fix the `sql-wasm.wasm` `extraResources.from` path in the desktop `package.json`

**Failing-test framing:** With npm workspaces, `npm install` hoists `sql.js` to the **monorepo root** `node_modules/`, so the desktop `package.json`'s `extraResources.from` of `node_modules/sql.js/dist/sql-wasm.wasm` (resolved relative to `apps/desktop/`) will **not exist** → `electron-builder` packaging would fail to copy the wasm. Fix `from` to point at the hoisted root location via `../../node_modules/...`. (electron-builder resolves `from` relative to `directories.app`, which defaults to the project dir = `apps/desktop/`; `../../` reaches the monorepo root.)

- [ ] Read the moved desktop manifest to get exact current text:
  ```bash
  git -C "C:/Users/aboab/Desktop/Projects/sunsama clone" show :apps/desktop/package.json
  ```
- [ ] Edit `apps/desktop/package.json` — change ONLY the `extraResources[0].from` value. Apply this exact replacement:

  **Old:**
  ```json
      "extraResources": [
        {
          "from": "node_modules/sql.js/dist/sql-wasm.wasm",
          "to": "sql-wasm.wasm"
        }
      ]
  ```
  **New:**
  ```json
      "extraResources": [
        {
          "from": "../../node_modules/sql.js/dist/sql-wasm.wasm",
          "to": "sql-wasm.wasm"
        }
      ]
  ```
- [ ] Add a `"private": true` is already present — confirm the desktop manifest keeps `"name": "tmap"`, `"version": "1.0.0"`, `"main": "./dist-electron/main.js"`, all `scripts`, `dependencies`, `devDependencies`, and the full `build` block **unchanged except** the one `from` line above. Do not alter `directories.output: "out"`, `files: ["dist/**/*", "dist-electron/**/*"]`, `win.icon: "build/icon.ico"`, or `nsis` — those are all relative to `apps/desktop/` and remain correct.
- [ ] The desktop manifest still uses `npm run format` with glob `"**/*.{ts,tsx,css,json}"` and `.prettierignore` — both moved into `apps/desktop/`, so formatting scopes to the workspace. **No change.**

---

### Task P7-5: Create the root `package.json` declaring npm workspaces

**Failing-test framing:** After the move there is no root `package.json`, so `npm install` at the repo root has no workspace definition → `apps/desktop` is not a workspace member and dependencies won't hoist/install for it. Create the root manifest to register `apps/*` and `packages/*` as workspaces.

- [ ] Create `C:/Users/aboab/Desktop/Projects/sunsama clone/package.json` with this exact content:
  ```json
  {
    "name": "tmap-monorepo",
    "version": "1.0.0",
    "private": true,
    "description": "TMap monorepo — Electron desktop app, .NET backend API, and TS API client",
    "workspaces": [
      "apps/*",
      "packages/*"
    ],
    "scripts": {
      "dev": "npm run dev --workspace apps/desktop",
      "build": "npm run build --workspace apps/desktop",
      "package": "npm run package --workspace apps/desktop",
      "format": "npm run format --workspace apps/desktop"
    }
  }
  ```
- [ ] Rationale check (no action): `workspaces: ["apps/*", "packages/*"]` makes `apps/desktop` (name `tmap`) and `packages/api-client` (created in an earlier phase) workspace members. Root passthrough scripts let `npm run build` at the root delegate to the desktop workspace, matching acceptance verification commands. The root name is `tmap-monorepo` to avoid colliding with the desktop workspace's `tmap` name.
- [ ] If `packages/api-client` does not yet contain a `package.json` (it should, from the client-generation phase), `npm install` will warn but still install `apps/desktop`. Confirm it exists:
  ```bash
  ls "C:/Users/aboab/Desktop/Projects/sunsama clone/packages/api-client/package.json"
  ```
  Expect: the file is listed (created by the earlier api-client phase). If missing, that is a prior-phase gap — note it, but it does not block the desktop build.

---

### Task P7-6: Update the root `.gitignore` for hoisted/per-workspace build artifacts and add a desktop `.gitignore`

The root `.gitignore` currently ignores `dist/`, `dist-electron/`, `out/` at the root. After the move those artifacts live under `apps/desktop/`. Make ignores match both monorepo-root `node_modules` (hoisted) and per-workspace artifacts.

- [ ] Read the current root `.gitignore`:
  ```bash
  git -C "C:/Users/aboab/Desktop/Projects/sunsama clone" show :.gitignore
  ```
- [ ] Replace the full contents of `C:/Users/aboab/Desktop/Projects/sunsama clone/.gitignore` with:
  ```gitignore
  # dependencies (npm workspaces hoist to the monorepo root)
  node_modules/

  # desktop (Electron + Vite) build artifacts — now under apps/desktop
  dist/
  dist-electron/
  out/
  **/dist/
  **/dist-electron/
  **/out/

  # generated TS client output (if emitted into the repo)
  packages/api-client/dist/

  # local databases
  *.db
  *.db-journal

  # build info
  *.tsbuildinfo

  # env
  .env
  .env.local

  # superpowers scratch
  .superpowers/
  ```
- [ ] Create `C:/Users/aboab/Desktop/Projects/sunsama clone/apps/desktop/.gitignore` (workspace-local, defensive — keeps desktop artifacts ignored even if the workspace is built in isolation):
  ```gitignore
  node_modules/
  dist/
  dist-electron/
  out/
  *.db
  *.db-journal
  *.tsbuildinfo
  ```
- [ ] Note: the moved `apps/desktop/.prettierignore` keeps ignoring `dist/`, `dist-electron/`, `out/`, `package-lock.json` relative to the desktop workspace — correct as-is. **No change** to that file.

---

### Task P7-7: GREEN gate 1 — `npm install` at the root installs the workspace

**This is the post-move equivalent of "run, expect PASS."** Install from the monorepo root and verify `apps/desktop` dependencies resolve via the workspace.

- [ ] Run a clean workspace install from the repo root:
  ```bash
  cd "C:/Users/aboab/Desktop/Projects/sunsama clone"
  npm install
  ```
  Expect: **PASS** — completes without `EJSONPARSE`/`ERESOLVE` fatal errors; creates/updates root `node_modules/` and a root `package-lock.json`. `electron`, `vite`, `react`, `sql.js`, etc. are hoisted to the root `node_modules/`.
- [ ] Verify `sql.js` hoisted to the root (this is what the P7-4 `../../node_modules` path depends on):
  ```bash
  ls "C:/Users/aboab/Desktop/Projects/sunsama clone/node_modules/sql.js/dist/sql-wasm.wasm"
  ```
  Expect: the wasm file is listed at the **root** `node_modules`. If it instead landed under `apps/desktop/node_modules/sql.js`, the `extraResources.from` in P7-4 would need `node_modules/...` rather than `../../node_modules/...` — but the default hoisting behavior places it at root; confirm here and reconcile P7-4 if hoisting differs.
- [ ] Verify the workspace is recognized:
  ```bash
  npm query .workspace --prefix "C:/Users/aboab/Desktop/Projects/sunsama clone" 2>/dev/null | grep -i tmap || npm ls --workspaces --depth 0 --prefix "C:/Users/aboab/Desktop/Projects/sunsama clone"
  ```
  Expect: `tmap@1.0.0 -> ./apps/desktop` (and `api-client` if present) appears as a workspace.

---

### Task P7-8: GREEN gate 2 — `npm run build` inside `apps/desktop` reproduces the baseline

**The core acceptance check (criterion #5: "still builds").** Build the desktop workspace at its new location and prove it produces the same artifacts as the P7-1 baseline.

- [ ] Build from inside the desktop workspace:
  ```bash
  cd "C:/Users/aboab/Desktop/Projects/sunsama clone/apps/desktop"
  npm run build
  ```
  Expect: **PASS** — `tsc && vite build && tsc -p tsconfig.electron.json` completes with exit code 0. (If `tsc` reports module-resolution errors for `@/` imports, the alias/`paths` did not survive — but P7-3 confirmed they are `./`-relative, so this should pass.)
- [ ] Verify the artifacts exist at the new location (mirrors the P7-1 baseline targets):
  ```bash
  ls "C:/Users/aboab/Desktop/Projects/sunsama clone/apps/desktop/dist/index.html" \
     "C:/Users/aboab/Desktop/Projects/sunsama clone/apps/desktop/dist-electron/main.js" \
     "C:/Users/aboab/Desktop/Projects/sunsama clone/apps/desktop/dist-electron/preload.js"
  ```
  Expect: all three listed. The desktop app **still builds** after the move.
- [ ] Verify the root passthrough build also works (delegation via root `package.json`):
  ```bash
  cd "C:/Users/aboab/Desktop/Projects/sunsama clone"
  npm run build
  ```
  Expect: **PASS** — root `build` script runs `npm run build --workspace apps/desktop` and produces the same `apps/desktop/dist*` artifacts.

---

### Task P7-9: GREEN gate 3 — `npm run dev` launches the app from the new location

**Acceptance criterion #5: "still ... runs."** Confirm the dev server + Electron launch from the relocated workspace. This is run as a time-boxed background launch (it is a long-running process) and then stopped.

- [ ] Launch dev in the background from the desktop workspace and capture output:
  ```bash
  cd "C:/Users/aboab/Desktop/Projects/sunsama clone/apps/desktop"
  npm run dev
  ```
  Run this with `run_in_background: true`. Expect within ~30s: Vite prints `VITE v6 ... ready`, `Local: http://localhost:5173/` (or the configured port), and `vite-plugin-electron` rebuilds `dist-electron/main.js`/`preload.js` and **spawns the Electron window** (process startup via the `onstart` handler that calls `args.startup(['.', '--no-sandbox'], ...)`).
- [ ] Tail the background output and confirm there is **no** error containing `Cannot find module`, `Failed to resolve import "@/..."`, `ENOENT`, or `Unable to load preload script`. Expect: clean dev startup with the Vite ready banner and Electron main-process logs.
- [ ] Stop the background dev process once the ready banner + Electron launch are confirmed (kill the background task). Expect: process exits cleanly; no orphaned `electron`/`vite` processes left (on Windows, confirm with `tasklist | findstr electron` returning nothing if needed).
- [ ] (Optional packaging spot-check, only if `electron-builder` is desired in CI) Confirm the wasm `extraResources` path resolves without a full installer build by dry-checking the file `electron-builder` will copy:
  ```bash
  ls "C:/Users/aboab/Desktop/Projects/sunsama clone/apps/desktop/../../node_modules/sql.js/dist/sql-wasm.wasm"
  ```
  Expect: the wasm file is listed (proves the `../../node_modules/sql.js/dist/sql-wasm.wasm` `from` path resolves from the desktop workspace dir).

---

### Task P7-10: Clean post-move artifacts and verify the working tree is move-only

Ensure no build output got staged and the diff is purely relocations + the few config edits (no app-logic changes).

- [ ] Remove generated artifacts produced by the gates so they aren't committed:
  ```bash
  cd "C:/Users/aboab/Desktop/Projects/sunsama clone"
  git clean -fdX -- apps/desktop/dist apps/desktop/dist-electron apps/desktop/out
  ```
  Expect: removes the freshly built `apps/desktop/dist*`/`out` (ignored files only).
- [ ] Review the staged/working diff is **only** moves + the 4 expected edits/creations:
  ```bash
  git -C "C:/Users/aboab/Desktop/Projects/sunsama clone" add -A
  git -C "C:/Users/aboab/Desktop/Projects/sunsama clone" status --short
  ```
  Expect exactly:
  - `R` renames for `electron/`, `src/`, `public/`, `build/`, `index.html`, `vite.config.ts`, `vitest.config.ts`, `tsconfig.json`, `tsconfig.electron.json`, `tailwind.config.js`, `postcss.config.js`, `test-electron.js`, `.prettierrc`, `.prettierignore` → under `apps/desktop/`
  - `R` + `M` for `package.json` → `apps/desktop/package.json` (renamed **and** modified: the wasm `from` line)
  - `M` for `.gitignore` (root)
  - `A` (new) `package.json` (root workspace manifest), `apps/desktop/.gitignore`
- [ ] Confirm **no diff inside any `.ts`/`.tsx` under `apps/desktop/src` or `apps/desktop/electron`** (zero app-logic change). The rename should show no content hunks:
  ```bash
  git -C "C:/Users/aboab/Desktop/Projects/sunsama clone" diff --cached --stat -- "apps/desktop/src" "apps/desktop/electron"
  ```
  Expect: rename lines only (`{ => apps/desktop}/...`), **0 insertions / 0 deletions** of code. If any code hunk appears, revert it — this phase changes no logic.

---

### Task P7-11: Commit the monorepo move

- [ ] Commit the relocation + build-config fixes as a single revertible commit:
  ```bash
  cd "C:/Users/aboab/Desktop/Projects/sunsama clone"
  git commit -m "chore: relocate Electron app to apps/desktop and add npm workspaces

Move the existing Electron+React desktop app into apps/desktop (history
preserved via git mv) and wire up the monorepo:
- add root package.json declaring npm workspaces (apps/*, packages/*)
- fix electron-builder extraResources sql-wasm.wasm from-path for the
  hoisted root node_modules (../../node_modules/sql.js/dist/sql-wasm.wasm)
- update root .gitignore for per-workspace dist/dist-electron/out and add
  apps/desktop/.gitignore
- vite/tsconfig/@-alias/tailwind/index.html paths are __dirname/relative
  and survive the move unchanged; no app logic changed

Verified: npm install at root, npm run build in apps/desktop, npm run dev
launches the app. Backend phases green before the move so it is isolated
and revertible.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
  ```
  Expect: commit created; `git show --stat HEAD` shows renames + the 4 config files only.
- [ ] Final verification the tree is clean post-commit:
  ```bash
  git -C "C:/Users/aboab/Desktop/Projects/sunsama clone" status --porcelain
  ```
  Expect: **empty** (clean). Phase P7 complete: monorepo skeleton in place, desktop app moved to `apps/desktop`, and it still builds and runs (acceptance criterion #5 satisfied).

---

**Phase P7 acceptance recap:** `apps/desktop` is a registered npm workspace member; `npm install` (root) → `npm run build` (in `apps/desktop`) succeeds and reproduces `dist/` + `dist-electron/`; `npm run dev` launches Vite + Electron from the new location; the only non-rename diffs are the root `package.json` (workspaces), root `.gitignore`, `apps/desktop/.gitignore`, and the single `extraResources.from` wasm-path edit — zero app-logic changes; the whole move is one revertible commit gated on green backend phases.
