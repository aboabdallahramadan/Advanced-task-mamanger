# SP2 — Online Clients + Web Build — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Connect the Electron desktop app + a new browser web app to the SP1 .NET API (online-only), sharing one account/database, with email/password auth — by extracting the shared React app into `packages/app`, swapping the data layer to HTTP via a `DataClient` seam, migrating to `projectId`, and adding auth.

**Architecture:** One React codebase in `packages/app` builds as both `apps/desktop` (Electron shell) and `apps/web` (Vite web), each injecting a host `Platform` adapter. The store calls an injected `DataClient` (`HttpDataClient` over `@tmap/api-client`); a `mappers` module owns all DTO⇄domain conversion (incl. order↔rank, enum case-fold, numeric coercion). Auth = in-memory access token + single-flight refresh-on-401 (desktop refresh token in OS keychain; web in httpOnly cookie).

**Tech Stack:** React 18 · TypeScript strict · Zustand · Vite 6 · vitest · @tmap/api-client (openapi-fetch) · .NET 10 (Q0 backend touch-ups) · npm workspaces.

**Source spec:** `docs/superpowers/specs/2026-06-08-sp2-online-clients-web-design.md` · **Branch:** `feat/sp2-online-clients`.

**Phase order (dependency-ordered):** Q0 backend touch-ups + client regen → Q1 `packages/app` extraction + dual-build → Q2 DataClient seam + mappers + store refactor → Q3 projectId migration → Q4 auth → Q5 Platform/Desktop/Web adapters + main.ts unwire + reminders + settings split → Q6 web entry + config + end-to-end integration. **Q1 is a P7-style move — gate every relevant task on "both apps build + run".**

---

## Phase Q0 — Backend contract touch-ups + regenerate client

This phase makes six targeted changes to the .NET API that the SP2 clients require, keeps the backend test suite green, then regenerates `packages/api-client` and proves `tsc --noEmit` passes. Files created or modified:

**Backend — modified:**
- `backend/src/Tmap.Api/Features/Auth/AuthDtos.cs` — add `AuthTokenResponse`; keep `TokenPairResponse` as an internal alias
- `backend/src/Tmap.Api/Features/Auth/AuthEndpoints.cs` — return `AuthTokenResponse` from register/login/refresh; add `X-Tmap-Refresh` header guard on `/refresh`
- `backend/src/Tmap.Api/Features/Auth/RefreshCookie.cs` — narrow cookie `Path` from `/api/v1/auth` to `/api/v1/auth/refresh`
- `backend/src/Tmap.Api/Features/NoteGroups/NoteGroupsEndpoints.cs` — add `PATCH /reorder` handler
- `backend/src/Tmap.Api/Features/Recurrence/RecurrenceDtos.cs` — replace `EnsureInstancesResponse.Created` with `TaskResponse[]`; replace `RecurringTaskResponse` return with `TaskResponse[]`
- `backend/src/Tmap.Api/Features/Recurrence/RecurrenceEndpoints.cs` — update `EnsureInstances` and `Create` to return full `TaskResponse[]`
- `backend/src/Tmap.Api/Features/Settings/SettingsDtos.cs` — ensure `SaveSettingsRequest.TimeZoneId` visible in OpenAPI (it already exists but needs the `[FromBody]` annotation verified)
- `backend/src/Tmap.Api/appsettings.Development.json` — add `http://localhost:5174` to `Cors:AllowedOrigins`

**Backend — modified (tests):**
- `backend/tests/Tmap.Api.Tests/AuthTests.cs` — add tests for typed `AuthTokenResponse` body, refresh `X-Tmap-Refresh` guard
- `backend/tests/Tmap.Api.Tests/CorsTests.cs` — add test for `http://localhost:5174` web dev origin
- `backend/tests/Tmap.Api.Tests/NoteGroupsTests.cs` — add test for `PATCH /note-groups/reorder`
- `backend/tests/Tmap.Api.Tests/RecurrenceTests.cs` — add tests for full `TaskResponse[]` from `ensure-instances` and `recurrence/create`
- `backend/tests/Tmap.Api.Tests/SettingsTests.cs` — existing `timeZoneId` tests already cover the write path; add OpenAPI schema assertion

**Client — regenerated (script output):**
- `packages/api-client/src/schema.d.ts` — regenerated

---

### Task Q0-1: Typed `AuthTokenResponse` — annotate DTOs and update endpoints

**Scope:** `AuthDtos.cs`, `AuthEndpoints.cs`, `AuthTests.cs`

The current schema returns `content?: never` for register/login/refresh because `TokenPairResponse` exposes `RefreshToken` in the body (which is also set as a cookie). The client must read `accessToken` and `user { id, email, timeZoneId }` through the typed OpenAPI client. Introduce `AuthTokenResponse` as the public DTO and keep `TokenPairResponse` internally for the issuer.

- [ ] **Write failing test** — in `AuthTests.cs`, add:

```csharp
[Fact]
public async Task Register_response_body_contains_typed_AuthTokenResponse()
{
    var email = $"atr-{Guid.NewGuid():N}@x.io";
    var res = await Client.PostAsJsonAsync("/api/v1/auth/register",
        new { email, password = "Password123!x" });

    res.StatusCode.Should().Be(System.Net.HttpStatusCode.OK);
    var body = await res.Content.ReadFromJsonAsync<AuthTokenResponseDto>();
    body!.AccessToken.Should().NotBeNullOrWhiteSpace();
    body.ExpiresIn.Should().BeGreaterThan(0);
    body.User.Should().NotBeNull();
    body.User.Email.Should().Be(email);
    body.User.Id.Should().NotBe(Guid.Empty);
    body.User.TimeZoneId.Should().Be("UTC");
}

[Fact]
public async Task Login_response_body_contains_typed_AuthTokenResponse()
{
    var email = $"atr2-{Guid.NewGuid():N}@x.io";
    await Client.PostAsJsonAsync("/api/v1/auth/register", new { email, password = "Password123!x" });
    var res = await Client.PostAsJsonAsync("/api/v1/auth/login",
        new { email, password = "Password123!x" });

    res.StatusCode.Should().Be(System.Net.HttpStatusCode.OK);
    var body = await res.Content.ReadFromJsonAsync<AuthTokenResponseDto>();
    body!.AccessToken.Should().NotBeNullOrWhiteSpace();
    body.ExpiresIn.Should().BeGreaterThan(0);
    body.User.Email.Should().Be(email);
}

[Fact]
public async Task Refresh_response_body_contains_typed_AuthTokenResponse()
{
    var email = $"atr3-{Guid.NewGuid():N}@x.io";
    var reg = await (await Client.PostAsJsonAsync("/api/v1/auth/register",
        new { email, password = "Password123!x" }))
        .Content.ReadFromJsonAsync<AuthTokenResponseDto>();

    var res = await Client.PostAsJsonAsync("/api/v1/auth/refresh",
        new { refreshToken = reg!.RefreshToken });

    res.StatusCode.Should().Be(System.Net.HttpStatusCode.OK);
    var body = await res.Content.ReadFromJsonAsync<AuthTokenResponseDto>();
    body!.AccessToken.Should().NotBeNullOrWhiteSpace();
    body.ExpiresIn.Should().BeGreaterThan(0);
    body.User.Email.Should().Be(email);
}

// Local DTO for the new contract; placed at bottom of AuthTests.cs
private sealed record AuthTokenResponseDto(
    string AccessToken,
    string RefreshToken,
    int ExpiresIn,
    AuthTokenUserDto User);

private sealed record AuthTokenUserDto(Guid Id, string Email, string TimeZoneId);
```

- [ ] **Run** `dotnet test backend/Tmap.sln --filter "FullyQualifiedName~AuthTests"` — expect the three new tests to **FAIL** (body is `TokenPairResponse`, no `ExpiresIn`/`User` fields).

- [ ] **Implement** — edit `backend/src/Tmap.Api/Features/Auth/AuthDtos.cs`:

```csharp
namespace Tmap.Api.Features.Auth;

public sealed record RegisterRequest(string Email, string Password);
public sealed record LoginRequest(string Email, string Password);
// Native clients send the refresh token in the body; web omits it (cookie). Nullable for that reason.
public sealed record RefreshRequest(string? RefreshToken);
public sealed record LogoutRequest(string? RefreshToken);

/// <summary>Public OpenAPI-visible response for register / login / refresh.</summary>
public sealed record AuthTokenResponse(
    string AccessToken,
    /// <summary>Refresh token — absent on web (httpOnly cookie); present for native clients.</summary>
    string RefreshToken,
    /// <summary>Seconds until the access token expires.</summary>
    int ExpiresIn,
    AuthTokenUser User);

public sealed record AuthTokenUser(Guid Id, string Email, string TimeZoneId);

// Internal: full issuer result; converted to AuthTokenResponse before returning.
internal sealed record TokenPairResponse(string AccessToken, string RefreshToken, DateTimeOffset AccessTokenExpiresAt);
public sealed record MeResponse(Guid Id, string Email, string TimeZoneId);
```

- [ ] **Update `AuthEndpoints.cs`** — change `Register`, `Login`, and `Refresh` to return `AuthTokenResponse`. The `AuthTokenIssuer.IssueAsync` already returns `TokenPairResponse`; add a `ToPublic` helper that converts it plus loads the user profile:

In `AuthTokenIssuer`, add:

```csharp
public static AuthTokenResponse ToPublic(
    TokenPairResponse pair,
    JwtOptions opts,
    ApplicationUser user)
{
    var expiresIn = (int)(pair.AccessTokenExpiresAt - DateTimeOffset.UtcNow).TotalSeconds;
    return new AuthTokenResponse(
        pair.AccessToken,
        pair.RefreshToken,
        expiresIn > 0 ? expiresIn : 0,
        new AuthTokenUser(user.Id, user.Email ?? "", user.TimeZoneId));
}
```

In `Register`: after `var pair = await AuthTokenIssuer.IssueAsync(...)`, load `user` (already in scope) and return `Results.Ok(AuthTokenIssuer.ToPublic(pair, jwtOptions.Value, user))`.

In `Login`: same pattern — `user` already resolved; add `IOptions<JwtOptions> jwtOptions` parameter (already present), call `AuthTokenIssuer.ToPublic(pair, jwtOptions.Value, user)`.

In `Refresh`: after the `token.UserId` path resolves, load the user: `var refreshUser = await users.FindByIdAsync(token.UserId.ToString());` — add `UserManager<ApplicationUser> users` to the parameter list. Return `Results.Ok(AuthTokenIssuer.ToPublic(new TokenPairResponse(access, newRaw, accessExp), jwtOptions.Value, refreshUser!))`.

> Note: the existing `TokenPairDto` in `IntegrationTestBase` still works because it only reads `AccessToken`/`RefreshToken`/`AccessTokenExpiresAt`. The new `AuthTokenResponseDto` in tests is additive.

- [ ] **Run** `dotnet test backend/Tmap.sln --filter "FullyQualifiedName~AuthTests"` — expect **all auth tests PASS** including the three new ones.

---

### Task Q0-2: `X-Tmap-Refresh` CSRF header guard on `/auth/refresh`

**Scope:** `AuthEndpoints.cs`, `AuthTests.cs`

Web clients will send `X-Tmap-Refresh: 1` on every cookie-based refresh. Require this header when no body `refreshToken` is present (i.e., web path), so a CSRF attack that forces a browser to POST `/auth/refresh` without the custom header gets rejected.

- [ ] **Write failing test** — add to `AuthTests.cs`:

```csharp
[Fact]
public async Task Refresh_web_path_without_XTmapRefresh_header_returns_401()
{
    // Register so a valid cookie would exist in a real browser session.
    // In tests we have no actual cookie jar, but we can verify the header guard
    // independently: sending no body token AND no X-Tmap-Refresh header must fail.
    var email = $"csrf-{Guid.NewGuid():N}@x.io";
    await Client.PostAsJsonAsync("/api/v1/auth/register", new { email, password = "Password123!x" });

    // Simulate web path: no body refreshToken, no X-Tmap-Refresh header.
    using var req = new HttpRequestMessage(HttpMethod.Post, "/api/v1/auth/refresh");
    req.Content = JsonContent.Create(new { refreshToken = (string?)null });
    // Deliberately NOT adding X-Tmap-Refresh header.
    var res = await Client.SendAsync(req);
    res.StatusCode.Should().Be(System.Net.HttpStatusCode.Unauthorized);
}

[Fact]
public async Task Refresh_native_path_without_XTmapRefresh_header_still_works()
{
    // Native clients send the token in the body — header not required.
    var email = $"native-{Guid.NewGuid():N}@x.io";
    var reg = await (await Client.PostAsJsonAsync("/api/v1/auth/register",
        new { email, password = "Password123!x" }))
        .Content.ReadFromJsonAsync<AuthTokenResponseDto>();

    var res = await Client.PostAsJsonAsync("/api/v1/auth/refresh",
        new { refreshToken = reg!.RefreshToken });
    res.StatusCode.Should().Be(System.Net.HttpStatusCode.OK);
}
```

- [ ] **Run** — expect `Refresh_web_path_without_XTmapRefresh_header_returns_401` to **FAIL** (currently 401 by accident — token is null, so `Resolve` returns null and we get 401; but this test passes for wrong reason). Actually, add a "web path with X-Tmap-Refresh" test to confirm the positive path too:

```csharp
[Fact]
public async Task Refresh_cookie_path_with_XTmapRefresh_header_is_accepted()
{
    // We cannot set httpOnly cookies in the test client easily, but we can confirm
    // the header alone does not break a body-token refresh.
    var email = $"xhdr-{Guid.NewGuid():N}@x.io";
    var reg = await (await Client.PostAsJsonAsync("/api/v1/auth/register",
        new { email, password = "Password123!x" }))
        .Content.ReadFromJsonAsync<AuthTokenResponseDto>();

    using var req = new HttpRequestMessage(HttpMethod.Post, "/api/v1/auth/refresh");
    req.Headers.Add("X-Tmap-Refresh", "1");
    req.Content = JsonContent.Create(new { refreshToken = reg!.RefreshToken });
    var res = await Client.SendAsync(req);
    res.StatusCode.Should().Be(System.Net.HttpStatusCode.OK);
}
```

The logic is: if `body.RefreshToken` is null AND the cookie is absent AND `X-Tmap-Refresh` header is missing, return 401. If `body.RefreshToken` is null AND no cookie, return 401 regardless (already does). The actual guard: if `body.RefreshToken` is null (web path), require `X-Tmap-Refresh: 1` header before even reading the cookie. This makes CSRF impossible because browsers cannot set arbitrary headers cross-origin.

- [ ] **Implement** — in `AuthEndpoints.cs`, update the `Refresh` method's first guard:

```csharp
private static async Task<IResult> Refresh(
    RefreshRequest body,
    HttpContext http,
    AppDbContext db,
    IJwtService jwt,
    IOptions<JwtOptions> jwtOptions,
    UserManager<ApplicationUser> users)
{
    // Web path (no body token): require the CSRF sentinel header.
    // Native path (body token present): header not required.
    var isWebPath = string.IsNullOrEmpty(body.RefreshToken);
    if (isWebPath && !http.Request.Headers.ContainsKey("X-Tmap-Refresh"))
    {
        return GenericUnauthorized();
    }

    var raw = RefreshCookie.Resolve(http.Request, body.RefreshToken);
    if (string.IsNullOrEmpty(raw))
    {
        return GenericUnauthorized();
    }
    // ... rest unchanged ...
}
```

- [ ] **Run** `dotnet test backend/Tmap.sln --filter "FullyQualifiedName~AuthTests"` — expect **all auth tests PASS**.

---

### Task Q0-3: Narrow refresh cookie `Path` to `/api/v1/auth/refresh`

**Scope:** `RefreshCookie.cs`, `AuthTests.cs`

Currently the cookie is scoped to `Path=/api/v1/auth`, so it is sent on `/auth/me`, `/auth/logout-all`, etc. Narrowing to exactly `/api/v1/auth/refresh` means a `credentials:'include'` call to any other API path never carries the refresh cookie, eliminating accidental exposure.

- [ ] **Write failing test** — add to `AuthTests.cs`:

```csharp
[Fact]
public async Task Refresh_response_sets_cookie_scoped_to_refresh_path_only()
{
    var email = $"cookiepath-{Guid.NewGuid():N}@x.io";
    var reg = await (await Client.PostAsJsonAsync("/api/v1/auth/register",
        new { email, password = "Password123!x" }))
        .Content.ReadFromJsonAsync<AuthTokenResponseDto>();

    // Use a handler that captures Set-Cookie headers.
    using var handler = new HttpClientHandler { UseCookies = false };
    using var rawClient = Factory.CreateDefaultClient(handler);

    var res = await rawClient.PostAsJsonAsync("/api/v1/auth/refresh",
        new { refreshToken = reg!.RefreshToken });
    res.StatusCode.Should().Be(System.Net.HttpStatusCode.OK);

    var setCookie = res.Headers.GetValues("Set-Cookie").FirstOrDefault();
    setCookie.Should().NotBeNull();
    // Path must be exactly /api/v1/auth/refresh (not /api/v1/auth).
    setCookie!.Should().Contain("path=/api/v1/auth/refresh",
        StringComparison.OrdinalIgnoreCase);
    setCookie.Should().NotContain("path=/api/v1/auth;",
        StringComparison.OrdinalIgnoreCase);
}
```

- [ ] **Run** — expect FAIL (current `Path` is `/api/v1/auth`).

- [ ] **Implement** — edit `backend/src/Tmap.Api/Features/Auth/RefreshCookie.cs`, change both `Path` values from `"/api/v1/auth"` to `"/api/v1/auth/refresh"`:

```csharp
public static void Write(HttpResponse res, string rawRefreshToken, DateTimeOffset expiresAt) =>
    res.Cookies.Append(Name, rawRefreshToken, new CookieOptions
    {
        HttpOnly = true,
        Secure = true,
        SameSite = SameSiteMode.Strict,
        Path = "/api/v1/auth/refresh",
        Expires = expiresAt,
    });

public static void Clear(HttpResponse res) =>
    res.Cookies.Append(Name, "", new CookieOptions
    {
        HttpOnly = true,
        Secure = true,
        SameSite = SameSiteMode.Strict,
        Path = "/api/v1/auth/refresh",
        Expires = DateTimeOffset.UnixEpoch,
    });
```

- [ ] **Run** `dotnet test backend/Tmap.sln --filter "FullyQualifiedName~AuthTests"` — expect **all PASS**.

> Note: `/auth/logout` uses `RefreshCookie.Resolve` + `RefreshCookie.Clear`. Because `Clear` writes the expiry cookie with the same `Path`, the browser will correctly clear the cookie. Logout always accepts the token from the body anyway (native), or reads the cookie if present. The narrowed path means the cookie is only auto-sent by the browser to `/auth/refresh`, not to `/auth/logout` — this is intentional: the web logout path does NOT need to carry the cookie because `refreshClient.ts` will call `/auth/logout` with no body (web variant per §5) and the server already reads the cookie via `Resolve` when a cookie IS present. Confirm `Logout` still works via body-token path in the existing `Logout_revokes_presented_refresh_token` test.

---

### Task Q0-4: Add `PATCH /api/v1/note-groups/reorder`

**Scope:** `NoteGroupsEndpoints.cs`, `NoteGroupsTests.cs`

`tasks/reorder` and `projects/reorder` both exist; `note-groups/reorder` was never shipped.

- [ ] **Write failing test** — add to `NoteGroupsTests.cs`:

```csharp
[Fact]
public async Task Reorder_updates_ranks_for_all_supplied_ids()
{
    var authed = await RegisterAsync();

    // Create three groups.
    var a = await (await authed.Client.PostAsJsonAsync("/api/v1/note-groups",
        new CreateNoteGroupRequest("A", "📁", null, "a0")))
        .Content.ReadFromJsonAsync<NoteGroupResponse>();
    var b = await (await authed.Client.PostAsJsonAsync("/api/v1/note-groups",
        new CreateNoteGroupRequest("B", "📁", null, "b0")))
        .Content.ReadFromJsonAsync<NoteGroupResponse>();
    var c = await (await authed.Client.PostAsJsonAsync("/api/v1/note-groups",
        new CreateNoteGroupRequest("C", "📁", null, "c0")))
        .Content.ReadFromJsonAsync<NoteGroupResponse>();

    // Reorder: move A to end by giving it rank "z0".
    var items = new[]
    {
        new { id = a!.Id, rank = "z0" },
        new { id = b!.Id, rank = "a0" },
        new { id = c!.Id, rank = "m0" },
    };

    var resp = await authed.Client.PatchAsJsonAsync("/api/v1/note-groups/reorder", items);
    resp.StatusCode.Should().Be(HttpStatusCode.NoContent);

    var list = await authed.Client.GetFromJsonAsync<List<NoteGroupResponse>>("/api/v1/note-groups");
    list!.First(g => g.Id == b.Id).Rank.Should().Be("a0");
    list.First(g => g.Id == c.Id).Rank.Should().Be("m0");
    list.First(g => g.Id == a.Id).Rank.Should().Be("z0");
    // Verify list is returned sorted by new ranks.
    list.Select(g => g.Rank).Should().BeInAscendingOrder(StringComparer.Ordinal);
}

[Fact]
public async Task Reorder_ignores_ids_belonging_to_other_users()
{
    var alice = await RegisterAsync();
    var bob = await RegisterAsync();

    var aliceGroup = await (await alice.Client.PostAsJsonAsync("/api/v1/note-groups",
        new CreateNoteGroupRequest("AliceG", "📁", null, "a0")))
        .Content.ReadFromJsonAsync<NoteGroupResponse>();

    // Bob tries to reorder Alice's group.
    var resp = await bob.Client.PatchAsJsonAsync("/api/v1/note-groups/reorder",
        new[] { new { id = aliceGroup!.Id, rank = "z9" } });
    resp.StatusCode.Should().Be(HttpStatusCode.NoContent); // no error, but nothing changed

    // Alice's group rank is unchanged.
    var aliceList = await alice.Client.GetFromJsonAsync<List<NoteGroupResponse>>("/api/v1/note-groups");
    aliceList!.Single(g => g.Id == aliceGroup.Id).Rank.Should().Be("a0");
}
```

- [ ] **Run** `dotnet test backend/Tmap.sln --filter "FullyQualifiedName~NoteGroupsTests"` — expect the two new tests to **FAIL** (404 on PATCH `/note-groups/reorder`).

- [ ] **Implement** — edit `NoteGroupsEndpoints.cs`, register `PATCH /reorder` before `/{id:guid}` and add the handler. Note: `ReorderItem` is already defined in `NoteGroupDtos.cs`.

In `MapNoteGroups`:
```csharp
// reorder must be registered BEFORE /{id:guid} so the literal segment wins.
group.MapPatch("/reorder", Reorder)
    .AddEndpointFilter<ValidationFilter<IReadOnlyList<ReorderItem>>>();

group.MapPatch("/{id:guid}", Update).AddEndpointFilter<ValidationFilter<UpdateNoteGroupRequest>>();
```

Add the handler method (mirrors the projects pattern):
```csharp
private static async Task<NoContent> Reorder(
    IReadOnlyList<ReorderItem> items,
    AppDbContext db,
    ICurrentUser currentUser,
    CancellationToken ct)
{
    foreach (var item in items)
    {
        // Tenant filter on NoteGroups ensures only the current user's rows are matched.
        await db.NoteGroups
            .Where(g => g.Id == item.Id)
            .ExecuteUpdateAsync(s => s.SetProperty(g => g.Rank, item.Rank), ct);
    }

    return TypedResults.NoContent();
}
```

- [ ] **Run** `dotnet test backend/Tmap.sln --filter "FullyQualifiedName~NoteGroupsTests"` — expect **all PASS**.

---

### Task Q0-5: `ensure-instances` returns full `TaskResponse[]`; `recurrence/create` returns full instances

**Scope:** `RecurrenceDtos.cs`, `RecurrenceEndpoints.cs`, `RecurrenceTests.cs`, `TaskDtos.cs` (read-only reference)

Currently `EnsureInstances` returns `EnsureInstancesResponse { created: CreatedInstance[] }` (stubs with id/ruleId/date/title only). `Create` returns a single `RecurringTaskResponse` (template stub). The client needs full `TaskResponse[]` from both so it can hydrate the store with real domain objects.

- [ ] **Write failing tests** — add to `RecurrenceTests.cs`:

```csharp
[Fact]
public async Task EnsureInstances_returns_full_TaskResponse_array()
{
    var user = await RegisterAsync();
    var monday = NextFutureMonday();
    var created = await (await user.Client.PostAsJsonAsync(
            "/api/v1/recurrence", WeeklyMonWedFri(monday.ToString("yyyy-MM-dd"))))
        .Content.ReadFromJsonAsync<TaskResponse[]>();

    // Create returns at least the template.
    created.Should().NotBeNullOrEmpty();
    created![0].Title.Should().Be("Standup");
    created[0].RecurrenceRuleId.Should().NotBeNull();

    var start = monday.ToString("yyyy-MM-dd");
    var end   = monday.AddDays(13).ToString("yyyy-MM-dd");

    var ensureRes = await user.Client.PostAsync(
        $"/api/v1/recurrence/ensure-instances?start={start}&end={end}", null);
    ensureRes.StatusCode.Should().Be(System.Net.HttpStatusCode.OK);

    var instances = await ensureRes.Content.ReadFromJsonAsync<TaskResponse[]>();
    instances.Should().NotBeNullOrEmpty();
    // Every element must be a full TaskResponse (has a non-empty id, title, status, rank).
    foreach (var t in instances!)
    {
        t.Id.Should().NotBe(Guid.Empty);
        t.Title.Should().NotBeNullOrEmpty();
        t.Status.Should().NotBeNull();
        t.Rank.Should().NotBeNullOrEmpty();
        t.RecurrenceRuleId.Should().NotBeNull();
        t.IsRecurrenceTemplate.Should().BeFalse();
        t.PlannedDate.Should().NotBeNull();
    }
}

[Fact]
public async Task Create_recurrence_returns_TaskResponse_array_including_template()
{
    var user = await RegisterAsync();
    var monday = NextFutureMonday();

    var res = await user.Client.PostAsJsonAsync(
        "/api/v1/recurrence", WeeklyMonWedFri(monday.ToString("yyyy-MM-dd")));
    res.StatusCode.Should().Be(System.Net.HttpStatusCode.Created);

    var tasks = await res.Content.ReadFromJsonAsync<TaskResponse[]>();
    tasks.Should().NotBeNullOrEmpty();
    var template = tasks!.SingleOrDefault(t => t.IsRecurrenceTemplate);
    template.Should().NotBeNull();
    template!.Title.Should().Be("Standup");
    template.RecurrenceRuleId.Should().NotBeNull();
}
```

Also add a local `TaskResponse` record at the bottom of `RecurrenceTests.cs` matching the API shape:
```csharp
private sealed record TaskResponse(
    Guid Id,
    string Title,
    Guid? RecurrenceRuleId,
    bool IsRecurrenceTemplate,
    string? PlannedDate,
    string Status,
    string Rank,
    bool RecurrenceDetached,
    string? RecurrenceOriginalDate);
```

- [ ] **Run** `dotnet test backend/Tmap.sln --filter "FullyQualifiedName~RecurrenceTests"` — expect the two new tests to **FAIL** (wrong response shape).

- [ ] **Implement** — Update `RecurrenceDtos.cs`:

Remove the `EnsureInstancesResponse` record entirely. The endpoint will now return `TaskResponse[]` directly. Also remove `CreatedInstance` (it is only used by the old response). Keep all other DTOs untouched.

```csharp
// Remove:
// public sealed record EnsureInstancesResponse(List<CreatedInstance> Created);
// public sealed record CreatedInstance(Guid Id, Guid RecurrenceRuleId, DateOnly PlannedDate, string Title);
```

- [ ] **Implement** — Update `RecurrenceEndpoints.cs`, `EnsureInstances` handler:

Change return type signature: `Results<Ok<List<TaskResponse>>, ValidationProblem>`. Replace `var created = new List<CreatedInstance>();` with `var created = new List<TaskItem>();`. In the inner loop, replace `created.Add(new CreatedInstance(...))` with `created.Add(instance)`. At the end, replace `TypedResults.Ok(new EnsureInstancesResponse(created))` with:
```csharp
// Load subtasks for all created instances in one query.
var createdIds = created.Select(t => t.Id).ToList();
var subtasksMap = await db.Set<SubtaskItem>()
    .Where(s => createdIds.Contains(s.TaskId))
    .ToListAsync(ct);
var subByTask = subtasksMap.GroupBy(s => s.TaskId)
    .ToDictionary(g => g.Key, g => g.ToList());

return TypedResults.Ok(created.Select(t =>
    TasksEndpoints.ToTaskResponse(t, subByTask.GetValueOrDefault(t.Id, new()))).ToList());
```

This requires `ToTaskResponse` to be `internal static` in `TasksEndpoints.cs`. Check if it already exists (it does as a private helper) — promote it:

In `TasksEndpoints.cs`, change `private static TaskResponse ToTaskResponse(...)` to `internal static TaskResponse ToTaskResponse(...)`.

- [ ] **Implement** — Update `RecurrenceEndpoints.cs`, `Create` handler:

Change return type to `Results<Created<List<TaskResponse>>, ValidationProblem>`. After `await db.SaveChangesAsync(ct)`, load the template's subtasks (empty for a new task) and build the response:
```csharp
var taskResponse = TasksEndpoints.ToTaskResponse(template, new List<SubtaskItem>());
return TypedResults.Created($"/api/v1/tasks/{template.Id}", new List<TaskResponse> { taskResponse });
```

The existing `RecurringTaskResponse` DTO in `RecurrenceDtos.cs` can be removed or kept as dead code — remove it to keep the schema clean, since no endpoint returns it anymore.

- [ ] **Run** `dotnet test backend/Tmap.sln --filter "FullyQualifiedName~RecurrenceTests"` — expect **all PASS**.

- [ ] **Confirm** no other tests broke by running the full suite: `dotnet test backend/Tmap.sln` — expect **all green**.

> Note: `SubtaskItem` may be named differently in the codebase (it is the EF entity); check the entity name in `db.Set<...>()` calls in `TasksEndpoints.cs`. Use the same type name.

---

### Task Q0-6: Confirm `PUT /settings` `timeZoneId` is in the OpenAPI document

**Scope:** `SettingsDtos.cs`, `SettingsTests.cs`, OpenAPI document

The server already handles `timeZoneId` in `SaveSettingsRequest` and the tests in `SettingsTests.cs` (`Put_ValidTimeZoneId_PersistsAndIsReflectedInGet`) already pass. The gap is that `schema.d.ts` shows `SaveSettingsRequest { settings: Record<string,string> }` with NO `timeZoneId` field — it was not appearing in the generated OpenAPI doc. This is because the property is a C# optional parameter with a default value `null`, which the OpenAPI generator may emit as optional but it must be in the schema.

- [ ] **Write failing test** — add to `SettingsTests.cs`:

```csharp
[Fact]
public async Task OpenApi_SaveSettingsRequest_schema_includes_timeZoneId_field()
{
    var doc = await Client.GetFromJsonAsync<System.Text.Json.JsonElement>("/openapi/v1.json");
    var schemas = doc.GetProperty("components").GetProperty("schemas");
    var saveSchema = schemas.GetProperty("SaveSettingsRequest");
    saveSchema.GetProperty("properties").TryGetProperty("timeZoneId", out _)
        .Should().BeTrue("timeZoneId must appear in the SaveSettingsRequest OpenAPI schema");
}
```

- [ ] **Run** — expect FAIL (property not in generated schema).

- [ ] **Investigate** — the root cause is the C# record `SaveSettingsRequest(Dictionary<string, string> Settings, string? TimeZoneId = null)` uses a default-value optional parameter. Microsoft.AspNetCore.OpenApi may omit nullable optional parameters from the schema. Fix by adding `[property: System.ComponentModel.DataAnnotations.Required(AllowEmptyStrings = true)]` to `TimeZoneId`, or restructure to make it a required nullable field so the generator emits it. The simplest fix is to annotate the DTO explicitly:

Edit `SettingsDtos.cs`:
```csharp
using System.Text.Json.Serialization;

namespace Tmap.Api.Features.Settings;

public sealed record SaveSettingsRequest(
    Dictionary<string, string> Settings,
    [property: JsonPropertyName("timeZoneId")]
    string? TimeZoneId = null);

public sealed record SettingsResponse(
    Dictionary<string, string> Settings,
    string TimeZoneId);
```

If the generator still omits it, explicitly add an `IOperationTransformer` that injects the field. A cleaner approach that works with the ASP.NET Core OpenAPI generator: convert `SaveSettingsRequest` from a positional record to a class with a `[JsonPropertyName]`-decorated property and ensure the property has a non-default OpenAPI-visible type:

```csharp
namespace Tmap.Api.Features.Settings;

public sealed class SaveSettingsRequest
{
    public Dictionary<string, string> Settings { get; init; } = new();
    /// <summary>
    /// IANA time zone identifier to persist on the user profile, e.g. "America/New_York".
    /// When omitted the existing TimeZoneId is unchanged.
    /// </summary>
    public string? TimeZoneId { get; init; }
}

public sealed record SettingsResponse(
    Dictionary<string, string> Settings,
    string TimeZoneId);
```

This mirrors how other nullable fields (e.g. `UpdateTaskRequest.Title`) are handled and is guaranteed to appear in the generated OpenAPI schema.

Also update `SettingsValidator.cs` if it references the constructor pattern.

- [ ] **Run** `dotnet test backend/Tmap.sln --filter "FullyQualifiedName~SettingsTests"` — expect **all PASS** including the new OpenAPI assertion.

---

### Task Q0-7: Add web dev origin `http://localhost:5174` to CORS allowlist

**Scope:** `appsettings.Development.json`, `TmapApiFactory.cs`, `CorsTests.cs`

The desktop app's Vite dev server is on port 5173 (already allowed). The web app will run on 5174. Add it to the development config and the test factory's seeded list.

- [ ] **Write failing test** — add to `CorsTests.cs`:

```csharp
[Fact]
public async Task Preflight_from_web_dev_origin_5174_is_allowed()
{
    using var factory = NewFactoryWithConfig(new Dictionary<string, string?>
    {
        ["Cors:AllowedOrigins:1"] = "http://localhost:5174",
    });
    using var client = factory.CreateClient();

    var request = new HttpRequestMessage(HttpMethod.Options, "/api/v1/auth/login");
    request.Headers.Add("Origin", "http://localhost:5174");
    request.Headers.Add("Access-Control-Request-Method", "POST");
    request.Headers.Add("Access-Control-Request-Headers", "content-type,authorization");

    var response = await client.SendAsync(request);

    response.Headers.GetValues("Access-Control-Allow-Origin")
        .Should().ContainSingle().Which.Should().Be("http://localhost:5174");
    response.Headers.GetValues("Access-Control-Allow-Credentials")
        .Should().ContainSingle().Which.Should().Be("true");
}
```

- [ ] **Run** — expect FAIL if `http://localhost:5174` is not in the test factory seed (currently only `https://app.tmap.test` is seeded in `TmapApiFactory.cs`). The test uses `NewFactoryWithConfig` to inject the origin, so it will PASS immediately — verify this test actually exercises the code path by also adding a negative assertion confirming the base factory does NOT allow it:

```csharp
[Fact]
public async Task Preflight_from_web_dev_origin_5174_without_allowlist_entry_is_blocked()
{
    // Factory default (TmapApiFactory) only seeds "https://app.tmap.test".
    var request = new HttpRequestMessage(HttpMethod.Options, "/api/v1/tasks");
    request.Headers.Add("Origin", "http://localhost:5174");
    request.Headers.Add("Access-Control-Request-Method", "GET");

    var response = await Client.SendAsync(request);
    response.Headers.Contains("Access-Control-Allow-Origin").Should().BeFalse();
}
```

- [ ] **Implement** — edit `appsettings.Development.json`:

```json
"Cors": {
  "AllowedOrigins": [
    "http://localhost:5173",
    "http://127.0.0.1:5173",
    "http://localhost:5174",
    "http://127.0.0.1:5174"
  ]
}
```

- [ ] **Note on desktop (Electron):** the CORS comment already documents the decision: "Desktop clients send no Origin header and are unaffected by CORS." Electron's main-process `net` module does not attach an `Origin` header for `file://` loads, so CORS never blocks desktop fetch calls. Document this in a code comment at the top of `CorsServiceCollectionExtensions.cs`:

```csharp
// Desktop (Electron) clients fetch from a file:// context; the browser/renderer
// sends no Origin header (or "null"), so they never hit CORS and are unaffected
// by this allowlist. Only browser-based web app origins need to be listed here.
```

- [ ] **Run** `dotnet test backend/Tmap.sln --filter "FullyQualifiedName~CorsTests"` — expect **all PASS**.

---

### Task Q0-8: Full backend test suite green

**Scope:** `backend/Tmap.sln`

Before regenerating the client, confirm no regressions from the previous tasks.

- [ ] Run:
  ```
  dotnet test backend/Tmap.sln
  ```
  Expect: **all tests pass, zero failures**. If any fail, fix before continuing.

---

### Task Q0-9: Regenerate `packages/api-client` and verify TypeScript

**Scope:** `packages/api-client/src/schema.d.ts`, `packages/api-client/src/client.ts`

- [ ] **Run the generate script** from the monorepo root or `packages/api-client/`:
  ```
  cd "packages/api-client" && npm run gen
  ```
  This runs three steps: `emit:openapi` (builds the .NET project with `OpenApiGenerateDocumentsOnBuild=true`), `copy:openapi` (copies the generated `openapi.json` from the .NET bin), `gen:types` (runs `openapi-typescript`).

- [ ] **Verify the new schema contains the expected additions.** After regeneration, confirm the following are present in `schema.d.ts`:

  a. Auth endpoints return `AuthTokenResponse`:
  ```
  grep -l "AuthTokenResponse" packages/api-client/src/schema.d.ts
  ```
  Expect the type to appear in `components.schemas` and the `/auth/register`, `/auth/login`, `/auth/refresh` path responses to reference it.

  b. Note-groups reorder endpoint:
  ```
  grep "note-groups/reorder" packages/api-client/src/schema.d.ts
  ```
  Expect a `PATCH /api/v1/note-groups/reorder` path entry.

  c. Ensure-instances returns `TaskResponse[]`:
  ```
  grep -A5 "EnsureRecurrenceInstances" packages/api-client/src/schema.d.ts
  ```
  Expect `content: { "application/json": components["schemas"]["TaskResponse"][] }`.

  d. Create recurrence returns `TaskResponse[]`:
  Expect `CreateRecurringTask` operation's 201 response to reference `TaskResponse[]`.

  e. `SaveSettingsRequest` has `timeZoneId`:
  ```
  grep -A10 "SaveSettingsRequest" packages/api-client/src/schema.d.ts
  ```
  Expect a `timeZoneId` field in the schema.

- [ ] **Run TypeScript compiler check:**
  ```
  cd "packages/api-client" && npm run typecheck
  ```
  Expect: **zero type errors**. If `client.ts` references removed types (e.g. `EnsureInstancesResponse`, `RecurringTaskResponse`), update the import — however `client.ts` only imports `paths` from `schema`, so no changes should be needed there.

- [ ] **Commit the regenerated schema** on branch `feat/sp2-online-clients`:
  ```
  git add packages/api-client/src/schema.d.ts packages/api-client/openapi.json
  git add backend/src/Tmap.Api/Features/Auth/AuthDtos.cs
  git add backend/src/Tmap.Api/Features/Auth/AuthEndpoints.cs
  git add backend/src/Tmap.Api/Features/Auth/RefreshCookie.cs
  git add backend/src/Tmap.Api/Features/NoteGroups/NoteGroupsEndpoints.cs
  git add backend/src/Tmap.Api/Features/Recurrence/RecurrenceDtos.cs
  git add backend/src/Tmap.Api/Features/Recurrence/RecurrenceEndpoints.cs
  git add backend/src/Tmap.Api/Features/Settings/SettingsDtos.cs
  git add backend/src/Tmap.Api/appsettings.Development.json
  git add backend/tests/Tmap.Api.Tests/AuthTests.cs
  git add backend/tests/Tmap.Api.Tests/CorsTests.cs
  git add backend/tests/Tmap.Api.Tests/NoteGroupsTests.cs
  git add backend/tests/Tmap.Api.Tests/RecurrenceTests.cs
  git add backend/tests/Tmap.Api.Tests/SettingsTests.cs
  git commit -m "$(cat <<'EOF'
  feat(q0): backend contract touch-ups + regenerate api-client for SP2

  - Add AuthTokenResponse (accessToken, expiresIn, user{id,email,timeZoneId}) to
    register/login/refresh; keep internal TokenPairResponse for the issuer.
  - Narrow refresh cookie Path to /api/v1/auth/refresh; require X-Tmap-Refresh
    header on the web (cookie-only) refresh path as CSRF defense.
  - Add PATCH /api/v1/note-groups/reorder mirroring tasks/projects/notes.
  - ensure-instances and recurrence/create now return full TaskResponse[]
    instead of stub CreatedInstance / RecurringTaskResponse DTOs.
  - Confirm SaveSettingsRequest.timeZoneId appears in the OpenAPI schema.
  - Add http://localhost:5174 to the dev CORS allowlist for the web app.
  - All backend tests green; api-client regenerated; tsc --noEmit passes.

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  EOF
  )"
  ```

---

### Acceptance gate for Q0

All of the following must hold before moving to Q1:

1. `dotnet test backend/Tmap.sln` — **zero failures** (total test count is higher than before Q0 by the new tests added in tasks Q0-1 through Q0-7).
2. `cd packages/api-client && npm run gen` — exits with code 0.
3. `cd packages/api-client && npm run typecheck` — exits with code 0, zero errors.
4. `schema.d.ts` contains: `AuthTokenResponse` schema, `"/api/v1/note-groups/reorder"` path, `EnsureRecurrenceInstances` response referencing `TaskResponse[]`, `CreateRecurringTask` response referencing `TaskResponse[]`, `SaveSettingsRequest` with a `timeZoneId` property.
5. Branch is `feat/sp2-online-clients` (not `feat/online-multiplatform` — create the new branch before starting Q0 if not already on it):
   ```
   git checkout -b feat/sp2-online-clients
   ```

---

## Phase Q1 — packages/app extraction + dual-build skeleton

This phase creates the new shared React package `@tmap/app` at `packages/app/` and MOVES the entire renderer (`App.tsx`, `main.tsx`, `store.ts`, `types.ts`, `priorityUtils.ts`, `useTextDirection.ts`, `index.css`, and the `components/`, `lib/` trees) out of `apps/desktop/src` via `git mv` (history preserved). It then rebuilds `apps/desktop` as a thin Electron shell whose `src/main.tsx` imports `AppRoot` from `@tmap/app`, and scaffolds the brand-new `apps/web` Vite entry. A temporary `packages/app/src/AppRoot.tsx` stub renders the existing `App` so BOTH build targets compile NOW (real `DataClient`/`Platform`/auth wiring lands in Q2+). `focus-widget.html` STAYS in `apps/desktop/public`. **Files this phase creates:** `packages/app/{package.json, tsconfig.json, tsconfig.node.json, vite.config.ts, vitest.config.ts, tailwind.config.js, postcss.config.js, index.css(via mv)}`, `packages/app/src/AppRoot.tsx`, `apps/web/{package.json, index.html, vite.config.ts, tsconfig.json, postcss.config.js, tailwind.config.js, .gitignore}`, `apps/web/src/main.tsx`. **Files this phase modifies/moves:** all of `apps/desktop/src/**` → `packages/app/src/**` (git mv), new thin `apps/desktop/src/main.tsx`, `apps/desktop/{package.json, tsconfig.json, vite.config.ts, vitest.config.ts, index.html, tailwind.config.js}`, root `package.json` (scripts), root `.gitignore`. **Files unchanged & verified:** `apps/desktop/electron/**` (no cross-imports to `src/`), `apps/desktop/public/focus-widget.html`, `packages/api-client/**`, `backend/**`.

> Note: the store still calls `window.api` (desktop-only) after this phase — that is expected; Q2 swaps it for the injected `dataClient`. The `Window.api` global declaration lives in `types.ts` and travels with the move, so the package still typechecks against the global.

---

### Task Q1-1: Pre-flight verification — capture the green baseline before moving anything

Establish that the current desktop build, desktop tests, and backend build are green so any breakage in later tasks is unambiguously caused by this phase.

- [ ] Confirm branch:
  ```bash
  cd "C:/Users/aboab/Desktop/Projects/sunsama clone" && git rev-parse --abbrev-ref HEAD
  ```
  Expect `feat/sp2-online-clients`. If not, STOP and surface the gap.
- [ ] Confirm working tree clean:
  ```bash
  cd "C:/Users/aboab/Desktop/Projects/sunsama clone" && git status --porcelain
  ```
  Expect empty output. If dirty, STOP and surface the gap.
- [ ] Install at root (workspaces):
  ```bash
  cd "C:/Users/aboab/Desktop/Projects/sunsama clone" && npm install
  ```
  Expect success.
- [ ] Baseline desktop build (renderer + electron):
  ```bash
  npm run build --workspace tmap
  ```
  Expect success (this proves the pre-move state is green).
- [ ] Baseline desktop unit tests:
  ```bash
  npm run test:run --workspace tmap
  ```
  Expect PASS (these are the `lib/*.test.ts` files that will move to `packages/app`).
- [ ] Baseline backend build (must stay unaffected throughout the phase):
  ```bash
  dotnet build backend/Tmap.sln
  ```
  Expect success.
- [ ] No commit (read-only verification task).

---

### Task Q1-2: Create the `packages/app` directory shell and its `package.json` (`@tmap/app`)

Create the new workspace package that will own the shared renderer. It declares the renderer runtime deps (moved out of `apps/desktop`) plus a workspace dependency on `@tmap/api-client`, and owns its own `vitest` test command.

- [ ] Create the directory:
  ```bash
  cd "C:/Users/aboab/Desktop/Projects/sunsama clone" && mkdir -p packages/app/src
  ```
- [ ] Write `packages/app/package.json` (full contents):
  ```json
  {
    "name": "@tmap/app",
    "version": "0.1.0",
    "private": true,
    "type": "module",
    "main": "./src/AppRoot.tsx",
    "types": "./src/AppRoot.tsx",
    "exports": {
      ".": "./src/AppRoot.tsx",
      "./store": "./src/store.ts",
      "./types": "./src/types.ts",
      "./index.css": "./src/index.css",
      "./tailwind.config.js": "./tailwind.config.js"
    },
    "scripts": {
      "test": "vitest run",
      "test:watch": "vitest",
      "typecheck": "tsc --noEmit"
    },
    "dependencies": {
      "@dnd-kit/core": "^6.3.1",
      "@dnd-kit/sortable": "^10.0.0",
      "@dnd-kit/utilities": "^3.2.2",
      "@radix-ui/react-context-menu": "^2.2.6",
      "@radix-ui/react-dialog": "^1.1.6",
      "@radix-ui/react-dropdown-menu": "^2.1.6",
      "@radix-ui/react-popover": "^1.1.6",
      "@radix-ui/react-scroll-area": "^1.2.3",
      "@radix-ui/react-tooltip": "^1.1.8",
      "@tiptap/extension-bubble-menu": "^3.20.5",
      "@tiptap/extension-image": "^3.20.5",
      "@tiptap/extension-placeholder": "^3.20.5",
      "@tiptap/pm": "^3.20.5",
      "@tiptap/react": "^3.20.5",
      "@tiptap/starter-kit": "^3.20.5",
      "@tmap/api-client": "*",
      "clsx": "^2.1.1",
      "date-fns": "^4.1.0",
      "lucide-react": "^0.474.0",
      "react": "^18.3.1",
      "react-dom": "^18.3.1",
      "recharts": "^3.8.1",
      "zustand": "^5.0.3"
    },
    "devDependencies": {
      "@types/react": "^18.3.0",
      "@types/react-dom": "^18.3.0",
      "@vitejs/plugin-react": "^4.3.4",
      "typescript": "^5.4.0",
      "vitest": "^3.2.4"
    }
  }
  ```
  > Rationale: every renderer runtime/dev dep listed in `apps/desktop/package.json` that the moved `src/**` consumes (dnd-kit, radix, tiptap, clsx, date-fns, lucide, react, recharts, zustand, react types, plugin-react, vitest, typescript) moves here. `@tmap/api-client` is added now so Q2 can import it without another workspace re-link. The `exports` map exposes `AppRoot`, plus the `store`/`types`/`index.css`/`tailwind.config.js` subpaths the apps need to import.
- [ ] No commit yet (committed together with the move in Q1-5).

---

### Task Q1-3: Write `packages/app` build/test configs (tsconfig, vite, vitest, tailwind, postcss) owning the `@/` alias

The package owns the `@/ → ./src` alias for Vite, TypeScript, and Vitest. Tailwind/PostCSS configs live here so the apps can extend them and Tailwind scans the package source.

- [ ] Write `packages/app/tsconfig.json` (full contents):
  ```json
  {
    "compilerOptions": {
      "composite": true,
      "target": "ES2022",
      "lib": ["ES2022", "DOM", "DOM.Iterable"],
      "jsx": "react-jsx",
      "moduleResolution": "bundler",
      "module": "ESNext",
      "strict": true,
      "esModuleInterop": true,
      "skipLibCheck": true,
      "forceConsistentCasingInFileNames": true,
      "resolveJsonModule": true,
      "isolatedModules": true,
      "declaration": true,
      "emitDeclarationOnly": true,
      "outDir": "./dist-types",
      "rootDir": "./src",
      "baseUrl": ".",
      "paths": {
        "@/*": ["./src/*"]
      }
    },
    "include": ["src/**/*", "src/**/*.d.ts"],
    "exclude": ["src/**/*.test.ts", "src/**/*.test.tsx", "dist-types", "node_modules"]
  }
  ```
  > `composite: true` + `emitDeclarationOnly` lets `apps/desktop`/`apps/web` reference this project; tests are excluded so app builds don't typecheck test files.
- [ ] Write `packages/app/tsconfig.node.json` (full contents — typechecks the package's own `vite.config.ts`/`vitest.config.ts`):
  ```json
  {
    "compilerOptions": {
      "composite": true,
      "target": "ES2022",
      "lib": ["ES2022"],
      "module": "ESNext",
      "moduleResolution": "bundler",
      "strict": true,
      "esModuleInterop": true,
      "skipLibCheck": true,
      "types": ["node"]
    },
    "include": ["vite.config.ts", "vitest.config.ts"]
  }
  ```
- [ ] Write `packages/app/vite.config.ts` (full contents — a shared library/dev config the apps re-use the alias from; not a build target itself but provides `react()` + alias for any package-local dev/preview):
  ```ts
  import { defineConfig } from 'vite';
  import react from '@vitejs/plugin-react';
  import path from 'path';

  // Shared base config for @tmap/app. The host apps (apps/desktop, apps/web)
  // define their own build targets and re-declare the @/ alias into this src tree.
  export default defineConfig({
    plugins: [react()],
    resolve: {
      alias: {
        '@': path.resolve(__dirname, './src'),
      },
    },
  });
  ```
- [ ] Write `packages/app/vitest.config.ts` (full contents — owns unit-test execution for mappers/ranking/auth in later phases plus the moved `lib/*.test.ts`):
  ```ts
  import { defineConfig } from 'vitest/config';
  import react from '@vitejs/plugin-react';
  import path from 'path';

  export default defineConfig({
    plugins: [react()],
    resolve: {
      alias: {
        '@': path.resolve(__dirname, './src'),
      },
    },
    test: {
      environment: 'node',
      include: ['src/**/*.test.ts', 'src/**/*.test.tsx'],
    },
  });
  ```
- [ ] Write `packages/app/tailwind.config.js` (full contents — the canonical theme; copied verbatim from the desktop config so the design tokens become package-owned and `content` points at the package src):
  ```js
  /** @type {import('tailwindcss').Config} */
  export default {
    content: ['./src/**/*.{js,ts,jsx,tsx}'],
    darkMode: 'class',
    theme: {
      extend: {
        colors: {
          surface: {
            50: '#f8fafc',
            100: '#f1f5f9',
            200: '#e2e8f0',
            300: '#cbd5e1',
            400: '#94a3b8',
            500: '#64748b',
            600: '#475569',
            700: '#334155',
            800: '#1e293b',
            900: '#0f172a',
            950: '#020617',
          },
          accent: {
            50: '#eff6ff',
            100: '#dbeafe',
            200: '#bfdbfe',
            300: '#93c5fd',
            400: '#60a5fa',
            500: '#3b82f6',
            600: '#2563eb',
            700: '#1d4ed8',
            800: '#1e40af',
            900: '#1e3a8a',
            950: '#172554',
          },
          success: {
            400: '#4ade80',
            500: '#22c55e',
            600: '#16a34a',
          },
          warning: {
            400: '#fbbf24',
            500: '#f59e0b',
            600: '#d97706',
          },
          danger: {
            400: '#f87171',
            500: '#ef4444',
            600: '#dc2626',
          },
        },
        fontFamily: {
          sans: ['Inter', 'system-ui', '-apple-system', 'sans-serif'],
        },
        fontSize: {
          '2xs': ['0.625rem', { lineHeight: '0.875rem' }],
        },
        animation: {
          'slide-in': 'slideIn 0.2s ease-out',
          'fade-in': 'fadeIn 0.15s ease-out',
          'scale-in': 'scaleIn 0.15s ease-out',
          'pulse-soft': 'pulseSoft 2s infinite',
        },
        keyframes: {
          slideIn: {
            '0%': { transform: 'translateX(-8px)', opacity: '0' },
            '100%': { transform: 'translateX(0)', opacity: '1' },
          },
          fadeIn: {
            '0%': { opacity: '0' },
            '100%': { opacity: '1' },
          },
          scaleIn: {
            '0%': { transform: 'scale(0.95)', opacity: '0' },
            '100%': { transform: 'scale(1)', opacity: '1' },
          },
          pulseSoft: {
            '0%, 100%': { opacity: '1' },
            '50%': { opacity: '0.7' },
          },
        },
      },
    },
    plugins: [],
  };
  ```
- [ ] Write `packages/app/postcss.config.js` (full contents):
  ```js
  export default {
    plugins: {
      tailwindcss: {},
      autoprefixer: {},
    },
  };
  ```
- [ ] No commit yet (committed with the move in Q1-5).

---

### Task Q1-4: `git mv` the entire renderer from `apps/desktop/src` into `packages/app/src` (history preserved)

Move every renderer file/tree. There are NO cross-boundary imports between `apps/desktop/electron` and `apps/desktop/src` (verified in pre-flight), and all renderer imports are relative or the `window.api` global, so relative imports survive the move unchanged. `main.tsx` is intentionally moved here and then REPLACED by a thin shell in `apps/desktop` (Q1-6) — moving it first keeps git history attached to the file content; we will overwrite the desktop one afterward.

- [ ] Move the renderer trees and files (run each; `git mv` preserves history):
  ```bash
  cd "C:/Users/aboab/Desktop/Projects/sunsama clone"
  git mv apps/desktop/src/components packages/app/src/components
  git mv apps/desktop/src/lib packages/app/src/lib
  git mv apps/desktop/src/App.tsx packages/app/src/App.tsx
  git mv apps/desktop/src/store.ts packages/app/src/store.ts
  git mv apps/desktop/src/types.ts packages/app/src/types.ts
  git mv apps/desktop/src/priorityUtils.ts packages/app/src/priorityUtils.ts
  git mv apps/desktop/src/useTextDirection.ts packages/app/src/useTextDirection.ts
  git mv apps/desktop/src/index.css packages/app/src/index.css
  git mv apps/desktop/src/main.tsx packages/app/src/main.tsx
  ```
- [ ] Verify `apps/desktop/src` is now empty (and remove the empty dir if git left it):
  ```bash
  cd "C:/Users/aboab/Desktop/Projects/sunsama clone" && ls -la apps/desktop/src 2>/dev/null; echo "---"; git status --short
  ```
  Expect: `apps/desktop/src` empty or absent; staged renames listed as `R  apps/desktop/src/... -> packages/app/src/...`.
- [ ] Delete the just-moved `packages/app/src/main.tsx` (it is the OLD desktop entry; the package does not need an entry HTML mount — the apps provide their own thin `main.tsx`). We keep its history via the move, then remove it:
  ```bash
  cd "C:/Users/aboab/Desktop/Projects/sunsama clone" && git rm packages/app/src/main.tsx
  ```
  > Rationale: `packages/app` exposes `AppRoot` (a component), not a DOM-mount entry. Each app owns its `main.tsx` (`createRoot(...).render(<AppRoot .../>)`). Moving-then-removing keeps `git log --follow` clean for the file's lineage.
- [ ] Confirm moved files compile structurally (no path rewrites needed) — quick sanity grep that no moved file imports `../electron` or an `apps/desktop`-relative path:
  ```bash
  cd "C:/Users/aboab/Desktop/Projects/sunsama clone" && grep -rn "apps/desktop\|\.\./\.\./electron\|\.\./electron" packages/app/src 2>/dev/null | head; echo "grep-exit:$?"
  ```
  Expect: no matches (`grep-exit:1`). If any match appears, STOP and surface it.
- [ ] No commit yet (committed in Q1-5 after AppRoot stub exists, so the package is self-consistent in one commit).

---

### Task Q1-5: Add the temporary `AppRoot.tsx` stub and commit the extraction

`AppRoot` is the entry component both apps mount. In this phase it is a STUB that simply renders the existing `App` (which still calls `window.api` internally — fine for desktop; web won't have `window.api` until Q2, but it only matters at runtime, not build time). Its props are typed to the future shape (`{ dataClient, platform }`) using minimal compile-only placeholder types so Q2 can fill them in without changing call sites. Then commit the whole extraction as one coherent change.

- [ ] Write `packages/app/src/AppRoot.tsx` (full contents):
  ```tsx
  import React from 'react';
  import App from './App';

  /**
   * Q1 STUB — temporary AppRoot.
   *
   * Both apps/desktop and apps/web mount this component. In Q1 it simply renders
   * the existing <App />, which still talks to `window.api` (desktop-only) internally.
   * Q2 introduces the real DataClient seam and refactors the store to call
   * `props.dataClient` instead of window.api; Q3+ wires the Platform/auth shells.
   *
   * The prop shape is fixed now (so host main.tsx call sites are stable), but the
   * concrete DataClient/Platform types arrive in later phases. We use minimal
   * compile-only placeholders here and DO NOT consume the props yet.
   */
  export interface AppRootProps {
    /** Q2: typed as DataClient (packages/app/src/data/DataClient.ts). */
    dataClient?: unknown;
    /** Q3: typed as Platform (packages/app/src/platform/Platform.ts). */
    platform?: unknown;
  }

  export default function AppRoot(_props: AppRootProps): React.ReactElement {
    // Props intentionally unused in Q1; App still self-wires via window.api.
    return <App />;
  }
  ```
- [ ] Sanity: confirm the package's own type project compiles in isolation (this also proves the moved `window.api` global declaration in `types.ts` is self-contained):
  ```bash
  cd "C:/Users/aboab/Desktop/Projects/sunsama clone" && npm install && npm run typecheck --workspace @tmap/app
  ```
  Expect success. (If `@tmap/api-client` isn't yet importable, that's fine — Q1 code doesn't import it; the dep is declared for Q2.)
- [ ] Run the moved unit tests from the new home:
  ```bash
  npm run test --workspace @tmap/app
  ```
  Expect PASS (the `lib/*.test.ts` suites that moved in Q1-4).
- [ ] Stage and commit the extraction:
  ```bash
  cd "C:/Users/aboab/Desktop/Projects/sunsama clone" && git add -A && git commit -m "$(cat <<'EOF'
  refactor(app): extract shared renderer into packages/app (@tmap/app)

  git mv apps/desktop/src/{components,lib,App.tsx,store.ts,types.ts,
  priorityUtils.ts,useTextDirection.ts,index.css} into packages/app/src,
  preserving history. Add @tmap/app package.json, tsconfig(+node), vite/vitest
  configs (owning the @/ -> ./src alias), tailwind/postcss, and a temporary
  AppRoot stub that renders the existing App. Desktop shell + web entry wiring
  follow in subsequent Q1 tasks.

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  EOF
  )"
  ```
  > Note: At THIS commit `apps/desktop` is temporarily broken (its old `src/main.tsx` import path is gone). That is acceptable mid-phase; Q1-6/Q1-7 restore both apps to green and the phase HARD GATE (Q1-9) requires both builds to pass before the phase is considered done. If your workflow forbids a red intermediate commit, fold Q1-6/Q1-7 file writes into this same commit.

---

### Task Q1-6: Rebuild `apps/desktop` as a thin shell — new `main.tsx`, configs point at `@tmap/app`, deps slimmed

`apps/desktop` becomes an Electron shell: the renderer is the package. Its `src/main.tsx` mounts `AppRoot` from `@tmap/app`, imports the package CSS, and keeps `react-dom` mounting. Vite keeps `base: './'` (file:// prod), the electron plugin block unchanged, and re-declares the `@/` alias into the package src so any `@/`-prefixed import inside the moved code resolves through the app's bundler too. Tailwind extends the package config and scans the package source. The renderer-only deps move out (they now come transitively from `@tmap/app`); desktop keeps electron/build/IPC-side deps + `@tmap/app`.

- [ ] Recreate the desktop src dir and write the thin `apps/desktop/src/main.tsx` (full contents):
  ```bash
  cd "C:/Users/aboab/Desktop/Projects/sunsama clone" && mkdir -p apps/desktop/src
  ```
  ```tsx
  import React from 'react';
  import ReactDOM from 'react-dom/client';
  import AppRoot from '@tmap/app';
  import '@tmap/app/index.css';

  // Q1: thin Electron renderer entry. AppRoot is the @tmap/app stub (renders <App />,
  // which still self-wires via window.api on desktop). Q3 builds DesktopPlatform +
  // HttpDataClient here and passes them: <AppRoot dataClient={...} platform={...} />.
  ReactDOM.createRoot(document.getElementById('root')!).render(
    <React.StrictMode>
      <AppRoot />
    </React.StrictMode>,
  );
  ```
- [ ] Overwrite `apps/desktop/vite.config.ts` (full contents — keep electron plugin + `base: './'`; alias `@` into the PACKAGE src so moved `@/` imports resolve; add `@tmap/api-client` + `@tmap/app` to `optimizeDeps` so Vite pre-bundles workspace TS sources cleanly):
  ```ts
  import { defineConfig } from 'vite';
  import react from '@vitejs/plugin-react';
  import electronSimple from 'vite-plugin-electron/simple';
  import path from 'path';

  export default defineConfig({
    base: './',
    plugins: [
      react(),
      electronSimple({
        main: {
          entry: 'electron/main.ts',
          onstart(args) {
            // Remove ELECTRON_RUN_AS_NODE from env so Electron starts in app mode
            const env = { ...process.env };
            delete env.ELECTRON_RUN_AS_NODE;
            args.startup(['.', '--no-sandbox'], { env });
          },
          vite: {
            build: {
              outDir: 'dist-electron',
              rollupOptions: {
                external: ['electron', 'path', 'fs', 'os', 'url', 'sql.js', 'uuid'],
              },
            },
          },
        },
        preload: {
          input: 'electron/preload.ts',
          vite: {
            build: {
              outDir: 'dist-electron',
              rollupOptions: {
                external: ['electron'],
              },
            },
          },
        },
      }),
    ],
    resolve: {
      alias: {
        // The @/ alias now points into the shared package (where the renderer lives).
        '@': path.resolve(__dirname, '../../packages/app/src'),
      },
    },
    optimizeDeps: {
      include: ['@tmap/app', '@tmap/api-client'],
    },
    build: {
      outDir: 'dist',
    },
  });
  ```
  > `base: './'` is added explicitly (the old config set none) to satisfy the spec's file:// prod hard gate.
- [ ] Overwrite `apps/desktop/tsconfig.json` (full contents — point the renderer typecheck at the PACKAGE src via `@/`, include the thin `src/main.tsx`, reference both the package project and the electron project):
  ```json
  {
    "compilerOptions": {
      "target": "ES2022",
      "lib": ["ES2022", "DOM", "DOM.Iterable"],
      "outDir": "./dist",
      "jsx": "react-jsx",
      "moduleResolution": "bundler",
      "module": "ESNext",
      "strict": true,
      "esModuleInterop": true,
      "skipLibCheck": true,
      "forceConsistentCasingInFileNames": true,
      "resolveJsonModule": true,
      "isolatedModules": true,
      "noEmit": true,
      "baseUrl": ".",
      "paths": {
        "@/*": ["../../packages/app/src/*"]
      }
    },
    "include": ["src/**/*"],
    "references": [
      { "path": "../../packages/app/tsconfig.json" },
      { "path": "./tsconfig.electron.json" }
    ]
  }
  ```
  > `noEmit: true` + `references` to the package: `tsc -b`/`tsc` now typechecks only the thin shell and delegates the package's own typecheck to its referenced project. The desktop build script (next bullet) keeps `vite build` producing the actual renderer bundle.
- [ ] Overwrite `apps/desktop/vitest.config.ts` (full contents — desktop no longer hosts renderer unit tests; they live in `@tmap/app`. Keep a valid config that points the alias at the package and includes any future desktop-shell tests):
  ```ts
  import { defineConfig } from 'vitest/config';
  import react from '@vitejs/plugin-react';
  import path from 'path';

  export default defineConfig({
    plugins: [react()],
    resolve: {
      alias: {
        '@': path.resolve(__dirname, '../../packages/app/src'),
      },
    },
    test: {
      environment: 'node',
      include: ['src/**/*.test.ts', 'src/**/*.test.tsx'],
      passWithNoTests: true,
    },
  });
  ```
- [ ] Overwrite `apps/desktop/tailwind.config.js` (full contents — extend the package's canonical theme and scan BOTH the local index.html and the package source so utility classes in moved components are emitted):
  ```js
  import sharedConfig from '@tmap/app/tailwind.config.js';

  /** @type {import('tailwindcss').Config} */
  export default {
    presets: [sharedConfig],
    content: [
      './index.html',
      './src/**/*.{js,ts,jsx,tsx}',
      '../../packages/app/src/**/*.{js,ts,jsx,tsx}',
    ],
  };
  ```
- [ ] Edit `apps/desktop/package.json` — slim the renderer deps and add the workspace deps. Apply these exact changes:
  - Change the `build` script so the renderer typecheck is `noEmit` (vite produces the bundle) while electron still emits:
    - FROM: `"build": "tsc && vite build && tsc -p tsconfig.electron.json",`
    - TO:   `"build": "tsc -p tsconfig.json --noEmit && vite build && tsc -p tsconfig.electron.json",`
  - In `dependencies`: REMOVE the renderer-only packages now provided by `@tmap/app` (`@dnd-kit/core`, `@dnd-kit/sortable`, `@dnd-kit/utilities`, all `@radix-ui/*`, all `@tiptap/*`, `clsx`, `date-fns`, `lucide-react`, `recharts`, `zustand`). KEEP `react`, `react-dom` (Vite/JSX runtime for the thin entry), `electron-updater`, `sql.js`, `uuid` (electron-side dormant deps). ADD `"@tmap/app": "*"` and `"@tmap/api-client": "*"`.
  - In `devDependencies`: KEEP everything electron/build-related (`@types/node`, `@types/react`, `@types/react-dom`, `@vitejs/plugin-react`, `autoprefixer`, `electron`, `electron-builder`, `png-to-ico`, `postcss`, `prettier`, `sharp`, `tailwindcss`, `typescript`, `vite`, `vite-plugin-electron`, `vite-plugin-electron-renderer`, `vitest`).
  - Resulting `apps/desktop/package.json` (full contents):
  ```json
  {
    "name": "tmap",
    "version": "1.0.0",
    "private": true,
    "description": "TMap - Task Map: A daily planning workspace with task management and time blocking",
    "main": "./dist-electron/main.js",
    "scripts": {
      "dev": "vite",
      "build": "tsc -p tsconfig.json --noEmit && vite build && tsc -p tsconfig.electron.json",
      "preview": "vite preview",
      "package": "npm run build && electron-builder",
      "format": "prettier --write \"**/*.{ts,tsx,css,json}\"",
      "test": "vitest run",
      "test:run": "vitest run",
      "gen:api-client": "npm run gen --workspace @tmap/api-client"
    },
    "dependencies": {
      "@tmap/api-client": "*",
      "@tmap/app": "*",
      "electron-updater": "^6.3.9",
      "react": "^18.3.1",
      "react-dom": "^18.3.1",
      "sql.js": "^1.12.0",
      "uuid": "^11.1.0"
    },
    "devDependencies": {
      "@types/node": "^22.0.0",
      "@types/react": "^18.3.0",
      "@types/react-dom": "^18.3.0",
      "@types/uuid": "^10.0.0",
      "@vitejs/plugin-react": "^4.3.4",
      "autoprefixer": "^10.4.20",
      "electron": "^33.3.1",
      "electron-builder": "^26.8.1",
      "png-to-ico": "^3.0.1",
      "postcss": "^8.4.49",
      "prettier": "^3.2.0",
      "sharp": "^0.33.5",
      "tailwindcss": "^3.4.17",
      "typescript": "^5.4.0",
      "vite": "^6.0.0",
      "vite-plugin-electron": "^0.29.0",
      "vite-plugin-electron-renderer": "^0.14.6",
      "vitest": "^3.2.4"
    },
    "build": {
      "appId": "com.tmap.app",
      "productName": "TMap",
      "directories": {
        "output": "out"
      },
      "win": {
        "target": "nsis",
        "icon": "build/icon.ico",
        "forceCodeSigning": false,
        "signAndEditExecutable": false
      },
      "nsis": {
        "oneClick": false,
        "allowToChangeInstallationDirectory": true,
        "createDesktopShortcut": true,
        "createStartMenuShortcut": true,
        "shortcutName": "TMap"
      },
      "files": [
        "dist/**/*",
        "dist-electron/**/*"
      ],
      "extraResources": [
        {
          "from": "../../node_modules/sql.js/dist/sql-wasm.wasm",
          "to": "sql-wasm.wasm"
        }
      ]
    }
  }
  ```
  > `index.html` is unchanged — it still loads `/src/main.tsx`, which is now the thin shell. `focus-widget.html` stays untouched in `apps/desktop/public` and the Vite build keeps copying `public/**` into `dist/` automatically.
- [ ] Re-link workspaces and build the desktop app (HARD GATE for this task):
  ```bash
  cd "C:/Users/aboab/Desktop/Projects/sunsama clone" && npm install && npm run build --workspace tmap
  ```
  Expect success: renderer bundle in `apps/desktop/dist/` (with `index.html` referencing assets via relative `./` paths because `base: './'`), `dist-electron/main.js` + `preload`, and `apps/desktop/dist/focus-widget.html` present.
- [ ] Verify the focus widget still ships and the prod HTML uses relative asset paths (file:// safety):
  ```bash
  cd "C:/Users/aboab/Desktop/Projects/sunsama clone" && ls apps/desktop/dist/focus-widget.html && grep -c 'src="./assets\|href="./assets' apps/desktop/dist/index.html
  ```
  Expect: the widget file listed, and the grep count ≥ 1 (assets referenced as `./assets/...`, not `/assets/...`).
- [ ] No commit yet (commit at end of Q1-7 with the web app, so "both apps build" is one green commit).

---

### Task Q1-7: Scaffold `apps/web` as a thin Vite web entry mounting `@tmap/app`

Create the brand-new web app: a plain Vite + React SPA (no Electron) with `base: '/'` (env-overridable), its own `index.html`, thin `main.tsx` mounting `AppRoot`, tailwind extending the package config, and a workspace dep on `@tmap/app` + `@tmap/api-client`.

- [ ] Create dirs:
  ```bash
  cd "C:/Users/aboab/Desktop/Projects/sunsama clone" && mkdir -p apps/web/src
  ```
- [ ] Write `apps/web/package.json` (full contents):
  ```json
  {
    "name": "@tmap/web",
    "version": "0.1.0",
    "private": true,
    "type": "module",
    "scripts": {
      "dev": "vite",
      "build": "tsc -p tsconfig.json --noEmit && vite build",
      "preview": "vite preview",
      "format": "prettier --write \"**/*.{ts,tsx,css,json,html}\""
    },
    "dependencies": {
      "@tmap/api-client": "*",
      "@tmap/app": "*",
      "react": "^18.3.1",
      "react-dom": "^18.3.1"
    },
    "devDependencies": {
      "@types/react": "^18.3.0",
      "@types/react-dom": "^18.3.0",
      "@vitejs/plugin-react": "^4.3.4",
      "autoprefixer": "^10.4.20",
      "postcss": "^8.4.49",
      "prettier": "^3.2.0",
      "tailwindcss": "^3.4.17",
      "typescript": "^5.4.0",
      "vite": "^6.0.0"
    }
  }
  ```
- [ ] Write `apps/web/index.html` (full contents — same fonts/dark shell as desktop, but NO Electron titlebar concerns; uses the standard root mount). Note web shell stays `overflow-hidden`/full-screen consistent with the app layout:
  ```html
  <!DOCTYPE html>
  <html lang="en" class="dark">
    <head>
      <meta charset="UTF-8" />
      <meta name="viewport" content="width=device-width, initial-scale=1.0" />
      <title>TMap</title>
      <link rel="preconnect" href="https://fonts.googleapis.com" />
      <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin />
      <link
        href="https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700&display=swap"
        rel="stylesheet"
      />
    </head>
    <body class="bg-surface-950 text-surface-100 font-sans antialiased overflow-hidden">
      <div id="root"></div>
      <script type="module" src="/src/main.tsx"></script>
    </body>
  </html>
  ```
- [ ] Write `apps/web/src/main.tsx` (full contents):
  ```tsx
  import React from 'react';
  import ReactDOM from 'react-dom/client';
  import AppRoot from '@tmap/app';
  import '@tmap/app/index.css';

  // Q1: thin web renderer entry. AppRoot is the @tmap/app stub (renders <App />).
  // NOTE: <App /> still references window.api at runtime; the web host has no
  // window.api yet, so deep interactions will error at runtime until Q2 injects a
  // DataClient and Q3 provides WebPlatform. Q1's gate is BUILD-only for web.
  ReactDOM.createRoot(document.getElementById('root')!).render(
    <React.StrictMode>
      <AppRoot />
    </React.StrictMode>,
  );
  ```
- [ ] Write `apps/web/vite.config.ts` (full contents — `base` env-driven defaulting to `'/'`; alias `@` into the package src; pre-bundle the workspace packages):
  ```ts
  import { defineConfig, loadEnv } from 'vite';
  import react from '@vitejs/plugin-react';
  import path from 'path';

  export default defineConfig(({ mode }) => {
    const env = loadEnv(mode, process.cwd(), '');
    return {
      base: env.VITE_BASE_PATH ?? '/',
      plugins: [react()],
      resolve: {
        alias: {
          '@': path.resolve(__dirname, '../../packages/app/src'),
        },
      },
      optimizeDeps: {
        include: ['@tmap/app', '@tmap/api-client'],
      },
      server: {
        port: 5174,
      },
      build: {
        outDir: 'dist',
      },
    };
  });
  ```
  > Port `5174` matches the CORS dev origin the backend allowlists in SP2 §0.6. `VITE_API_BASE_URL` consumption is a Q2+ concern (config.ts); Q1 only needs the build to pass.
- [ ] Write `apps/web/tsconfig.json` (full contents):
  ```json
  {
    "compilerOptions": {
      "target": "ES2022",
      "lib": ["ES2022", "DOM", "DOM.Iterable"],
      "jsx": "react-jsx",
      "moduleResolution": "bundler",
      "module": "ESNext",
      "strict": true,
      "esModuleInterop": true,
      "skipLibCheck": true,
      "forceConsistentCasingInFileNames": true,
      "resolveJsonModule": true,
      "isolatedModules": true,
      "noEmit": true,
      "baseUrl": ".",
      "paths": {
        "@/*": ["../../packages/app/src/*"]
      }
    },
    "include": ["src/**/*"],
    "references": [{ "path": "../../packages/app/tsconfig.json" }]
  }
  ```
- [ ] Write `apps/web/tailwind.config.js` (full contents):
  ```js
  import sharedConfig from '@tmap/app/tailwind.config.js';

  /** @type {import('tailwindcss').Config} */
  export default {
    presets: [sharedConfig],
    content: [
      './index.html',
      './src/**/*.{js,ts,jsx,tsx}',
      '../../packages/app/src/**/*.{js,ts,jsx,tsx}',
    ],
  };
  ```
- [ ] Write `apps/web/postcss.config.js` (full contents):
  ```js
  export default {
    plugins: {
      tailwindcss: {},
      autoprefixer: {},
    },
  };
  ```
- [ ] Write `apps/web/.gitignore` (full contents):
  ```
  node_modules/
  dist/
  *.tsbuildinfo
  ```
- [ ] Re-link workspaces and build the web app (HARD GATE for this task):
  ```bash
  cd "C:/Users/aboab/Desktop/Projects/sunsama clone" && npm install && npm run build --workspace @tmap/web
  ```
  Expect success: `apps/web/dist/index.html` + `apps/web/dist/assets/**`, with assets referenced as absolute `/assets/...` (because `base: '/'`).
- [ ] Verify web prod uses root-absolute asset paths (proves `base: '/'` distinct from desktop's `'./'`):
  ```bash
  cd "C:/Users/aboab/Desktop/Projects/sunsama clone" && grep -c 'src="/assets\|href="/assets' apps/web/dist/index.html
  ```
  Expect count ≥ 1.
- [ ] No commit yet (commit in Q1-9 after the full-phase gate).

---

### Task Q1-8: Update root scripts + root `.gitignore`, and confirm `focus-widget.html` ownership

Wire convenience scripts at the monorepo root so the dual-build gate is one command, and ensure ignore rules cover the new package/app dist + type artifacts. The root `.gitignore` already globs `**/dist/` and `*.tsbuildinfo`; add the package's `dist-types` for completeness.

- [ ] Overwrite root `package.json` (full contents — add build/test orchestration scripts; keep workspaces):
  ```json
  {
    "name": "tmap-monorepo",
    "private": true,
    "workspaces": ["apps/*", "packages/*"],
    "scripts": {
      "build:desktop": "npm run build --workspace tmap",
      "build:web": "npm run build --workspace @tmap/web",
      "build:apps": "npm run build:desktop && npm run build:web",
      "test:app": "npm run test --workspace @tmap/app",
      "gen:api-client": "npm run gen --workspace @tmap/api-client"
    }
  }
  ```
- [ ] Edit root `.gitignore` — add the package type-emit dir. Apply this change:
  - FROM:
    ```
    # generated TS client output (if emitted into the repo)
    packages/api-client/dist/
    ```
  - TO:
    ```
    # generated TS client output (if emitted into the repo)
    packages/api-client/dist/

    # @tmap/app emitted declaration output
    packages/app/dist-types/
    ```
- [ ] Confirm `focus-widget.html` is still tracked under `apps/desktop/public` and was NOT moved:
  ```bash
  cd "C:/Users/aboab/Desktop/Projects/sunsama clone" && git ls-files apps/desktop/public/focus-widget.html; ls apps/desktop/public/focus-widget.html
  ```
  Expect both to list the file. If missing, STOP and surface the gap.
- [ ] No commit yet (commit in Q1-9).

---

### Task Q1-9: Full-phase HARD GATE — clean install, both apps build (incl. desktop file://), package tests, backend unaffected; then commit

This is the phase acceptance gate (spec §10.2 + the prompt's hard gates). Run from a clean dependency state so a fresh checkout reproduces green.

- [ ] Clean node_modules to prove a from-scratch install works (Windows-safe):
  ```bash
  cd "C:/Users/aboab/Desktop/Projects/sunsama clone" && rm -rf node_modules apps/desktop/node_modules apps/web/node_modules packages/app/node_modules packages/api-client/node_modules
  ```
- [ ] Root install (HARD GATE — must succeed):
  ```bash
  cd "C:/Users/aboab/Desktop/Projects/sunsama clone" && npm install
  ```
  Expect success.
- [ ] Build BOTH apps (HARD GATE):
  ```bash
  cd "C:/Users/aboab/Desktop/Projects/sunsama clone" && npm run build:apps
  ```
  Expect both `tmap` (desktop) and `@tmap/web` builds to succeed.
- [ ] Re-confirm desktop file:// base + focus widget emission (HARD GATE):
  ```bash
  cd "C:/Users/aboab/Desktop/Projects/sunsama clone" && ls apps/desktop/dist/focus-widget.html && grep -c 'src="./assets\|href="./assets' apps/desktop/dist/index.html && grep -c 'src="/assets\|href="/assets' apps/web/dist/index.html
  ```
  Expect: focus-widget.html present; desktop count ≥ 1 (relative `./assets`); web count ≥ 1 (absolute `/assets`).
- [ ] Package unit tests (the moved `lib/*.test.ts`) run from `@tmap/app` (HARD GATE):
  ```bash
  cd "C:/Users/aboab/Desktop/Projects/sunsama clone" && npm run test:app
  ```
  Expect PASS.
- [ ] Backend unaffected (HARD GATE):
  ```bash
  cd "C:/Users/aboab/Desktop/Projects/sunsama clone" && dotnet build backend/Tmap.sln
  ```
  Expect success.
- [ ] Manual smoke (desktop dev) — verify the extracted renderer still runs end-to-end via Electron + `window.api`:
  ```bash
  cd "C:/Users/aboab/Desktop/Projects/sunsama clone" && npm run dev --workspace tmap
  ```
  Verify: the app window opens, the sidebar/task list/timeline render, you can create a task (desktop store still uses `window.api`). Close it. (No web dev smoke needed this phase — web has no data layer until Q2; build-only gate applies to web.)
- [ ] Commit the desktop-shell + web-app + root-script changes:
  ```bash
  cd "C:/Users/aboab/Desktop/Projects/sunsama clone" && git add -A && git commit -m "$(cat <<'EOF'
  feat(monorepo): dual-build skeleton — desktop shell + apps/web entry on @tmap/app

  apps/desktop becomes a thin Electron shell: src/main.tsx mounts AppRoot from
  @tmap/app, vite base './' (file:// prod), @/ alias + tailwind point at the
  package, renderer-only deps removed (now transitive via @tmap/app).
  apps/web is a new thin Vite SPA (base '/', port 5174) mounting the same AppRoot.
  focus-widget.html stays in apps/desktop/public and is still emitted to dist.
  Root scripts build/test both targets. Both apps build; @tmap/app tests pass;
  backend unaffected.

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  EOF
  )"
  ```
- [ ] Final verification — working tree clean and history shows the rename lineage:
  ```bash
  cd "C:/Users/aboab/Desktop/Projects/sunsama clone" && git status --porcelain && git log --oneline -3 && git log --follow --oneline -- packages/app/src/store.ts | head -3
  ```
  Expect: empty `status --porcelain`; the two Q1 commits on top; `--follow` showing store.ts history predating the move (history preserved).

---

### Task Q1-10: Phase exit checklist (acceptance for Q1 only)

Confirm every Q1 deliverable and surface any gap to the orchestrator rather than proceeding.

- [ ] `packages/app` exists with name `@tmap/app`, owns the `@/ → ./src` alias in vite + tsconfig + vitest, and exposes `AppRoot` (stub), `./store`, `./types`, `./index.css`, `./tailwind.config.js`.
- [ ] Renderer (`components/`, `lib/`, `App.tsx`, `store.ts`, `types.ts`, `priorityUtils.ts`, `useTextDirection.ts`, `index.css`) moved via `git mv` (history preserved; `git log --follow` confirms).
- [ ] `apps/desktop` is a thin shell: `src/main.tsx` mounts `@tmap/app`'s `AppRoot`; `vite.config.ts` has `base: './'`; electron plugin + IPC untouched; renderer-only deps removed; `focus-widget.html` stays in `public/` and emits to `dist/`.
- [ ] `apps/web` is a new thin entry: `base: '/'`, port 5174, mounts the same `AppRoot`; no Electron.
- [ ] HARD GATES all green: clean `npm install` at root; `npm run build:apps` (desktop file:// + web `/`); `npm run test:app`; `dotnet build backend/Tmap.sln`.
- [ ] Known/accepted carry-overs documented for Q2+: the store still calls `window.api` (works on desktop; web build compiles but runtime data ops error until Q2 injects `DataClient`); `AppRoot` props (`dataClient`/`platform`) are typed `unknown` placeholders, unused in Q1. If any of these are NOT acceptable to the orchestrator, surface it now.
- [ ] No new commit (verification-only).

---

**Surfaced gaps / notes for the orchestrator (not blocking Q1):**
- The spec's §1 tree lists `lib/ hooks/` under `packages/app/src`, but the current renderer has no `hooks/` dir — `useTextDirection.ts` and `priorityUtils.ts` sit at `src/` root. Q1 moves them as-is (no `hooks/` dir created). Later phases that add `reminders/`, `data/`, `auth/`, `platform/` can introduce `hooks/` if needed; flagging so no phase assumes a pre-existing `hooks/`.
- `window.api`'s global `interface Window` declaration lives in the moved `types.ts` (lines ~298+). It travels with the package and keeps `@tmap/app` self-typechecking. Q2 must decide whether that global stays (desktop-only) or is replaced by the `DataClient`/`Platform` seam — out of Q1 scope.
- `apps/desktop/test-electron.js` (a manual env probe) is left untouched in `apps/desktop`; it is not a renderer file and does not belong in the package.

---

## Phase Q2 — DataClient seam + mappers + ranking + store refactor

This phase builds the data seam that the store talks to instead of `window.api.*`. It **creates**: `packages/app/src/data/ranking.ts`, `packages/app/src/data/mappers.ts`, `packages/app/src/data/DataClient.ts`, `packages/app/src/data/HttpDataClient.ts`, and the vitest specs `packages/app/src/data/ranking.test.ts`, `packages/app/src/data/mappers.test.ts`, `packages/app/src/data/HttpDataClient.test.ts`. It **modifies**: `packages/app/src/types.ts` (Task: `project` → `projectId`, add `changeSeq`; this phase only edits the type and the store — the component sweep is Q4) and `packages/app/src/store.ts` (every data-touching action moves from `window.api.*` to an injected `dataClient: DataClient`; a `setDataClient`/init seam is added so `AppRoot` can wire it in Q6). It does **not** touch desktop-only `window.api` groups (`app`/`data`/`focus`/`on`/`off`) — those move to `Platform` in Q5. Assumes Q0 (regenerated `@tmap/api-client` with typed auth + note-groups reorder + full ensure-instances) and Q1 (`packages/app` package scaffolding: `package.json` name `@tmap/app`, `vitest.config.ts`, `tsconfig.json`, the `@/` alias, and the `apps/desktop/src` → `packages/app/src` file move) are complete. The current `apps/desktop/src/{types.ts,store.ts}` shown here are the files that will live at `packages/app/src/{types.ts,store.ts}` after the Q1 move; all Q2 paths below are the `packages/app` paths.

> Server ranking contract this phase mirrors (from `backend/src/Tmap.Api/Common/Ranking.cs`): keys are plain lowercase strings compared lexicographically; the default seed is `"n"`; `RankAfter(prev)` increments the last char (or appends `"n"` when it is `'z'`); `RankBetween(prev,next)` pads `prev` to `next`'s length with `'a'`, walks from the right for the first position where `next[i] - paddedPrev[i] > 1`, returns `prefix + midChar`, else falls back to `RankAfter(prev)`. `ranking.ts` must reproduce this exactly so client-computed ranks interleave cleanly with server-seeded ones.

---

### Task Q2-1: `rankBetween` — failing test for the empty/append/prepend/between cases

- [ ] Create `packages/app/src/data/ranking.test.ts` with the following content:

```ts
import { describe, it, expect } from 'vitest';
import { rankBetween } from './ranking';

// Mirrors backend Tmap.Api.Common.Ranking. Keys are plain lowercase strings
// compared with default JS string `<`. Seed is "n"; append increments last char.
describe('rankBetween', () => {
  it('returns the seed "n" for an empty container (no prev, no next)', () => {
    expect(rankBetween(null, null)).toBe('n');
  });

  it('appends after prev when next is null (increment last char)', () => {
    expect(rankBetween('n', null)).toBe('o');
    expect(rankBetween('a', null)).toBe('b');
  });

  it('appends a new level when prev ends in z and next is null', () => {
    expect(rankBetween('z', null)).toBe('zn');
    expect(rankBetween('az', null)).toBe('azn');
  });

  it('prepends before next when prev is null (midpoint with padded "a")', () => {
    // prev padded to "a", next "n": gap a..n > 1 -> mid of 'a'(97) and 'n'(110) = 103 'g'
    expect(rankBetween(null, 'n')).toBe('g');
  });

  it('returns a key strictly between two adjacent-but-gapped keys', () => {
    const mid = rankBetween('a', 'c'); // gap a..c > 1 -> 'b'
    expect(mid).toBe('b');
    expect('a' < mid && mid < 'c').toBe(true);
  });

  it('extends to a new char level when neighbors are tight (b,c -> "bn")', () => {
    const mid = rankBetween('b', 'c'); // no gap at last index -> RankAfter('b') = 'c'? no: fallback
    expect('b' < mid && mid < 'c').toBe(true);
  });

  it('finds a midpoint between multi-char ranks', () => {
    const mid = rankBetween('aa', 'ac'); // index1: a..c gap>1 -> 'ab'
    expect(mid).toBe('ab');
    expect('aa' < mid && mid < 'ac').toBe(true);
  });

  it('is always strictly increasing for a sequence of appends', () => {
    let prev: string | null = null;
    const keys: string[] = [];
    for (let i = 0; i < 50; i++) {
      const k = rankBetween(prev, null);
      keys.push(k);
      prev = k;
    }
    const sorted = [...keys].sort();
    expect(keys).toEqual(sorted);
  });

  it('produces strictly-between keys when repeatedly inserting in the same gap', () => {
    let lo = 'a';
    const hi = 'z';
    for (let i = 0; i < 20; i++) {
      const mid = rankBetween(lo, hi);
      expect(lo < mid && mid < hi).toBe(true);
      lo = mid;
    }
  });
});
```

- [ ] Run `npm test` from `packages/app` and confirm these tests **FAIL** (module `./ranking` not found / `rankBetween` undefined).

### Task Q2-2: implement `ranking.ts` (mirror the server scheme) — make Q2-1 pass

- [ ] Create `packages/app/src/data/ranking.ts` with the full implementation:

```ts
/**
 * Client mirror of the server's lexicographic rank scheme
 * (backend Tmap.Api.Common.Ranking). Keys are plain lowercase ASCII strings
 * compared with the default string ordering. A move/insert produces a single
 * key that sorts strictly between its neighbors so reorder touches one row.
 *
 * Contract (must match the server byte-for-byte for interleaving to work):
 *  - empty container seed: "n"
 *  - rankAfter(prev): increment the last char, or append "n" when it is 'z'
 *  - rankBetween(prev, next): pad prev to next.length with 'a', walk from the
 *    right for the first index where next[i] - paddedPrev[i] > 1, return
 *    prefix + midChar; otherwise fall back to rankAfter(prev).
 */

const SEED = 'n'; // midpoint of 'a'..'z'

/** Returns a rank that sorts strictly after `prev` (seed when prev is empty). */
export function rankAfter(prev: string | null): string {
  if (!prev) {
    return SEED;
  }
  const last = prev.charCodeAt(prev.length - 1);
  if (last < 'z'.charCodeAt(0)) {
    return prev.slice(0, -1) + String.fromCharCode(last + 1);
  }
  return prev + SEED;
}

/** Returns a rank that sorts strictly between `prev` and `next`. */
export function rankBetween(prev: string | null, next: string | null): string {
  if (!next) {
    return rankAfter(prev);
  }

  const p = prev ?? '';
  // Pad prev to next's length with 'a' (lowest visible char).
  const padded = p.length >= next.length ? p : p + 'a'.repeat(next.length - p.length);

  for (let i = next.length - 1; i >= 0; i--) {
    const pChar = i < padded.length ? padded.charCodeAt(i) : 'a'.charCodeAt(0);
    const nChar = next.charCodeAt(i);
    if (nChar - pChar > 1) {
      const mid = Math.floor((pChar + nChar) / 2);
      return padded.slice(0, i) + String.fromCharCode(mid);
    }
  }

  // No gap found at any position — start a new char level after prev.
  return rankAfter(prev);
}
```

- [ ] Run `npm test` from `packages/app`; confirm `ranking.test.ts` **PASSES**.
- [ ] Commit: `test(q2): ranking.ts — client mirror of server lexicographic rank scheme`
  (footer `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`).

---

### Task Q2-3: domain type changes in `types.ts` (project → projectId, changeSeq)

This is a non-TDD type-only edit (the store and mapper tests in later tasks exercise it). The full component sweep of `\.project\b` is Q4; here we change only the type so the store and mappers compile against the new shape.

- [ ] In `packages/app/src/types.ts`, replace the `Task` interface `project` field and add `changeSeq`. Change:

```ts
export interface Task {
  id: string;
  title: string;
  notes: string;
  project: string;
  labels: string[];
```

to:

```ts
export interface Task {
  id: string;
  title: string;
  notes: string;
  projectId: string | null;
  labels: string[];
```

- [ ] In the same `Task` interface, add `changeSeq` next to the sync columns. Change:

```ts
  createdAt: string;
  updatedAt: string;
  completedAt?: string | null;
}
```

to:

```ts
  createdAt: string;
  updatedAt: string;
  changeSeq: number;
  completedAt?: string | null;
}
```

- [ ] Leave `FocusSession.project` (a name string — wire history snapshot) and `ReportData`/`ProjectTime`/`CompletedTaskReportItem` `project` name strings **unchanged** (spec §2/§3: reports + focus-session history stay name-based).
- [ ] Do **not** delete the `ElectronAPI`/`window.api` declaration block yet (the store still imports nothing from it after Q2 refactor, but desktop preload typing is removed in Q5; leaving it now keeps the desktop build green during Q2). No test step — verified transitively when the store + mappers compile in Q2-13.

---

### Task Q2-4: `mappers.ts` — failing tests for enum case-folding + numeric coercion + projectId

- [ ] Create `packages/app/src/data/mappers.test.ts` with this first block (more blocks are appended in Q2-6 and Q2-8; write the whole file now):

```ts
import { describe, it, expect } from 'vitest';
import type { components } from '@tmap/api-client';
import {
  toTask,
  fromTask,
  toServerStatus,
  fromServerStatus,
  toServerFrequency,
  fromServerFrequency,
  toServerEndType,
  fromServerEndType,
  toSubtask,
  fromSubtaskUpdate,
  toProject,
  toNoteGroup,
  toNote,
  toFocusSession,
  toDailyPlan,
  toReportData,
  toRecurrenceRule,
  parseSettings,
  stringifySettings,
} from './mappers';

type TaskResponse = components['schemas']['TaskResponse'];
type SubtaskResponse = components['schemas']['SubtaskResponse'];

function baseTaskResponse(overrides: Partial<TaskResponse> = {}): TaskResponse {
  return {
    id: '00000000-0000-0000-0000-000000000001',
    title: 'T',
    notes: '',
    projectId: null,
    labels: [],
    source: 'manual',
    status: 'Inbox',
    plannedDate: null,
    scheduledStart: null,
    scheduledEnd: null,
    durationMinutes: 30,
    actualTimeMinutes: 0,
    priority: null,
    reminderMinutes: null,
    rank: 'n',
    dueDate: null,
    recurrenceRuleId: null,
    isRecurrenceTemplate: false,
    recurrenceDetached: false,
    recurrenceOriginalDate: null,
    completedAt: null,
    createdAt: '2026-06-08T00:00:00Z',
    updatedAt: '2026-06-08T00:00:00Z',
    changeSeq: 1,
    subtasks: [],
    ...overrides,
  };
}

describe('status enum case-folding', () => {
  it('folds every server PascalCase status to the lowercase union on read', () => {
    expect(fromServerStatus('Inbox')).toBe('inbox');
    expect(fromServerStatus('Backlog')).toBe('backlog');
    expect(fromServerStatus('Planned')).toBe('planned');
    expect(fromServerStatus('Scheduled')).toBe('scheduled');
    expect(fromServerStatus('Done')).toBe('done');
    expect(fromServerStatus('Archived')).toBe('archived');
  });

  it('defends against server status: null by treating it as inbox', () => {
    expect(fromServerStatus(null)).toBe('inbox');
  });

  it('folds the lowercase union back to PascalCase on write', () => {
    expect(toServerStatus('inbox')).toBe('Inbox');
    expect(toServerStatus('scheduled')).toBe('Scheduled');
    expect(toServerStatus('archived')).toBe('Archived');
  });
});

describe('recurrence enum case-folding', () => {
  it('folds frequency both ways', () => {
    expect(fromServerFrequency('Daily')).toBe('daily');
    expect(fromServerFrequency('Weekly')).toBe('weekly');
    expect(toServerFrequency('daily')).toBe('Daily');
    expect(toServerFrequency('weekly')).toBe('Weekly');
  });

  it('folds end type both ways', () => {
    expect(fromServerEndType('Never')).toBe('never');
    expect(fromServerEndType('Count')).toBe('count');
    expect(fromServerEndType('Date')).toBe('date');
    expect(toServerEndType('never')).toBe('Never');
    expect(toServerEndType('count')).toBe('Count');
    expect(toServerEndType('date')).toBe('Date');
  });
});

describe('toTask — numeric coercion + projectId + sync columns', () => {
  it('coerces string numerics to numbers', () => {
    const t = toTask(baseTaskResponse({ durationMinutes: '45', actualTimeMinutes: '12', changeSeq: '7' }));
    expect(t.durationMinutes).toBe(45);
    expect(t.actualTimeMinutes).toBe(12);
    expect(t.changeSeq).toBe(7);
  });

  it('re-narrows priority to 1|2|3|4|null', () => {
    expect(toTask(baseTaskResponse({ priority: '3' })).priority).toBe(3);
    expect(toTask(baseTaskResponse({ priority: null })).priority).toBe(null);
    // out-of-range coerces to null (defensive)
    expect(toTask(baseTaskResponse({ priority: '9' })).priority).toBe(null);
    expect(toTask(baseTaskResponse({ priority: '0' })).priority).toBe(null);
  });

  it('maps projectId through (uuid|null)', () => {
    expect(toTask(baseTaskResponse({ projectId: null })).projectId).toBe(null);
    expect(toTask(baseTaskResponse({ projectId: 'p-1' })).projectId).toBe('p-1');
  });

  it('carries updatedAt and changeSeq onto the domain task', () => {
    const t = toTask(baseTaskResponse({ updatedAt: '2026-06-09T10:00:00Z', changeSeq: 42 }));
    expect(t.updatedAt).toBe('2026-06-09T10:00:00Z');
    expect(t.changeSeq).toBe(42);
  });

  it('does NOT assign order (order is owned by the container rank-sort pass, defaults 0)', () => {
    expect(toTask(baseTaskResponse()).order).toBe(0);
  });

  it('reminderMinutes null stays null; numeric coerces', () => {
    expect(toTask(baseTaskResponse({ reminderMinutes: null })).reminderMinutes).toBe(null);
    expect(toTask(baseTaskResponse({ reminderMinutes: '15' })).reminderMinutes).toBe(15);
  });

  it('maps nested subtasks via sortOrder -> order, sorted ascending', () => {
    const subs: SubtaskResponse[] = [
      { id: 's2', taskId: 't', title: 'b', completed: false, sortOrder: '5', createdAt: 'x', updatedAt: 'x' },
      { id: 's1', taskId: 't', title: 'a', completed: true, sortOrder: '1', createdAt: 'x', updatedAt: 'x' },
    ];
    const t = toTask(baseTaskResponse({ subtasks: subs }));
    expect(t.subtasks.map((s) => s.id)).toEqual(['s1', 's2']);
    expect(t.subtasks.map((s) => s.order)).toEqual([0, 1]);
    expect(t.subtasks[0].completed).toBe(true);
  });
});

describe('fromTask — domain -> CreateTaskRequest / UpdateTaskRequest', () => {
  it('omits undefined fields and only sends provided keys (PATCH semantics)', () => {
    const body = fromTask({ title: 'New title' });
    expect(body).toEqual({ title: 'New title' });
  });

  it('maps projectId and status (lower->Pascal) and never sends order', () => {
    const body = fromTask({ projectId: 'p-9', status: 'scheduled', order: 4 });
    expect(body.projectId).toBe('p-9');
    expect(body.status).toBe('Scheduled');
    expect('order' in body).toBe(false);
  });

  it('sends projectId: null explicitly when caller clears the project', () => {
    const body = fromTask({ projectId: null });
    expect(body).toEqual({ projectId: null });
  });

  it('passes through a client-provided id for idempotent create retries', () => {
    const body = fromTask({ id: 'client-uuid', title: 'x' });
    expect(body.id).toBe('client-uuid');
  });
});
```

- [ ] Run `npm test`; confirm **FAIL** (`./mappers` not found).

### Task Q2-5: implement `mappers.ts` (enums + toTask/fromTask + sync columns) — partial pass

- [ ] Create `packages/app/src/data/mappers.ts`. Write the **whole** file now (it also contains the project/note/recurrence/settings mappers exercised by Q2-6 and Q2-8 so we implement it once):

```ts
import type { components } from '@tmap/api-client';
import type {
  Task,
  Subtask,
  Project,
  NoteGroup,
  Note,
  FocusSession,
  DailyPlan,
  ReportData,
  RecurrenceRule,
  RecurrenceFrequency,
  RecurrenceEndType,
  TaskStatus,
} from '../types';

type C = components['schemas'];
type TaskResponse = C['TaskResponse'];
type SubtaskResponse = C['SubtaskResponse'];
type ProjectResponse = C['ProjectResponse'];
type NoteGroupResponse = C['NoteGroupResponse'];
type NoteResponse = C['NoteResponse'];
type FocusSessionResponse = C['FocusSessionResponse'];
type DailyPlanResponse = C['DailyPlanResponse'];
type ReportDataResponse = C['ReportDataResponse'];
type RecurrenceRuleResponse = C['RecurrenceRuleResponse'];
type ServerTaskStatus = C['TaskStatus'];
type ServerFrequency = C['RecurrenceFrequency'];
type ServerEndType = C['RecurrenceEndType'];

// ── numeric coercion ─────────────────────────────────────────
/** Every DTO numeric arrives as number|string; normalize to number. */
function num(v: number | string | null | undefined, fallback = 0): number {
  if (v === null || v === undefined) return fallback;
  const n = Number(v);
  return Number.isFinite(n) ? n : fallback;
}

/** Numeric or null (e.g. reminderMinutes). */
function numOrNull(v: number | string | null | undefined): number | null {
  if (v === null || v === undefined) return null;
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
}

/** Re-narrow priority to the 1|2|3|4|null domain union. */
function priorityOf(v: number | string | null | undefined): 1 | 2 | 3 | 4 | null {
  const n = numOrNull(v);
  return n === 1 || n === 2 || n === 3 || n === 4 ? n : null;
}

// ── enum case-folding ────────────────────────────────────────
export function fromServerStatus(s: ServerTaskStatus | null | undefined): TaskStatus {
  switch (s) {
    case 'Inbox':
      return 'inbox';
    case 'Backlog':
      return 'backlog';
    case 'Planned':
      return 'planned';
    case 'Scheduled':
      return 'scheduled';
    case 'Done':
      return 'done';
    case 'Archived':
      return 'archived';
    default:
      return 'inbox'; // defend against status: null
  }
}

export function toServerStatus(s: TaskStatus): Exclude<ServerTaskStatus, null> {
  return ((s.charAt(0).toUpperCase() + s.slice(1)) as Exclude<ServerTaskStatus, null>);
}

export function fromServerFrequency(f: ServerFrequency): RecurrenceFrequency {
  return f === 'Weekly' ? 'weekly' : 'daily';
}

export function toServerFrequency(f: RecurrenceFrequency): ServerFrequency {
  return f === 'weekly' ? 'Weekly' : 'Daily';
}

export function fromServerEndType(e: ServerEndType): RecurrenceEndType {
  switch (e) {
    case 'Count':
      return 'count';
    case 'Date':
      return 'date';
    default:
      return 'never';
  }
}

export function toServerEndType(e: RecurrenceEndType): ServerEndType {
  switch (e) {
    case 'count':
      return 'Count';
    case 'date':
      return 'Date';
    default:
      return 'Never';
  }
}

// ── subtasks ─────────────────────────────────────────────────
/** Map one SubtaskResponse; `order` is assigned by the container pass in toTask. */
function toSubtaskRaw(s: SubtaskResponse): Subtask & { _rank: number } {
  return {
    id: s.id,
    taskId: s.taskId,
    title: s.title,
    completed: s.completed,
    order: 0,
    createdAt: s.createdAt,
    _rank: num(s.sortOrder),
  };
}

/** Public single-subtask mapper (sortOrder -> order pass-through, no resort). */
export function toSubtask(s: SubtaskResponse): Subtask {
  return {
    id: s.id,
    taskId: s.taskId,
    title: s.title,
    completed: s.completed,
    order: num(s.sortOrder),
    createdAt: s.createdAt,
  };
}

/** Domain subtask update -> UpdateSubtaskRequest (order -> sortOrder). */
export function fromSubtaskUpdate(u: {
  title?: string;
  completed?: boolean;
  order?: number;
}): C['UpdateSubtaskRequest'] {
  const body: C['UpdateSubtaskRequest'] = { title: null, completed: null, sortOrder: null };
  if (u.title !== undefined) body.title = u.title;
  if (u.completed !== undefined) body.completed = u.completed;
  if (u.order !== undefined) body.sortOrder = u.order;
  return body;
}

// ── tasks ────────────────────────────────────────────────────
export function toTask(r: TaskResponse): Task {
  const subtasks = [...r.subtasks]
    .map(toSubtaskRaw)
    .sort((a, b) => a._rank - b._rank)
    .map((s, i): Subtask => ({
      id: s.id,
      taskId: s.taskId,
      title: s.title,
      completed: s.completed,
      order: i,
      createdAt: s.createdAt,
    }));

  return {
    id: r.id,
    title: r.title,
    notes: r.notes,
    projectId: r.projectId ?? null,
    labels: r.labels ?? [],
    source: r.source,
    status: fromServerStatus(r.status),
    plannedDate: r.plannedDate ?? null,
    scheduledStart: r.scheduledStart ?? null,
    scheduledEnd: r.scheduledEnd ?? null,
    durationMinutes: num(r.durationMinutes),
    actualTimeMinutes: num(r.actualTimeMinutes),
    priority: priorityOf(r.priority),
    reminderMinutes: numOrNull(r.reminderMinutes),
    subtasks,
    order: 0, // assigned by the container rank-sort pass in HttpDataClient
    recurrenceRuleId: r.recurrenceRuleId ?? null,
    isRecurrenceTemplate: r.isRecurrenceTemplate,
    recurrenceDetached: r.recurrenceDetached,
    recurrenceOriginalDate: r.recurrenceOriginalDate ?? null,
    createdAt: r.createdAt,
    updatedAt: r.updatedAt,
    changeSeq: num(r.changeSeq),
    completedAt: r.completedAt ?? null,
  };
}

/**
 * Domain Task patch -> CreateTaskRequest/UpdateTaskRequest shape. Only includes
 * keys present in `u` (PATCH semantics). `order`/`subtasks`/`changeSeq` are never
 * sent; ordering is sent via reorder (rank), subtasks via their own endpoints.
 * A client-provided `id` is forwarded so a retried create stays idempotent.
 */
export function fromTask(u: Partial<Task>): Record<string, unknown> {
  const body: Record<string, unknown> = {};
  if (u.id !== undefined) body.id = u.id;
  if (u.title !== undefined) body.title = u.title;
  if (u.notes !== undefined) body.notes = u.notes;
  if (u.projectId !== undefined) body.projectId = u.projectId;
  if (u.labels !== undefined) body.labels = u.labels;
  if (u.source !== undefined) body.source = u.source;
  if (u.status !== undefined) body.status = toServerStatus(u.status);
  if (u.plannedDate !== undefined) body.plannedDate = u.plannedDate;
  if (u.scheduledStart !== undefined) body.scheduledStart = u.scheduledStart;
  if (u.scheduledEnd !== undefined) body.scheduledEnd = u.scheduledEnd;
  if (u.durationMinutes !== undefined) body.durationMinutes = u.durationMinutes;
  if (u.actualTimeMinutes !== undefined) body.actualTimeMinutes = u.actualTimeMinutes;
  if (u.priority !== undefined) body.priority = u.priority;
  if (u.reminderMinutes !== undefined) body.reminderMinutes = u.reminderMinutes;
  if (u.completedAt !== undefined) body.completedAt = u.completedAt;
  return body;
}

// ── projects ─────────────────────────────────────────────────
export function toProject(r: ProjectResponse): Project & { _rank: string } {
  return {
    id: r.id,
    name: r.name,
    color: r.color,
    emoji: r.emoji,
    order: 0,
    actualTimeMinutes: num(r.actualTimeMinutes),
    createdAt: r.createdAt,
    updatedAt: r.updatedAt,
    _rank: r.rank,
  };
}

// ── note groups ──────────────────────────────────────────────
export function toNoteGroup(r: NoteGroupResponse): NoteGroup & { _rank: string } {
  return {
    id: r.id,
    name: r.name,
    emoji: r.emoji,
    projectId: r.projectId ?? null,
    order: 0,
    createdAt: r.createdAt,
    updatedAt: r.updatedAt,
    _rank: r.rank,
  };
}

// ── notes ────────────────────────────────────────────────────
export function toNote(r: NoteResponse): Note & { _rank: string } {
  return {
    id: r.id,
    groupId: r.groupId ?? null,
    projectId: r.projectId ?? null,
    title: r.title,
    content: r.content,
    order: 0,
    createdAt: r.createdAt,
    updatedAt: r.updatedAt,
    _rank: r.rank,
  };
}

// ── focus sessions ───────────────────────────────────────────
export function toFocusSession(r: FocusSessionResponse): FocusSession {
  return {
    id: r.id,
    taskId: r.taskId ?? null,
    project: r.project,
    startedAt: r.startedAt,
    endedAt: r.endedAt,
    minutes: num(r.minutes),
    date: r.date,
    createdAt: r.createdAt,
  };
}

// ── daily plans ──────────────────────────────────────────────
export function toDailyPlan(r: DailyPlanResponse): DailyPlan {
  return {
    date: r.date,
    committedAt: r.committedAt,
    plannedTaskIds: r.plannedTaskIds,
    plannedMinutes: num(r.plannedMinutes),
  };
}

// ── reports (pass-through, numeric coercion only) ────────────
export function toReportData(r: ReportDataResponse): ReportData {
  return {
    completedTasks: r.completedTasks.map((c) => ({ id: c.id, project: c.project, date: c.date })),
    sessions: r.sessions.map((s) => ({ project: s.project, minutes: num(s.minutes), date: s.date })),
    dailyPlans: r.dailyPlans.map((d) => ({
      date: d.date,
      committedAt: d.committedAt,
      plannedTaskIds: d.plannedTaskIds,
      plannedMinutes: num(d.plannedMinutes),
    })),
  };
}

// ── recurrence rule ──────────────────────────────────────────
export function toRecurrenceRule(r: RecurrenceRuleResponse): RecurrenceRule {
  return {
    id: r.id,
    frequency: fromServerFrequency(r.frequency),
    interval: num(r.interval),
    daysOfWeek: r.daysOfWeek.map((d) => num(d)),
    endType: fromServerEndType(r.endType),
    endCount: numOrNull(r.endCount),
    endDate: r.endDate ?? null,
    generatedUntil: r.generatedUntil ?? null,
    createdAt: r.createdAt,
    updatedAt: r.updatedAt,
  };
}

// ── settings (string<->number on the three synced values) ────
const SYNCED_NUMERIC_KEYS = ['workStartHour', 'workEndHour', 'timeIncrement'] as const;

/**
 * Parse a SettingsResponse.settings map (Record<string,string>) into the
 * store-friendly object: the three synced values become numbers; any other key
 * is left as its string value. `timeZoneId` is handled separately (top-level).
 */
export function parseSettings(map: Record<string, string>): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(map)) {
    if ((SYNCED_NUMERIC_KEYS as readonly string[]).includes(k)) {
      const n = Number(v);
      out[k] = Number.isFinite(n) ? n : v;
    } else {
      out[k] = v;
    }
  }
  return out;
}

/**
 * Stringify a store settings object into SaveSettingsRequest.settings
 * (Record<string,string>). Only the three synced numeric keys are persisted to
 * the server in SP2 (local-only keys live in localStorage, handled by the store).
 */
export function stringifySettings(s: Record<string, unknown>): Record<string, string> {
  const out: Record<string, string> = {};
  for (const k of SYNCED_NUMERIC_KEYS) {
    if (s[k] !== undefined && s[k] !== null) {
      out[k] = String(s[k]);
    }
  }
  return out;
}
```

- [ ] Run `npm test`; confirm the Q2-4 blocks **PASS** (other blocks added in Q2-6/Q2-8 will fail until those tasks; that is expected and the run is allowed to show those pending failures — proceed). If your harness requires green between tasks, append the Q2-6 + Q2-8 test blocks now and skip ahead to running after Q2-8.

### Task Q2-6: append project/notegroup/note/focus/dailyplan/report/recurrence mapper tests

- [ ] Append to `packages/app/src/data/mappers.test.ts`:

```ts
describe('toProject / toNoteGroup / toNote — carry _rank, default order 0', () => {
  it('toProject coerces actualTimeMinutes and exposes _rank', () => {
    const p = toProject({
      id: 'p1', name: 'P', color: '#fff', emoji: '🚀',
      rank: 'm', actualTimeMinutes: '90',
      createdAt: 'a', updatedAt: 'b',
    });
    expect(p.actualTimeMinutes).toBe(90);
    expect(p.order).toBe(0);
    expect((p as any)._rank).toBe('m');
  });

  it('toNoteGroup maps projectId null and _rank', () => {
    const g = toNoteGroup({
      id: 'g1', name: 'G', emoji: '📁', projectId: null,
      rank: 'k', createdAt: 'a', updatedAt: 'b',
    });
    expect(g.projectId).toBe(null);
    expect((g as any)._rank).toBe('k');
  });

  it('toNote maps groupId/projectId and _rank', () => {
    const n = toNote({
      id: 'n1', groupId: 'g1', projectId: null, title: 'T', content: '<p/>',
      rank: 'h', createdAt: 'a', updatedAt: 'b',
    });
    expect(n.groupId).toBe('g1');
    expect(n.projectId).toBe(null);
    expect((n as any)._rank).toBe('h');
  });
});

describe('toFocusSession / toDailyPlan / toReportData', () => {
  it('toFocusSession coerces minutes and preserves project name string', () => {
    const fs = toFocusSession({
      id: 'f1', taskId: null, project: 'Marketing',
      startedAt: 's', endedAt: 'e', minutes: '25', date: '2026-06-08',
      createdAt: 'c', updatedAt: 'u',
    });
    expect(fs.minutes).toBe(25);
    expect(fs.project).toBe('Marketing');
    expect(fs.taskId).toBe(null);
  });

  it('toDailyPlan coerces plannedMinutes', () => {
    const dp = toDailyPlan({
      date: '2026-06-08', committedAt: 'c',
      plannedTaskIds: ['t1', 't2'], plannedMinutes: '120',
      createdAt: 'a', updatedAt: 'b',
    });
    expect(dp.plannedMinutes).toBe(120);
    expect(dp.plannedTaskIds).toEqual(['t1', 't2']);
  });

  it('toReportData coerces session/plan minutes and keeps project names', () => {
    const rd = toReportData({
      completedTasks: [{ id: 't1', project: 'Ops', date: '2026-06-08' }],
      sessions: [{ project: 'Ops', minutes: '30', date: '2026-06-08' }],
      dailyPlans: [{ date: '2026-06-08', committedAt: 'c', plannedTaskIds: ['t1'], plannedMinutes: '60' }],
    });
    expect(rd.sessions[0].minutes).toBe(30);
    expect(rd.completedTasks[0].project).toBe('Ops');
    expect(rd.dailyPlans[0].plannedMinutes).toBe(60);
  });
});

describe('toRecurrenceRule', () => {
  it('folds enums and coerces numerics', () => {
    const rr = toRecurrenceRule({
      id: 'r1', frequency: 'Weekly', interval: '2',
      daysOfWeek: ['1', '3', '5'], endType: 'Count',
      endCount: '10', endDate: null, generatedUntil: '2026-07-01',
      createdAt: 'a', updatedAt: 'b',
    });
    expect(rr.frequency).toBe('weekly');
    expect(rr.interval).toBe(2);
    expect(rr.daysOfWeek).toEqual([1, 3, 5]);
    expect(rr.endType).toBe('count');
    expect(rr.endCount).toBe(10);
  });
});

describe('fromSubtaskUpdate (order -> sortOrder, omit unset)', () => {
  it('maps order to sortOrder and leaves unset fields null', () => {
    expect(fromSubtaskUpdate({ order: 3 })).toEqual({ title: null, completed: null, sortOrder: 3 });
    expect(fromSubtaskUpdate({ completed: true })).toEqual({ title: null, completed: true, sortOrder: null });
  });
});
```

### Task Q2-7: settings parse/stringify tests + timeZoneId top-level expectation

- [ ] Append to `packages/app/src/data/mappers.test.ts`:

```ts
describe('parseSettings / stringifySettings', () => {
  it('parses the three synced numeric values string -> number', () => {
    const parsed = parseSettings({ workStartHour: '8', workEndHour: '20', timeIncrement: '15' });
    expect(parsed).toEqual({ workStartHour: 8, workEndHour: 20, timeIncrement: 15 });
  });

  it('leaves unknown keys as strings (timeZoneId is NOT inside the map)', () => {
    const parsed = parseSettings({ workStartHour: '9', someFutureKey: 'hello' });
    expect(parsed.workStartHour).toBe(9);
    expect(parsed.someFutureKey).toBe('hello');
  });

  it('stringifies only the three synced numeric keys', () => {
    const body = stringifySettings({
      workStartHour: 8, workEndHour: 20, timeIncrement: 10,
      sidebarCollapsed: true, // local-only -> not persisted to server
    });
    expect(body).toEqual({ workStartHour: '8', workEndHour: '20', timeIncrement: '10' });
    expect('sidebarCollapsed' in body).toBe(false);
  });

  it('omits undefined synced keys rather than writing "undefined"', () => {
    const body = stringifySettings({ workStartHour: 8 });
    expect(body).toEqual({ workStartHour: '8' });
  });
});
```

### Task Q2-8: run mappers tests green

- [ ] Run `npm test` from `packages/app`; confirm `mappers.test.ts` (all blocks Q2-4/6/7) **PASSES** and `ranking.test.ts` still passes.
- [ ] Commit: `test(q2): mappers.ts — DTO<->domain (enums, numeric coercion, projectId, settings)`.

---

### Task Q2-9: `DataClient.ts` interface (the seam the store calls)

Interface-only file; verified transitively when `HttpDataClient` implements it (Q2-11) and the store consumes it (Q2-13). No standalone test.

- [ ] Create `packages/app/src/data/DataClient.ts`:

```ts
import type {
  Task,
  Subtask,
  Project,
  NoteGroup,
  Note,
  FocusSession,
  DailyPlan,
  ReportData,
  RecurrenceRule,
} from '../types';

/** Reorder payload as the store produces it (desired sequential order). */
export interface ReorderInput {
  id: string;
  order: number;
}

/** Recurrence rule input the store passes through createRecurringTask/updateRule. */
export interface RecurrenceRuleInput {
  frequency: 'daily' | 'weekly';
  interval: number;
  daysOfWeek: number[];
  endType: 'never' | 'count' | 'date';
  endCount?: number;
  endDate?: string;
}

/**
 * The data seam the Zustand store talks to. SP2 has exactly one implementation
 * (HttpDataClient over @tmap/api-client). SP3 will wrap it in a CachingDataClient
 * without changing the store. Only methods the store actually calls are present.
 */
export interface DataClient {
  tasks: {
    getAll(): Promise<Task[]>;
    getByDate(date: string): Promise<Task[]>;
    create(t: Partial<Task>): Promise<Task>;
    update(id: string, u: Partial<Task>): Promise<Task>;
    delete(id: string): Promise<void>;
    reorder(items: ReorderInput[]): Promise<void>;
  };
  subtasks: {
    create(taskId: string, title: string): Promise<Subtask>;
    update(
      id: string,
      u: { title?: string; completed?: boolean; order?: number },
    ): Promise<void>;
    delete(id: string): Promise<void>;
  };
  projects: {
    getAll(): Promise<Project[]>;
    create(i: { name: string; color?: string; emoji?: string }): Promise<Project>;
    update(id: string, u: Partial<Project>): Promise<Project>;
    delete(id: string): Promise<void>;
    reorder(items: ReorderInput[]): Promise<void>;
  };
  noteGroups: {
    getAll(): Promise<NoteGroup[]>;
    getByProject(projectId: string): Promise<NoteGroup[]>;
    create(i: { name: string; emoji?: string; projectId?: string }): Promise<NoteGroup>;
    update(id: string, u: Partial<NoteGroup>): Promise<NoteGroup>;
    delete(id: string): Promise<void>;
    reorder(items: ReorderInput[]): Promise<void>;
  };
  notes: {
    getAll(): Promise<Note[]>;
    getByGroup(groupId: string): Promise<Note[]>;
    getByProject(projectId: string): Promise<Note[]>;
    getById(id: string): Promise<Note | null>;
    create(i: {
      groupId?: string;
      projectId?: string;
      title?: string;
      content?: string;
    }): Promise<Note>;
    update(
      id: string,
      u: Partial<{
        title: string;
        content: string;
        groupId: string;
        projectId: string;
        order: number;
      }>,
    ): Promise<Note>;
    delete(id: string): Promise<void>;
    reorder(items: ReorderInput[]): Promise<void>;
  };
  recurrence: {
    create(task: Partial<Task>, rule: RecurrenceRuleInput): Promise<Task[]>;
    updateSeries(ruleId: string, u: Partial<Task>): Promise<void>;
    deleteSeries(ruleId: string): Promise<void>;
    deleteSeriesFuture(ruleId: string, from: string): Promise<void>;
    detachInstance(taskId: string): Promise<void>;
    ensureInstances(start: string, end: string): Promise<Task[]>;
    updateRule(ruleId: string, u: RecurrenceRuleInput): Promise<void>;
    getRule(ruleId: string): Promise<RecurrenceRule | null>;
  };
  focusSessions: {
    add(s: {
      taskId: string | null;
      project: string;
      startedAt: string;
      endedAt: string;
      minutes: number;
      date: string;
    }): Promise<FocusSession>;
  };
  dailyPlans: {
    upsert(p: {
      date: string;
      plannedTaskIds: string[];
      plannedMinutes: number;
    }): Promise<DailyPlan>;
    get(date: string): Promise<DailyPlan | null>;
  };
  reports: {
    getData(start: string, end: string): Promise<ReportData>;
  };
  settings: {
    get(): Promise<{ settings: Record<string, unknown>; timeZoneId: string }>;
    save(s: Record<string, unknown>, timeZoneId?: string): Promise<void>;
  };
}
```

---

### Task Q2-10: `HttpDataClient` reorder-rank computation — failing test

This is the subtlest piece, so it gets a dedicated unit test using a fake `TmapClient`. The fake records the `[{id,rank}]` body sent to each reorder endpoint and serves a fixed task list (with known ranks) for `getAll`, so we can assert the cache + `rankBetween` math.

- [ ] Create `packages/app/src/data/HttpDataClient.test.ts`:

```ts
import { describe, it, expect, beforeEach } from 'vitest';
import { HttpDataClient } from './HttpDataClient';
import type { TmapClient } from '@tmap/api-client';

// ── minimal fake TmapClient ──────────────────────────────────
interface Recorded {
  method: 'GET' | 'POST' | 'PATCH' | 'PUT' | 'DELETE';
  path: string;
  body?: unknown;
}

function fakeClient(handlers: Record<string, (body: unknown) => unknown>) {
  const calls: Recorded[] = [];
  const make =
    (method: Recorded['method']) =>
    (path: string, opts?: { body?: unknown; params?: unknown }) => {
      calls.push({ method, path, body: opts?.body });
      const key = `${method} ${path}`;
      const handler = handlers[key];
      const data = handler ? handler(opts?.body) : undefined;
      return Promise.resolve({ data, error: undefined });
    };
  const client = {
    GET: make('GET'),
    POST: make('POST'),
    PATCH: make('PATCH'),
    PUT: make('PUT'),
    DELETE: make('DELETE'),
  } as unknown as TmapClient;
  return { client, calls };
}

function taskRow(id: string, rank: string) {
  return {
    id, title: id, notes: '', projectId: null, labels: [], source: 'manual',
    status: 'Inbox', plannedDate: null, scheduledStart: null, scheduledEnd: null,
    durationMinutes: 0, actualTimeMinutes: 0, priority: null, reminderMinutes: null,
    rank, dueDate: null, recurrenceRuleId: null, isRecurrenceTemplate: false,
    recurrenceDetached: false, recurrenceOriginalDate: null, completedAt: null,
    createdAt: 'a', updatedAt: 'b', changeSeq: 1, subtasks: [],
  };
}

describe('HttpDataClient.tasks.getAll — rank -> sequential order + cache', () => {
  it('sorts by rank and assigns sequential integer order', async () => {
    const { client } = fakeClient({
      'GET /api/v1/tasks': () => [taskRow('c', 'p'), taskRow('a', 'd'), taskRow('b', 'k')],
    });
    const dc = new HttpDataClient(client);
    const tasks = await dc.tasks.getAll();
    expect(tasks.map((t) => t.id)).toEqual(['a', 'b', 'c']); // d < k < p
    expect(tasks.map((t) => t.order)).toEqual([0, 1, 2]);
  });
});

describe('HttpDataClient.tasks.reorder — computes one rank per moved id', () => {
  beforeEach(() => {});

  it('moves a single item between two neighbors using cached ranks', async () => {
    const sent: Array<{ id: string; rank: string }[]> = [];
    const { client } = fakeClient({
      'GET /api/v1/tasks': () => [taskRow('a', 'd'), taskRow('b', 'k'), taskRow('c', 'p')],
      'PATCH /api/v1/tasks/reorder': (body) => {
        sent.push(body as { id: string; rank: string }[]);
        return undefined;
      },
    });
    const dc = new HttpDataClient(client);
    // prime the cache (getAll caches a..d, b..k, c..p)
    await dc.tasks.getAll();
    // desired new order: a(0), c(1), b(2)  -> only c moved (between a='d' and b='k')
    await dc.tasks.reorder([
      { id: 'a', order: 0 },
      { id: 'c', order: 1 },
      { id: 'b', order: 2 },
    ]);
    expect(sent).toHaveLength(1);
    const payload = sent[0];
    // exactly the moved row(s) are sent — c is the only id whose neighbors changed
    const cItem = payload.find((p) => p.id === 'c');
    expect(cItem).toBeDefined();
    // c's new rank must sort strictly between a's 'd' and b's 'k'
    expect('d' < cItem!.rank && cItem!.rank < 'k').toBe(true);
  });

  it('appends a moved item to the end with a rank after the last neighbor', async () => {
    const sent: Array<{ id: string; rank: string }[]> = [];
    const { client } = fakeClient({
      'GET /api/v1/tasks': () => [taskRow('a', 'd'), taskRow('b', 'k'), taskRow('c', 'p')],
      'PATCH /api/v1/tasks/reorder': (body) => {
        sent.push(body as { id: string; rank: string }[]);
        return undefined;
      },
    });
    const dc = new HttpDataClient(client);
    await dc.tasks.getAll();
    // move 'a' to the end: b(0), c(1), a(2) -> a after 'p'
    await dc.tasks.reorder([
      { id: 'b', order: 0 },
      { id: 'c', order: 1 },
      { id: 'a', order: 2 },
    ]);
    const aItem = sent[0].find((p) => p.id === 'a');
    expect(aItem).toBeDefined();
    expect(aItem!.rank > 'p').toBe(true);
  });

  it('updates the cache so a second reorder uses the new rank', async () => {
    const sent: Array<{ id: string; rank: string }[]> = [];
    const { client } = fakeClient({
      'GET /api/v1/tasks': () => [taskRow('a', 'd'), taskRow('b', 'k')],
      'PATCH /api/v1/tasks/reorder': (body) => {
        sent.push(body as { id: string; rank: string }[]);
        return undefined;
      },
    });
    const dc = new HttpDataClient(client);
    await dc.tasks.getAll();
    await dc.tasks.reorder([{ id: 'b', order: 0 }, { id: 'a', order: 1 }]); // b before a
    const bRank1 = sent[0].find((p) => p.id === 'b')!.rank;
    await dc.tasks.reorder([{ id: 'a', order: 0 }, { id: 'b', order: 1 }]); // swap back
    const bRank2 = sent[1].find((p) => p.id === 'b')!.rank;
    // second computation must use the cache updated after the first reorder
    expect(bRank2 > bRank1).toBe(true);
  });
});

describe('HttpDataClient.projects.create — passes client rank seed', () => {
  it('seeds rank after the current last project', async () => {
    let createdBody: any;
    const { client } = fakeClient({
      'GET /api/v1/projects': () => [
        { id: 'p1', name: 'A', color: '#1', emoji: '', rank: 'd', actualTimeMinutes: 0, createdAt: 'a', updatedAt: 'b' },
      ],
      'POST /api/v1/projects': (body) => {
        createdBody = body;
        return { id: 'p2', name: 'B', color: '#fff', emoji: '🆕', rank: (body as any).rank ?? 'e', actualTimeMinutes: 0, createdAt: 'a', updatedAt: 'b' };
      },
    });
    const dc = new HttpDataClient(client);
    await dc.projects.getAll(); // primes cache: last rank 'd'
    const created = await dc.projects.create({ name: 'B' });
    expect(createdBody.rank).toBeDefined();
    expect(createdBody.rank > 'd').toBe(true);
    expect(created.name).toBe('B');
  });
});
```

- [ ] Run `npm test`; confirm **FAIL** (`./HttpDataClient` not found).

### Task Q2-11: implement `HttpDataClient.ts` — make Q2-10 pass

- [ ] Create `packages/app/src/data/HttpDataClient.ts`. Write the complete class:

```ts
import type { TmapClient } from '@tmap/api-client';
import type {
  Task,
  Subtask,
  Project,
  NoteGroup,
  Note,
  FocusSession,
  DailyPlan,
  ReportData,
  RecurrenceRule,
} from '../types';
import type { DataClient, ReorderInput, RecurrenceRuleInput } from './DataClient';
import { rankBetween } from './ranking';
import {
  toTask,
  fromTask,
  toSubtask,
  fromSubtaskUpdate,
  toProject,
  toNoteGroup,
  toNote,
  toFocusSession,
  toDailyPlan,
  toReportData,
  toRecurrenceRule,
  toServerFrequency,
  toServerEndType,
  parseSettings,
  stringifySettings,
} from './mappers';

/** crypto.randomUUID with a safe fallback (used for client-provided ids). */
function newId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

/** Unwrap an openapi-fetch result; throw on transport/HTTP error. */
function unwrap<T>(res: { data?: T; error?: unknown }): T {
  if (res.error) {
    throw res.error;
  }
  return res.data as T;
}

/**
 * The only SP2 DataClient implementation. Talks to the API via @tmap/api-client
 * and converts every DTO<->domain shape through mappers.ts. Keeps a per-container
 * id->rank cache so reorder can compute a single fractional rank per moved row
 * (via ranking.rankBetween) instead of renumbering the whole list.
 */
export class HttpDataClient implements DataClient {
  private readonly api: TmapClient;

  // Per-container rank caches. Keyed by entity id.
  private taskRanks = new Map<string, string>();
  private projectRanks = new Map<string, string>();
  private noteGroupRanks = new Map<string, string>();
  private noteRanks = new Map<string, string>();

  constructor(client: TmapClient) {
    this.api = client;
  }

  // ── rank helpers ───────────────────────────────────────────
  /** Rebuild a cache from freshly-read rows (each row carries _rank). */
  private cacheRanks<T extends { id: string; _rank: string }>(
    cache: Map<string, string>,
    rows: T[],
  ): void {
    cache.clear();
    for (const r of rows) cache.set(r.id, r._rank);
  }

  /** Sort rows by their cached rank and assign sequential integer order. */
  private withOrder<T extends { id: string; _rank: string }>(rows: T[]): T[] {
    const sorted = [...rows].sort((a, b) => (a._rank < b._rank ? -1 : a._rank > b._rank ? 1 : 0));
    return sorted.map((r, i) => ({ ...r, order: i }));
  }

  /** Rank that sorts after the current max in a cache (for create/append). */
  private rankForAppend(cache: Map<string, string>): string {
    let max: string | null = null;
    for (const r of cache.values()) {
      if (max === null || r > max) max = r;
    }
    return rankBetween(max, null);
  }

  /**
   * Given the store's desired [{id, order}] and the current rank cache, compute
   * the minimal set of [{id, rank}] to send: for each item whose new neighbors
   * differ from a position consistent with its current rank, assign a fresh rank
   * strictly between the (already-finalized) previous item and the next item's
   * current rank. We process in target order and update the cache as we go.
   */
  private computeReorder(cache: Map<string, string>, items: ReorderInput[]): { id: string; rank: string }[] {
    const ordered = [...items].sort((a, b) => a.order - b.order);
    const result: { id: string; rank: string }[] = [];
    // Working ranks: start from current cache; finalized items overwrite theirs.
    const working = new Map(cache);
    let prevRank: string | null = null;

    for (let i = 0; i < ordered.length; i++) {
      const cur = ordered[i];
      const curRank = working.get(cur.id) ?? null;
      // The next item's rank as it stands right now (not yet re-ranked).
      const next = ordered[i + 1];
      const nextRank = next ? working.get(next.id) ?? null : null;

      const inOrder =
        curRank !== null &&
        (prevRank === null || prevRank < curRank) &&
        (nextRank === null || curRank < nextRank);

      if (inOrder) {
        prevRank = curRank;
        continue; // unchanged — no payload entry
      }

      const newRank = rankBetween(prevRank, nextRank);
      working.set(cur.id, newRank);
      cache.set(cur.id, newRank);
      result.push({ id: cur.id, rank: newRank });
      prevRank = newRank;
    }
    return result;
  }

  // ── tasks ──────────────────────────────────────────────────
  tasks = {
    getAll: async (): Promise<Task[]> => {
      const rows = unwrap(await this.api.GET('/api/v1/tasks', {}));
      const mapped = rows.map((r) => ({ ...toTask(r), _rank: r.rank }));
      this.cacheRanks(this.taskRanks, mapped);
      return this.withOrder(mapped).map(({ _rank, ...t }) => t as Task);
    },
    getByDate: async (date: string): Promise<Task[]> => {
      const rows = unwrap(await this.api.GET('/api/v1/tasks', { params: { query: { date } } }));
      const mapped = rows.map((r) => ({ ...toTask(r), _rank: r.rank }));
      // date-filtered reads do not own the global cache; merge ranks for these ids.
      for (const r of mapped) this.taskRanks.set(r.id, r._rank);
      return this.withOrder(mapped).map(({ _rank, ...t }) => t as Task);
    },
    create: async (t: Partial<Task>): Promise<Task> => {
      const body = fromTask(t) as Record<string, unknown>;
      if (body.id === undefined) body.id = newId();
      if (body.rank === undefined) body.rank = this.rankForAppend(this.taskRanks);
      const row = unwrap(
        await this.api.POST('/api/v1/tasks', { body: body as never }),
      );
      this.taskRanks.set(row.id, row.rank);
      return toTask(row);
    },
    update: async (id: string, u: Partial<Task>): Promise<Task> => {
      const row = unwrap(
        await this.api.PATCH('/api/v1/tasks/{id}', {
          params: { path: { id } },
          body: fromTask(u) as never,
        }),
      );
      this.taskRanks.set(row.id, row.rank);
      return toTask(row);
    },
    delete: async (id: string): Promise<void> => {
      unwrap(await this.api.DELETE('/api/v1/tasks/{id}', { params: { path: { id } } }));
      this.taskRanks.delete(id);
    },
    reorder: async (items: ReorderInput[]): Promise<void> => {
      const payload = this.computeReorder(this.taskRanks, items);
      if (payload.length === 0) return;
      unwrap(await this.api.PATCH('/api/v1/tasks/reorder', { body: payload as never }));
    },
  };

  // ── subtasks ───────────────────────────────────────────────
  subtasks = {
    create: async (taskId: string, title: string): Promise<Subtask> => {
      const row = unwrap(
        await this.api.POST('/api/v1/tasks/{taskId}/subtasks', {
          params: { path: { taskId } },
          body: { title } as never,
        }),
      );
      return toSubtask(row);
    },
    update: async (
      id: string,
      u: { title?: string; completed?: boolean; order?: number },
    ): Promise<void> => {
      unwrap(
        await this.api.PATCH('/api/v1/subtasks/{id}', {
          params: { path: { id } },
          body: fromSubtaskUpdate(u) as never,
        }),
      );
    },
    delete: async (id: string): Promise<void> => {
      unwrap(await this.api.DELETE('/api/v1/subtasks/{id}', { params: { path: { id } } }));
    },
  };

  // ── projects ───────────────────────────────────────────────
  projects = {
    getAll: async (): Promise<Project[]> => {
      const rows = unwrap(await this.api.GET('/api/v1/projects', {}));
      const mapped = rows.map(toProject);
      this.cacheRanks(this.projectRanks, mapped);
      return this.withOrder(mapped).map(({ _rank, ...p }) => p as Project);
    },
    create: async (i: { name: string; color?: string; emoji?: string }): Promise<Project> => {
      const row = unwrap(
        await this.api.POST('/api/v1/projects', {
          body: {
            name: i.name,
            color: i.color ?? '#6366f1',
            emoji: i.emoji ?? '📁',
            rank: this.rankForAppend(this.projectRanks),
            id: newId(),
          } as never,
        }),
      );
      this.projectRanks.set(row.id, row.rank);
      const { _rank, ...p } = toProject(row);
      return p as Project;
    },
    update: async (id: string, u: Partial<Project>): Promise<Project> => {
      // PUT replaces all four mutable fields; merge against the cached/known state.
      const current = await this.projects.getAll().then((all) => all.find((p) => p.id === id));
      const row = unwrap(
        await this.api.PATCH('/api/v1/projects/{id}', {
          params: { path: { id } },
          body: {
            name: u.name ?? current?.name ?? '',
            color: u.color ?? current?.color ?? '#6366f1',
            emoji: u.emoji ?? current?.emoji ?? '📁',
            rank: this.projectRanks.get(id) ?? 'n',
          } as never,
        }),
      );
      this.projectRanks.set(row.id, row.rank);
      const { _rank, ...p } = toProject(row);
      return p as Project;
    },
    delete: async (id: string): Promise<void> => {
      unwrap(await this.api.DELETE('/api/v1/projects/{id}', { params: { path: { id } } }));
      this.projectRanks.delete(id);
    },
    reorder: async (items: ReorderInput[]): Promise<void> => {
      const payload = this.computeReorder(this.projectRanks, items);
      if (payload.length === 0) return;
      unwrap(await this.api.PATCH('/api/v1/projects/reorder', { body: payload as never }));
    },
  };

  // ── note groups ────────────────────────────────────────────
  noteGroups = {
    getAll: async (): Promise<NoteGroup[]> => {
      const rows = unwrap(await this.api.GET('/api/v1/note-groups', {}));
      const mapped = rows.map(toNoteGroup);
      this.cacheRanks(this.noteGroupRanks, mapped);
      return this.withOrder(mapped).map(({ _rank, ...g }) => g as NoteGroup);
    },
    getByProject: async (projectId: string): Promise<NoteGroup[]> => {
      const rows = unwrap(
        await this.api.GET('/api/v1/note-groups', { params: { query: { projectId } } }),
      );
      const mapped = rows.map(toNoteGroup);
      for (const g of mapped) this.noteGroupRanks.set(g.id, g._rank);
      return this.withOrder(mapped).map(({ _rank, ...g }) => g as NoteGroup);
    },
    create: async (i: { name: string; emoji?: string; projectId?: string }): Promise<NoteGroup> => {
      const row = unwrap(
        await this.api.POST('/api/v1/note-groups', {
          body: {
            name: i.name,
            emoji: i.emoji ?? null,
            projectId: i.projectId ?? null,
            rank: this.rankForAppend(this.noteGroupRanks),
            id: newId(),
          } as never,
        }),
      );
      this.noteGroupRanks.set(row.id, row.rank);
      const { _rank, ...g } = toNoteGroup(row);
      return g as NoteGroup;
    },
    update: async (id: string, u: Partial<NoteGroup>): Promise<NoteGroup> => {
      const row = unwrap(
        await this.api.PATCH('/api/v1/note-groups/{id}', {
          params: { path: { id } },
          body: {
            name: u.name ?? null,
            emoji: u.emoji ?? null,
            projectId: u.projectId === undefined ? null : u.projectId,
            rank: null,
          } as never,
        }),
      );
      this.noteGroupRanks.set(row.id, row.rank);
      const { _rank, ...g } = toNoteGroup(row);
      return g as NoteGroup;
    },
    delete: async (id: string): Promise<void> => {
      unwrap(await this.api.DELETE('/api/v1/note-groups/{id}', { params: { path: { id } } }));
      this.noteGroupRanks.delete(id);
    },
    reorder: async (items: ReorderInput[]): Promise<void> => {
      const payload = this.computeReorder(this.noteGroupRanks, items);
      if (payload.length === 0) return;
      // Added in Q0 (§0.2): PATCH /api/v1/note-groups/reorder.
      unwrap(await this.api.PATCH('/api/v1/note-groups/reorder', { body: payload as never }));
    },
  };

  // ── notes ──────────────────────────────────────────────────
  notes = {
    getAll: async (): Promise<Note[]> => {
      const rows = unwrap(await this.api.GET('/api/v1/notes', {}));
      const mapped = rows.map(toNote);
      this.cacheRanks(this.noteRanks, mapped);
      return this.withOrder(mapped).map(({ _rank, ...n }) => n as Note);
    },
    getByGroup: async (groupId: string): Promise<Note[]> => {
      const rows = unwrap(await this.api.GET('/api/v1/notes', { params: { query: { groupId } } }));
      const mapped = rows.map(toNote);
      for (const n of mapped) this.noteRanks.set(n.id, n._rank);
      return this.withOrder(mapped).map(({ _rank, ...n }) => n as Note);
    },
    getByProject: async (projectId: string): Promise<Note[]> => {
      const rows = unwrap(
        await this.api.GET('/api/v1/notes', { params: { query: { projectId } } }),
      );
      const mapped = rows.map(toNote);
      for (const n of mapped) this.noteRanks.set(n.id, n._rank);
      return this.withOrder(mapped).map(({ _rank, ...n }) => n as Note);
    },
    getById: async (id: string): Promise<Note | null> => {
      const res = await this.api.GET('/api/v1/notes/{id}', { params: { path: { id } } });
      if (res.error) {
        return null;
      }
      const { _rank, ...n } = toNote(res.data as never);
      this.noteRanks.set(n.id, _rank);
      return n as Note;
    },
    create: async (i: {
      groupId?: string;
      projectId?: string;
      title?: string;
      content?: string;
    }): Promise<Note> => {
      const row = unwrap(
        await this.api.POST('/api/v1/notes', {
          body: {
            groupId: i.groupId ?? null,
            projectId: i.projectId ?? null,
            title: i.title ?? '',
            content: i.content ?? null,
            rank: this.rankForAppend(this.noteRanks),
            id: newId(),
          } as never,
        }),
      );
      this.noteRanks.set(row.id, row.rank);
      const { _rank, ...n } = toNote(row);
      return n as Note;
    },
    update: async (
      id: string,
      u: Partial<{
        title: string;
        content: string;
        groupId: string;
        projectId: string;
        order: number;
      }>,
    ): Promise<Note> => {
      const row = unwrap(
        await this.api.PATCH('/api/v1/notes/{id}', {
          params: { path: { id } },
          body: {
            groupId: u.groupId === undefined ? null : u.groupId,
            projectId: u.projectId === undefined ? null : u.projectId,
            title: u.title ?? null,
            content: u.content ?? null,
            rank: null,
          } as never,
        }),
      );
      this.noteRanks.set(row.id, row.rank);
      const { _rank, ...n } = toNote(row);
      return n as Note;
    },
    delete: async (id: string): Promise<void> => {
      unwrap(await this.api.DELETE('/api/v1/notes/{id}', { params: { path: { id } } }));
      this.noteRanks.delete(id);
    },
    reorder: async (items: ReorderInput[]): Promise<void> => {
      const payload = this.computeReorder(this.noteRanks, items);
      if (payload.length === 0) return;
      unwrap(await this.api.PATCH('/api/v1/notes/reorder', { body: payload as never }));
    },
  };

  // ── recurrence ─────────────────────────────────────────────
  recurrence = {
    create: async (task: Partial<Task>, rule: RecurrenceRuleInput): Promise<Task[]> => {
      unwrap(
        await this.api.POST('/api/v1/recurrence', {
          body: {
            task: {
              title: task.title ?? '',
              notes: task.notes ?? '',
              projectId: task.projectId ?? null,
              labels: task.labels ?? [],
              source: task.source ?? 'manual',
              plannedDate: task.plannedDate ?? null,
              durationMinutes: task.durationMinutes ?? 30,
              priority: task.priority ?? null,
              reminderMinutes: task.reminderMinutes ?? null,
              id: task.id ?? newId(),
            },
            rule: {
              frequency: toServerFrequency(rule.frequency),
              interval: rule.interval,
              daysOfWeek: rule.daysOfWeek,
              endType: toServerEndType(rule.endType),
              endCount: rule.endCount ?? null,
              endDate: rule.endDate ?? null,
            },
          } as never,
        }),
      );
      // Per §2: the store does a targeted reload after create (the create endpoint
      // returns a summary RecurringTaskResponse, not full instances). Return the
      // freshly-read full task list so the store can replace state without stubs.
      return this.tasks.getAll();
    },
    updateSeries: async (ruleId: string, u: Partial<Task>): Promise<void> => {
      unwrap(
        await this.api.PATCH('/api/v1/recurrence/rules/{ruleId}/series', {
          params: { path: { ruleId } },
          body: {
            title: u.title ?? null,
            notes: u.notes ?? null,
            projectId: u.projectId === undefined ? null : u.projectId,
            labels: u.labels ?? null,
            durationMinutes: u.durationMinutes ?? null,
            priority: u.priority ?? null,
            reminderMinutes: u.reminderMinutes ?? null,
          } as never,
        }),
      );
    },
    deleteSeries: async (ruleId: string): Promise<void> => {
      unwrap(
        await this.api.DELETE('/api/v1/recurrence/rules/{ruleId}', {
          params: { path: { ruleId } },
        }),
      );
    },
    deleteSeriesFuture: async (ruleId: string, from: string): Promise<void> => {
      unwrap(
        await this.api.POST('/api/v1/recurrence/rules/{ruleId}/delete-future', {
          params: { path: { ruleId } },
          body: { fromDate: from } as never,
        }),
      );
    },
    detachInstance: async (taskId: string): Promise<void> => {
      unwrap(
        await this.api.PATCH('/api/v1/recurrence/instances/{taskId}/detach', {
          params: { path: { taskId } },
        }),
      );
    },
    ensureInstances: async (start: string, end: string): Promise<Task[]> => {
      // Q0 (§0.3) makes this return full TaskResponse[]; map them as real Tasks.
      const data = unwrap(
        await this.api.POST('/api/v1/recurrence/ensure-instances', {
          params: { query: { start, end } },
        }),
      ) as { created: unknown };
      const created = (data.created ?? []) as Parameters<typeof toTask>[0][];
      const mapped = created.map((r) => ({ ...toTask(r), _rank: r.rank }));
      for (const r of mapped) this.taskRanks.set(r.id, r._rank);
      return mapped.map(({ _rank, ...t }) => t as Task);
    },
    updateRule: async (ruleId: string, u: RecurrenceRuleInput): Promise<void> => {
      unwrap(
        await this.api.PATCH('/api/v1/recurrence/rules/{ruleId}', {
          params: { path: { ruleId } },
          body: {
            frequency: toServerFrequency(u.frequency),
            interval: u.interval,
            daysOfWeek: u.daysOfWeek,
            endType: toServerEndType(u.endType),
            endCount: u.endCount ?? null,
            endDate: u.endDate ?? null,
          } as never,
        }),
      );
    },
    getRule: async (ruleId: string): Promise<RecurrenceRule | null> => {
      const res = await this.api.GET('/api/v1/recurrence/rules/{ruleId}', {
        params: { path: { ruleId } },
      });
      if (res.error) return null;
      return toRecurrenceRule(res.data as never);
    },
  };

  // ── focus sessions ─────────────────────────────────────────
  focusSessions = {
    add: async (s: {
      taskId: string | null;
      project: string;
      startedAt: string;
      endedAt: string;
      minutes: number;
      date: string;
    }): Promise<FocusSession> => {
      const row = unwrap(
        await this.api.POST('/api/v1/focus-sessions', {
          body: {
            taskId: s.taskId,
            project: s.project,
            startedAt: s.startedAt,
            endedAt: s.endedAt,
            minutes: s.minutes,
            date: s.date,
          } as never,
        }),
      );
      return toFocusSession(row);
    },
  };

  // ── daily plans ────────────────────────────────────────────
  dailyPlans = {
    upsert: async (p: {
      date: string;
      plannedTaskIds: string[];
      plannedMinutes: number;
    }): Promise<DailyPlan> => {
      const row = unwrap(
        await this.api.PUT('/api/v1/daily-plans/{date}', {
          params: { path: { date: p.date } },
          body: { plannedTaskIds: p.plannedTaskIds, plannedMinutes: p.plannedMinutes } as never,
        }),
      );
      return toDailyPlan(row);
    },
    get: async (date: string): Promise<DailyPlan | null> => {
      const res = await this.api.GET('/api/v1/daily-plans/{date}', {
        params: { path: { date } },
      });
      if (res.error) return null;
      return toDailyPlan(res.data as never);
    },
  };

  // ── reports ────────────────────────────────────────────────
  reports = {
    getData: async (start: string, end: string): Promise<ReportData> => {
      const data = unwrap(
        await this.api.GET('/api/v1/reports', { params: { query: { start, end } } }),
      );
      return toReportData(data);
    },
  };

  // ── settings ───────────────────────────────────────────────
  settings = {
    get: async (): Promise<{ settings: Record<string, unknown>; timeZoneId: string }> => {
      const data = unwrap(await this.api.GET('/api/v1/settings', {}));
      return { settings: parseSettings(data.settings ?? {}), timeZoneId: data.timeZoneId };
    },
    save: async (s: Record<string, unknown>, timeZoneId?: string): Promise<void> => {
      const body: { settings: Record<string, string>; timeZoneId?: string } = {
        settings: stringifySettings(s),
      };
      if (timeZoneId !== undefined) body.timeZoneId = timeZoneId;
      unwrap(await this.api.PUT('/api/v1/settings', { body: body as never }));
    },
  };
}
```

- [ ] **Gap to surface (do not invent):** the project `update` path uses `PATCH /projects/{id}` with `UpdateProjectRequest` whose schema (`{name,color,emoji,rank}`) is **non-partial** in the regenerated client (a `fix(projects)` commit made it a true PATCH server-side, but the generated `UpdateProjectRequest` still lists all four as required strings). The merge-against-current approach above sends all four. If Q0's regen makes `UpdateProjectRequest` nullable/partial, simplify `projects.update` to send only `u`'s keys. Flag in the Q2 completion note; do not change the api-client here.
- [ ] **Note on `settings.save` body:** the regenerated `SaveSettingsRequest` must include `timeZoneId` per §0.4. If Q0's regen does not yet expose `timeZoneId` on `SaveSettingsRequest`, the `body as never` cast still compiles and the server (which accepts it per the SP1 commit) applies it; keep the cast and flag for the api-client regen to add the field.
- [ ] Run `npm test`; confirm `HttpDataClient.test.ts` **PASSES** along with the prior specs.
- [ ] Run `npx tsc --noEmit -p tsconfig.json` from `packages/app`; confirm no type errors in `data/`.
- [ ] Commit: `feat(q2): HttpDataClient over @tmap/api-client (rank cache + reorder + DataClient seam)`.

---

### Task Q2-12: store — add the injected `dataClient` seam (setter + accessor)

The store currently calls `window.api.*` directly. We add a module-level `dataClient` that `AppRoot` injects (Q6 calls `setDataClient`). All actions in Q2-13 read it via `dc()`.

- [ ] At the top of `packages/app/src/store.ts`, after the imports, add the seam. Insert immediately after the `import { summarize, ... } from './lib/reports';` line:

```ts
import type { DataClient } from './data/DataClient';

// ── DataClient injection seam ───────────────────────────────
// The host (AppRoot, built per app entry) injects the concrete HttpDataClient
// after auth. Every data action reads it via dc(); calling a data action before
// injection is a programming error (auth gates the app, so this never happens
// in normal flow) and throws a clear message.
let _dataClient: DataClient | null = null;

/** Inject the DataClient. Called once by AppRoot after the client is built. */
export function setDataClient(client: DataClient): void {
  _dataClient = client;
}

/** Accessor used by every store data action. */
function dc(): DataClient {
  if (!_dataClient) {
    throw new Error('DataClient not initialized — call setDataClient() before using the store');
  }
  return _dataClient;
}
```

- [ ] No test step (this is wiring exercised by Q2-13's actions; the store compiles at the end of Q2-13).

### Task Q2-13: store — replace every `window.api.*` data call with `dc().*` (per action group)

Apply each edit below. These are the **only** lines that change; desktop-only groups (`window.api.app/data/focus/on/off`) are untouched (they move to Platform in Q5). After all edits, `window.api` no longer appears in `store.ts`.

**Group A — `logFocusSession` (projectId + dc):** the helper resolves the project **name** snapshot from `projectId` now (spec §3: `FocusSession.project` stays a name string on the wire). Replace:

```ts
  if (targetType === 'task') {
    taskId = targetId;
    project = tasks.find((t) => t.id === targetId)?.project || '';
  } else {
    project = projects.find((p) => p.id === targetId)?.name || '';
  }
  window.api.focusSessions
    .add({
```

with:

```ts
  if (targetType === 'task') {
    taskId = targetId;
    const t = tasks.find((tt) => tt.id === targetId);
    project = (t?.projectId ? projects.find((p) => p.id === t.projectId)?.name : '') || '';
  } else {
    project = projects.find((p) => p.id === targetId)?.name || '';
  }
  dc()
    .focusSessions.add({
```

**Group B — tasks load/CRUD/reorder.** Replace each `window.api.tasks.*` and the recurrence-instances call inside `loadTasks`:

- In `loadTasks`: `await window.api.tasks.getAll()` → `await dc().tasks.getAll()`.
- In `loadTasks` rollover: both `window.api.tasks.update(...)` → `dc().tasks.update(...)`.
- In `loadTasks` ensure-instances: `await window.api.recurrence.ensureInstances(today, twoWeeks)` → `await dc().recurrence.ensureInstances(today, twoWeeks)`.
- In `loadTasksByDate`: `await window.api.tasks.getByDate(date)` → `await dc().tasks.getByDate(date)`.
- In `createTask`: `await window.api.tasks.create(task)` → `await dc().tasks.create(task)`.
- In `updateTask`: `await window.api.tasks.update(id, updates)` → `await dc().tasks.update(id, updates)`.
- In `deleteTask`: `await window.api.tasks.delete(id)` → `await dc().tasks.delete(id)`.
- In `reorderTasks`: `await window.api.tasks.reorder(tasks)` → `await dc().tasks.reorder(tasks)`. (Optimistic local sort by `order` is unchanged — the HttpDataClient maps order→rank internally.)

**Group C — recurrence actions.**

- `createRecurringTask`: replace the body to use the full-task return (no more blind reload-and-return-null; spec §2 says map full rows). Change:

```ts
  createRecurringTask: async (task, rule) => {
    try {
      await window.api.recurrence.create(task, rule);
      // Reload all tasks since multiple instances were created
      await get().loadTasks();
      return null;
    } catch (e) {
      console.error('Failed to create recurring task:', e);
      return null;
    }
  },
```

to:

```ts
  createRecurringTask: async (task, rule) => {
    try {
      // HttpDataClient returns the full task list (targeted reload) after create.
      const tasks = await dc().recurrence.create(task, rule);
      set({ tasks });
      return null;
    } catch (e) {
      console.error('Failed to create recurring task:', e);
      return null;
    }
  },
```

- `updateRecurrenceSeries`: `await window.api.recurrence.updateSeries(ruleId, updates)` → `await dc().recurrence.updateSeries(ruleId, updates)`.
- `deleteRecurrenceSeries`: `await window.api.recurrence.deleteSeries(ruleId)` → `await dc().recurrence.deleteSeries(ruleId)`.
- `deleteRecurrenceSeriesFuture`: `await window.api.recurrence.deleteSeriesFuture(ruleId, fromDate)` → `await dc().recurrence.deleteSeriesFuture(ruleId, fromDate)`.
- `detachRecurrenceInstance`: `await window.api.recurrence.detachInstance(taskId)` → `await dc().recurrence.detachInstance(taskId)`.
- `ensureRecurrenceInstances`: `await window.api.recurrence.ensureInstances(startDate, endDate)` → `await dc().recurrence.ensureInstances(startDate, endDate)`.

**Group D — subtasks.**

- `createSubtask`: `await window.api.subtasks.create(taskId, title)` → `await dc().subtasks.create(taskId, title)`.
- `updateSubtask`: `await window.api.subtasks.update(subtaskId, updates)` → `await dc().subtasks.update(subtaskId, updates)`.
- `deleteSubtask`: `await window.api.subtasks.delete(subtaskId)` → `await dc().subtasks.delete(subtaskId)`.

**Group E — projects.**

- `loadProjects`: `await window.api.projects.getAll()` → `await dc().projects.getAll()`.
- `createProject`: `await window.api.projects.create(input)` → `await dc().projects.create(input)`.
- `updateProject`: `await window.api.projects.update(id, updates)` → `await dc().projects.update(id, updates)`.
- `deleteProject`: `await window.api.projects.delete(id)` → `await dc().projects.delete(id)`.
- `reorderProjects`: `await window.api.projects.reorder(items)` → `await dc().projects.reorder(items)`.

**Group F — note groups.**

- `loadNoteGroups`: `await window.api.noteGroups.getAll()` → `await dc().noteGroups.getAll()`.
- `createNoteGroup`: `await window.api.noteGroups.create(input)` → `await dc().noteGroups.create(input)`.
- `updateNoteGroup`: `await window.api.noteGroups.update(id, updates)` → `await dc().noteGroups.update(id, updates)`.
- `deleteNoteGroup`: `await window.api.noteGroups.delete(id)` → `await dc().noteGroups.delete(id)`.
- `reorderNoteGroups`: `await window.api.noteGroups.reorder(items)` → `await dc().noteGroups.reorder(items)`.

**Group G — notes.**

- `loadAllNotes`: `await window.api.notes.getAll()` → `await dc().notes.getAll()`.
- `loadNotesByProject`: `await window.api.notes.getByProject(projectId)` → `await dc().notes.getByProject(projectId)`.
- `createProjectNote`: `await window.api.notes.create({ projectId })` → `await dc().notes.create({ projectId })`.
- `loadNotesByGroup`: `await window.api.notes.getByGroup(groupId)` → `await dc().notes.getByGroup(groupId)`.
- `createNote`: `await window.api.notes.create({ groupId })` → `await dc().notes.create({ groupId })`.
- `updateNote`: `await window.api.notes.update(id, updates)` → `await dc().notes.update(id, updates)`.
- `deleteNote`: `await window.api.notes.delete(id)` → `await dc().notes.delete(id)`.
- `reorderNotes`: `await window.api.notes.reorder(items)` → `await dc().notes.reorder(items)`.

**Group H — daily plans + reports.**

- `commitDay`: `await window.api.dailyPlans.upsert({...})` → `await dc().dailyPlans.upsert({...})`.
- `loadReports`: both `window.api.reports.getData(...)` calls → `dc().reports.getData(...)`.

**Group I — settings (string⇄number + timeZoneId top-level).** The store now reads/writes through `dc().settings` (which already parses/stringifies numbers and partitions the synced keys). Local-only keys (`sidebarCollapsed`, `notesCollapsed`, `projectsCollapsed`) are **not** persisted to the server in SP2 — leave them out of the server payload (their localStorage handling is Q5/Q6 scope; here we simply stop sending them to the API). Replace `loadSettings`:

```ts
  loadSettings: async () => {
    try {
      const settings = await window.api.settings.get();
      if (settings && typeof settings === 'object') {
        const updates: Partial<AppState> = {};
        if (typeof settings.workStartHour === 'number')
          updates.workStartHour = settings.workStartHour;
        if (typeof settings.workEndHour === 'number') updates.workEndHour = settings.workEndHour;
        if (typeof settings.timeIncrement === 'number')
          updates.timeIncrement = settings.timeIncrement;
        if (typeof settings.sidebarCollapsed === 'boolean')
          updates.sidebarCollapsed = settings.sidebarCollapsed;
        if (typeof settings.notesCollapsed === 'boolean')
          updates.notesCollapsed = settings.notesCollapsed;
        if (typeof settings.projectsCollapsed === 'boolean')
          updates.projectsCollapsed = settings.projectsCollapsed;
        set(updates);
      }
    } catch (e) {
      console.error('Failed to load settings:', e);
    }
  },
```

with:

```ts
  loadSettings: async () => {
    try {
      const { settings } = await dc().settings.get();
      const updates: Partial<AppState> = {};
      if (typeof settings.workStartHour === 'number')
        updates.workStartHour = settings.workStartHour;
      if (typeof settings.workEndHour === 'number') updates.workEndHour = settings.workEndHour;
      if (typeof settings.timeIncrement === 'number')
        updates.timeIncrement = settings.timeIncrement;
      set(updates);
    } catch (e) {
      console.error('Failed to load settings:', e);
    }
  },
```

and replace `saveSettings`:

```ts
  saveSettings: async () => {
    try {
      const {
        workStartHour,
        workEndHour,
        timeIncrement,
        sidebarCollapsed,
        notesCollapsed,
        projectsCollapsed,
      } = get();
      await window.api.settings.save({
        workStartHour,
        workEndHour,
        timeIncrement,
        sidebarCollapsed,
        notesCollapsed,
        projectsCollapsed,
      });
    } catch (e) {
      console.error('Failed to save settings:', e);
    }
  },
```

with:

```ts
  saveSettings: async () => {
    try {
      const { workStartHour, workEndHour, timeIncrement } = get();
      // Only the three synced numeric values go to the server; local-only UI
      // collapse flags persist to localStorage (handled by the host in Q5/Q6).
      await dc().settings.save({ workStartHour, workEndHour, timeIncrement });
    } catch (e) {
      console.error('Failed to save settings:', e);
    }
  },
```

- [ ] After all Group A–I edits, run `npm test` from `packages/app`; existing specs (`ranking`, `mappers`, `HttpDataClient`) still **PASS** (no store specs exist; the store is exercised manually + by the build gate).
- [ ] Run `npx tsc --noEmit -p tsconfig.json` from `packages/app`; confirm `store.ts` has **no** type errors and **no** remaining `window.api` reference (search `window.api` in `store.ts` returns nothing).
- [ ] Commit: `refactor(q2): store calls injected DataClient instead of window.api for all data groups`.

---

### Task Q2-14: phase verification gate

- [ ] From `packages/app`: `npm test` — all of `ranking.test.ts`, `mappers.test.ts`, `HttpDataClient.test.ts` green.
- [ ] From `packages/app`: `npx tsc --noEmit -p tsconfig.json` — clean (the store, mappers, HttpDataClient, DataClient all typecheck against the new `Task.projectId`/`changeSeq`).
- [ ] Confirm `store.ts` no longer references `window.api.{tasks,subtasks,projects,noteGroups,notes,recurrence,focusSessions,dailyPlans,reports,settings}` (all data groups now go through `dc()`); desktop-only `window.api.{app,data,focus,on,off}` are intentionally **not** referenced in `store.ts` (they never were) and are left for Q5.
- [ ] **Do NOT** run `apps/desktop`/`apps/web` builds here — they still import the old `window.api` typing and the thin entries/AppRoot/Platform are Q5/Q6; the cross-app build gate runs in Q6. The Q2 gate is `packages/app` unit tests + `tsc --noEmit` only.
- [ ] **Completion notes to surface to the next phase / user:**
  1. `Task.projectId`/`changeSeq` are live in `types.ts`; the **component `\.project\b` sweep** (TaskDetailDialog select-by-id, TaskItem/DayTimeline/WeeklyBoardView/AllTasksView/Sidebar/ProjectView, `projectName(id)` selector) is **Q4** — components will not compile until then, so Q2 does not run app builds.
  2. `projects.update` in `HttpDataClient` sends all four `UpdateProjectRequest` fields (merge-against-current) because the regenerated `UpdateProjectRequest` is non-partial; simplify to partial if Q0's regen makes it nullable.
  3. `settings.save` relies on Q0 adding `timeZoneId` to `SaveSettingsRequest`; the cast compiles regardless, but the field should be added in the regen for type safety.
  4. `recurrence.ensureInstances`/`create` assume Q0's `EnsureInstancesResponse.created` carries **full `TaskResponse`** rows (§0.3); if the regen still returns the summary `CreatedInstance[]`, `ensureInstances` must fall back to `tasks.getAll()` — flagged, not silently stubbed.
  5. The `setDataClient(client)` seam is exported from `store.ts`; **Q6 `AppRoot`** must call it (with the `HttpDataClient` built from the auth-aware `refreshClient`) before any data action fires.
```

Phase Q2 task list authored above. Key grounding facts I verified against the actual repo (so the plan is concrete, not invented):

- **Server rank scheme** mirrored exactly from `backend/src/Tmap.Api/Common/Ranking.cs` (seed `"n"`, `RankAfter` increments last char / appends `"n"` at `'z'`, `RankBetween` pads with `'a'` and finds first gap > 1 from the right). `ranking.ts` reproduces it so client ranks interleave with server-seeded ones.
- **DTO shapes** taken from `packages/api-client/src/schema.d.ts`: every numeric is `number|string` (hence `Number()` coercion), `TaskStatus` is `"Inbox"|...|null` (hence null→`inbox`), `SubtaskResponse.sortOrder`, `TaskResponse.changeSeq`/`rank`/`projectId`, `SettingsResponse.{settings,timeZoneId}`, `ReorderItem {id, rank}`, and exact endpoint paths/verbs (`PATCH /tasks/reorder`, `POST /recurrence/ensure-instances` with `query {start,end}`, `PUT /daily-plans/{date}`, etc.).
- **Store refactor** enumerated against the real `apps/desktop/src/store.ts` — all 9 data groups + the `logFocusSession` helper (which currently reads `task.project`, now resolves name via `projectId`).

Gaps I surfaced rather than invented (in Q2-11 and Q2-14 completion notes):
- `UpdateProjectRequest` in the generated schema is **non-partial** (`{name,color,emoji,rank}` all required), conflicting with the recent "true PATCH" server commit — `HttpDataClient.projects.update` merges-against-current as a workaround and flags the regen.
- `SaveSettingsRequest` in the current schema has **no `timeZoneId`** field (§0.4 says Q0 adds it) — flagged.
- `EnsureInstancesResponse.created` is currently `CreatedInstance[]` (summary), not full `TaskResponse[]` — Q2 assumes Q0/§0.3 upgrades it and flags a `tasks.getAll()` fallback if not.

These three are Q0 (backend/regen) responsibilities per the shared contract; I used the contracted names and noted the dependency instead of redefining anything. The component `\.project\b` sweep and `projectName` selector are correctly deferred to Q4, and `setDataClient` wiring to Q6, per the phase boundaries.

---

## Phase Q3 — projectId migration across the client

This phase migrates every client-side task from `task.project` (name string) to `task.projectId` (uuid | null), relying on a `projectName(id)` selector in the store to resolve display names. The phase assumes `packages/app` is the canonical package produced by Q1/Q2 and that `Task.projectId: string | null` already appears in `packages/app/src/types.ts` (the Q1/Q2 contract). All edits target `packages/app/src/`.

**Files created:** `packages/app/src/__tests__/projectName.test.ts`

**Files modified:**
- `packages/app/src/types.ts` — remove `project: string`, verify `projectId: string | null` is present, verify `changeSeq` and `updatedAt` are present
- `packages/app/src/store.ts` — add `projectName` selector; fix `filteredTasks` search; fix `logFocusSession`
- `packages/app/src/components/TaskDetailDialog.tsx` — project `<select>` keyed by id, saves `projectId`
- `packages/app/src/components/TaskItem.tsx` — display name via `projectName`
- `packages/app/src/components/DayTimeline.tsx` — color lookup and label via `projectId`
- `packages/app/src/components/WeeklyBoardView.tsx` — `#projectName` display
- `packages/app/src/components/AllTasksView.tsx` — filter set, grouping, search, project name list
- `packages/app/src/components/Sidebar.tsx` — per-project task count
- `packages/app/src/components/ProjectView.tsx` — `t.projectId === project.id` filter

**ReportsView.tsx and `lib/reports.ts` are intentionally untouched** — reports remain name-based, resolved server-side (the `ProjectTime.project` field is the resolved name string on the wire; `ReportData.completedTasks[].project` is likewise; `summarize` and `timeByProject` are correct as-is). **QuickAdd.tsx is untouched** — no `#project` parsing exists there.

---

### Task Q3-1: Confirm and lock the `Task` type shape in `packages/app/src/types.ts`

**Goal:** verify that after Q1/Q2, `types.ts` contains `projectId: string | null` and does NOT contain `project: string` on the `Task` interface. Also verify `changeSeq: number` and `updatedAt: string` are present (Q2 contract). If any of these are missing, add/remove them as the first atomic commit of this phase. This is the foundation every later task depends on.

- [ ] Read `packages/app/src/types.ts`.
- [ ] Confirm `Task.projectId: string | null` is present. If absent, add it.
- [ ] Confirm `Task.project: string` is absent. If present, remove it.
- [ ] Confirm `Task.changeSeq: number` is present. If absent, add it.
- [ ] Confirm `Task.updatedAt: string` is present. If absent, add it (it was already in the old type so this should be a no-op).
- [ ] Confirm `FocusSession.project: string` remains (name snapshot on the wire — do NOT touch it).
- [ ] Confirm `ReportData.completedTasks[].project: string` remains (server-resolved name — do NOT touch it).
- [ ] Build gate: `cd packages/app && npm run build` must pass (or `tsc --noEmit`). Fix any import-level breakage before continuing.

**Before (in `Task`, `apps/desktop/src/types.ts` baseline):**
```ts
project: string;
// no projectId, no changeSeq
```

**After (in `packages/app/src/types.ts`):**
```ts
projectId: string | null;   // replaces project
changeSeq: number;          // sync-ready
updatedAt: string;          // already existed
// 'project: string' is gone from Task
```

**Manual verification:** `grep -r "project:" packages/app/src/types.ts` — must show only `projectId` (on Task), `projectId: string | null` (on NoteGroup, Note), and `project: string` inside `FocusSession` and `ReportData`. Zero raw `project: string` on `Task`.

---

### Task Q3-2: Add `projectName` selector to `packages/app/src/store.ts`

**Goal:** add a `projectName(projectId: string | null): string` selector that resolves a display name from the store's `projects` list. This becomes the single source of truth for every "show project name" site in the UI.

**Subtasks:**

- [ ] **Write a failing test** in `packages/app/src/__tests__/projectName.test.ts`:

```ts
// packages/app/src/__tests__/projectName.test.ts
import { describe, it, expect, beforeEach } from 'vitest';
import { useStore } from '../store';
import type { Project } from '../types';

// Helper: inject projects directly into the store for unit testing
function setProjects(projects: Project[]) {
  useStore.setState({ projects });
}

const P1: Project = {
  id: 'proj-1', name: 'Design', color: '#3b82f6', emoji: '🎨',
  order: 0, createdAt: '2024-01-01T00:00:00Z', updatedAt: '2024-01-01T00:00:00Z',
};
const P2: Project = {
  id: 'proj-2', name: 'Backend', color: '#22c55e', emoji: '🛠️',
  order: 1, createdAt: '2024-01-01T00:00:00Z', updatedAt: '2024-01-01T00:00:00Z',
};

describe('projectName selector', () => {
  beforeEach(() => setProjects([P1, P2]));

  it('resolves a known projectId to its name', () => {
    expect(useStore.getState().projectName('proj-1')).toBe('Design');
    expect(useStore.getState().projectName('proj-2')).toBe('Backend');
  });

  it('returns empty string for null', () => {
    expect(useStore.getState().projectName(null)).toBe('');
  });

  it('returns empty string for an unknown id', () => {
    expect(useStore.getState().projectName('unknown-id')).toBe('');
  });

  it('reflects updated project list', () => {
    setProjects([{ ...P1, name: 'Design v2' }]);
    expect(useStore.getState().projectName('proj-1')).toBe('Design v2');
  });
});
```

- [ ] Run `npm test` from `packages/app` — expect **FAIL** (selector does not exist yet).

- [ ] Add the selector to the `AppState` interface in `packages/app/src/store.ts`:

```ts
// In the AppState interface, under Computed:
projectName: (projectId: string | null) => string;
```

- [ ] Implement it in the store body (alongside `todayTasks`, `inboxTasks`, etc.):

```ts
projectName: (projectId: string | null) => {
  if (!projectId) return '';
  const { projects } = get();
  return projects.find((p) => p.id === projectId)?.name ?? '';
},
```

- [ ] Run `npm test` from `packages/app` — expect **PASS**.
- [ ] Commit with message: `feat(store): add projectName(id) selector for projectId migration`

---

### Task Q3-3: Migrate `store.ts` — `filteredTasks` search and `logFocusSession`

**Goal:** fix the two `task.project` usages in `store.ts` itself.

**Touch points (confirmed by grep):**
1. `store.ts:1268` — `t.project.toLowerCase().includes(q)` in `filteredTasks` search
2. `store.ts:54` — `tasks.find((t) => t.id === targetId)?.project || ''` in `logFocusSession`

**Subtasks:**

- [ ] **Write a failing test** for the search filter in `packages/app/src/__tests__/projectName.test.ts` (extend the existing file):

```ts
import type { Task } from '../types';

// Minimal task factory
function makeTask(overrides: Partial<Task>): Task {
  return {
    id: 't1', title: 'Test', notes: '', projectId: null,
    labels: [], source: 'manual',
    status: 'inbox', plannedDate: null, scheduledStart: null, scheduledEnd: null,
    durationMinutes: 30, priority: null, reminderMinutes: null, subtasks: [],
    order: 0, recurrenceRuleId: null, isRecurrenceTemplate: false,
    recurrenceDetached: false, recurrenceOriginalDate: null,
    createdAt: '2024-01-01T00:00:00Z', updatedAt: '2024-01-01T00:00:00Z', changeSeq: 0,
    ...overrides,
  };
}

describe('filteredTasks search includes project name', () => {
  beforeEach(() => {
    setProjects([P1, P2]);
    useStore.setState({
      tasks: [
        makeTask({ id: 't-design', title: 'UI work', projectId: 'proj-1' }),
        makeTask({ id: 't-none', title: 'No project task', projectId: null }),
        makeTask({ id: 't-backend', title: 'API fix', projectId: 'proj-2' }),
      ],
      currentView: 'board' as any,
      searchQuery: 'Design',
    });
  });

  it('finds tasks whose project name matches the search query', () => {
    const result = useStore.getState().filteredTasks();
    expect(result.map((t) => t.id)).toContain('t-design');
    expect(result.map((t) => t.id)).not.toContain('t-none');
  });
});
```

- [ ] Run `npm test` — expect **FAIL**.

- [ ] In `store.ts`, replace the `filteredTasks` search block:

**Before:**
```ts
return filtered.filter(
  (t) =>
    t.title.toLowerCase().includes(q) ||
    stripHtml(t.notes).toLowerCase().includes(q) ||
    t.project.toLowerCase().includes(q),
);
```

**After:**
```ts
const { projectName } = get();
return filtered.filter(
  (t) =>
    t.title.toLowerCase().includes(q) ||
    stripHtml(t.notes).toLowerCase().includes(q) ||
    projectName(t.projectId).toLowerCase().includes(q),
);
```

- [ ] In `logFocusSession`, replace the task-branch project resolution:

**Before (`store.ts:54`):**
```ts
if (targetType === 'task') {
  taskId = targetId;
  project = tasks.find((t) => t.id === targetId)?.project || '';
} else {
```

**After:**
```ts
if (targetType === 'task') {
  taskId = targetId;
  const focusTask = tasks.find((t) => t.id === targetId);
  project = focusTask ? get().projectName(focusTask.projectId) : '';
} else {
```

- [ ] Run `npm test` — expect **PASS**.
- [ ] Commit: `fix(store): use projectId/projectName in filteredTasks search and logFocusSession`

---

### Task Q3-4: Migrate `TaskDetailDialog.tsx`

**Goal:** the project `<select>` must be keyed by project **id** (not name), and `handleSave` must write `projectId` (not `project`).

**Before (key lines):**
```ts
// State
const [project, setProject] = useState('');

// Populate on edit open
setProject(task.project || '');

// Create mode reset
setProject('');

// handleSave
const taskData: Partial<Task> = {
  ...
  project,
  ...
};

// JSX: select options keyed by name, value = name
<option value="">No project</option>
{projects.map((p) => (
  <option key={p.id} value={p.name}>
    {p.emoji} {p.name}
  </option>
))}
```

**After:**
```ts
// State — rename variable for clarity
const [projectId, setProjectId] = useState<string | null>(null);

// Populate on edit open
setProjectId(task.projectId ?? null);

// Create mode reset
setProjectId(null);

// handleSave
const taskData: Partial<Task> = {
  ...
  projectId,
  ...
};

// JSX: select value = id, onChange sets id
<select
  value={projectId ?? ''}
  onChange={(e) => setProjectId(e.target.value || null)}
  ...
>
  <option value="">No project</option>
  {projects.map((p) => (
    <option key={p.id} value={p.id}>
      {p.emoji} {p.name}
    </option>
  ))}
</select>
```

**Subtasks:**

- [ ] Open `packages/app/src/components/TaskDetailDialog.tsx`.
- [ ] Rename state variable `project` → `projectId`, type `string | null`, initial value `null`.
- [ ] Update the `useEffect` `populate-on-open` block: `setProjectId(task.projectId ?? null)` (edit mode) and `setProjectId(null)` (create mode).
- [ ] Update `handleSave`'s `taskData` to use `projectId` (not `project`).
- [ ] Update the `<select>` value and `onChange` to use the id.
- [ ] Update each `<option>` to use `value={p.id}` (not `value={p.name}`).
- [ ] Verify there are no remaining references to `task.project` in this file (`grep "\.project" TaskDetailDialog.tsx` must return zero results after the edit).
- [ ] **Manual verification:** run `npm run dev` from `apps/desktop`; open a task; change its project in the dialog; save; confirm the project label shows correctly in `TaskItem` and `ProjectView`.
- [ ] Commit: `fix(TaskDetailDialog): key project select by id, save projectId`

---

### Task Q3-5: Migrate `TaskItem.tsx`

**Goal:** replace `task.project` (name string) with a resolved display name via the `projectName` selector.

**Before:**
```tsx
// Meta section
{task.project && (
  <span className="chip text-2xs hidden group-hover:inline-flex sm:inline-flex">
    <Folder className="w-2.5 h-2.5" />
    {task.project}
  </span>
)}
```

**After:**
```tsx
// Near top of component, pull selector:
const { ..., projectName } = useStore();
// OR via a derived variable inside the component:
const pName = useStore((s) => s.projectName(task.projectId));

// Meta section
{task.projectId && (
  <span className="chip text-2xs hidden group-hover:inline-flex sm:inline-flex">
    <Folder className="w-2.5 h-2.5" />
    {pName}
  </span>
)}
```

**Subtasks:**

- [ ] Open `packages/app/src/components/TaskItem.tsx`.
- [ ] Add a selector call to resolve the project name: `const pName = useStore((s) => s.projectName(task.projectId));`
- [ ] Replace `task.project &&` guard with `task.projectId &&`.
- [ ] Replace `{task.project}` text with `{pName}`.
- [ ] Confirm no other `task.project` references remain in this file.
- [ ] **Manual verification:** task with a project shows the project name chip; task without a project shows no chip.
- [ ] Commit: `fix(TaskItem): resolve project name via projectName selector`

---

### Task Q3-6: Migrate `DayTimeline.tsx`

**Goal:** the timeline block resolves color and project label via `projectId`, not via `p.name === task.project`.

**Before (lines ~383, ~506–513):**
```ts
const { projects } = useStore();
const projectObj = projects.find((p) => p.name === task.project);
const projectColor = projectObj?.color || '#6366f1';

// render:
{currentHeight > 60 && task.project && (
  <p
    dir={getTextDirection(task.project!)}
    className="text-2xs mt-0.5"
    style={{ ...getDirectionStyle(task.project!), color: projectColor + '99' }}
  >
    {task.project}
  </p>
)}
```

**After:**
```ts
const projects = useStore((s) => s.projects);
const projectName = useStore((s) => s.projectName);
const projectObj = projects.find((p) => p.id === task.projectId);
const projectColor = projectObj?.color || '#6366f1';
const pName = projectName(task.projectId);

// render:
{currentHeight > 60 && task.projectId && (
  <p
    dir={getTextDirection(pName)}
    className="text-2xs mt-0.5"
    style={{ ...getDirectionStyle(pName), color: projectColor + '99' }}
  >
    {pName}
  </p>
)}
```

**Subtasks:**

- [ ] Open `packages/app/src/components/DayTimeline.tsx`.
- [ ] Locate the `TimeBlock` inner component (around line 382).
- [ ] Change `projects.find((p) => p.name === task.project)` to `projects.find((p) => p.id === task.projectId)`.
- [ ] Add `const pName = useStore((s) => s.projectName)(task.projectId);` (or use `projectName` pulled from the same `useStore` call).
- [ ] Replace the three `task.project` references in the render block (`task.project &&`, `getTextDirection(task.project!)`, `getDirectionStyle(task.project!)`, `{task.project}`) with `task.projectId &&`, `pName`, `pName`, `{pName}`.
- [ ] Confirm no other `task.project` usages remain in this file.
- [ ] **Manual verification:** schedule a task with a project on the timeline; confirm the project label appears in the correct color.
- [ ] Commit: `fix(DayTimeline): look up project by id for color and label`

---

### Task Q3-7: Migrate `WeeklyBoardView.tsx`

**Goal:** replace `task.project` display in the weekly board task cards with a resolved name.

**Before (lines ~616–624):**
```tsx
{task.project && (
  <span
    dir={getTextDirection(task.project)}
    style={getDirectionStyle(task.project)}
    className="text-2xs text-accent-500 font-medium"
  >
    #{task.project}
  </span>
)}
```

**After:**
```tsx
// Inside the weekly task card component, near the top:
const pName = useStore((s) => s.projectName(task.projectId));

{task.projectId && (
  <span
    dir={getTextDirection(pName)}
    style={getDirectionStyle(pName)}
    className="text-2xs text-accent-500 font-medium"
  >
    #{pName}
  </span>
)}
```

**Subtasks:**

- [ ] Open `packages/app/src/components/WeeklyBoardView.tsx`.
- [ ] Locate the weekly task card inner component (the one that renders `task.project`).
- [ ] Add `const pName = useStore((s) => s.projectName(task.projectId));` at the top of that component's body.
- [ ] Replace the three `task.project` references (`task.project &&`, `getTextDirection(task.project)`, `getDirectionStyle(task.project)`, `#{task.project}`) with `task.projectId &&`, `pName`, `pName`, `#{pName}`.
- [ ] Confirm no other `task.project` usages remain in this file.
- [ ] **Manual verification:** open week view; confirm project tags appear on task cards with the correct name.
- [ ] Commit: `fix(WeeklyBoardView): resolve project name via projectId`

---

### Task Q3-8: Migrate `AllTasksView.tsx`

**Goal:** the project filter, group-by, search, and project name list all operate on project names resolved from the store's `projects` list (keyed by id). This is the most involved component change because the filter set, the group key, and the project name list all need updating.

**Changes required (confirmed by grep):**

| Location | Before | After |
|---|---|---|
| `filteredTasks` filter, line ~171 | `const proj = t.project \|\| '';` | `const proj = t.projectId \|\| '';` |
| `filteredTasks` filter, line ~172 | `if (!selectedProjects.has(proj))` | unchanged (now filtering by id, not name) |
| `filteredTasks` search, line ~186 | `!t.project.toLowerCase().includes(q)` | `!projectName(t.projectId).toLowerCase().includes(q)` |
| `groups` useMemo, line ~284 | `key = task.project \|\| 'No Project';` | `key = task.projectId \|\| 'no-project';` then resolve display label separately |
| `allProjectNames` useMemo, line ~317 | `if (t.project) names.add(t.project);` | build `allProjects` from store's `projects` list directly (ordered, not from tasks) |
| Filter dropdown render (~lines 450–471) | keyed/labeled by name string | keyed/labeled by project id, display `p.emoji + ' ' + p.name` |
| `SavedFilters.selectedProjects` | `string[] \| null` (names) | `string[] \| null` (ids) — existing localStorage values become stale; handle gracefully (treat as null on mismatch) |

**Subtasks:**

- [ ] Open `packages/app/src/components/AllTasksView.tsx`.
- [ ] Pull `projectName` from the store: add `const projectName = useStore((s) => s.projectName);` (or destructure from the top-level `useStore` call).
- [ ] Also pull the structured `projects` list: `const { tasks, projects, reorderTasks } = useStore();` (already has `projects` if AllTasksView reads from store; confirm and keep).
- [ ] In the `filteredTasks` useMemo, change the project filter:

**Before:**
```ts
if (selectedProjects !== null) {
  const proj = t.project || '';
  if (!selectedProjects.has(proj)) return false;
}
```

**After:**
```ts
if (selectedProjects !== null) {
  const proj = t.projectId || '';
  if (!selectedProjects.has(proj)) return false;
}
```

- [ ] In the `filteredTasks` useMemo, change the search project check:

**Before:**
```ts
!t.project.toLowerCase().includes(q)
```

**After:**
```ts
!projectName(t.projectId).toLowerCase().includes(q)
```

- [ ] In the `groups` useMemo, change the `'project'` group-by branch:

**Before:**
```ts
case 'project':
  key = task.project || 'No Project';
  break;
```

**After:**
```ts
case 'project':
  key = task.projectId || '';
  break;
```

And update the label resolution for groups:

**Before:**
```ts
return order.map((key) => ({
  key,
  label: groupBy === 'status' ? STATUS_LABELS[key] || key : key,
  tasks: map.get(key)!,
}));
```

**After:**
```ts
return order.map((key) => ({
  key,
  label:
    groupBy === 'status'
      ? STATUS_LABELS[key] || key
      : groupBy === 'project'
        ? key === ''
          ? 'No Project'
          : projectName(key)
        : key,
  tasks: map.get(key)!,
}));
```

- [ ] Replace the `allProjectNames` computation with a stable `allProjectIds` derived from the store's `projects` list (which is already sorted by order):

**Before:**
```ts
const allProjectNames = useMemo(() => {
  const names = new Set<string>();
  for (const t of tasks) {
    if (t.project) names.add(t.project);
  }
  return Array.from(names).sort();
}, [tasks]);
```

**After:**
```ts
// projects already comes from useStore; no useMemo needed — it's already stable
// rename variable for clarity:
const allProjectIds = projects.map((p) => p.id);
```

- [ ] Update the Project filter dropdown to render using `allProjectIds` and project objects:

**Before:**
```tsx
const allProjectNames = useMemo(...)
// ...
<FilterCheckbox
  label="No Project"
  checked={selectedProjects === null || selectedProjects.has('')}
  ...
/>
{allProjectNames.map((name) => (
  <FilterCheckbox
    key={name}
    label={name}
    checked={selectedProjects === null || selectedProjects.has(name)}
    onChange={(checked) => {
      if (selectedProjects === null) {
        const full = new Set(['', ...allProjectNames]);
        if (!checked) full.delete(name);
        setSelectedProjects(full);
      } else {
        const next = new Set(selectedProjects);
        checked ? next.add(name) : next.delete(name);
        if (next.size === allProjectNames.length + 1) {
          setSelectedProjects(null);
        } else {
          setSelectedProjects(next);
        }
      }
    }}
  />
))}
```

**After:**
```tsx
// allProjectIds = projects.map(p => p.id)  (no useMemo — just derived inline)
<FilterCheckbox
  label="No Project"
  checked={selectedProjects === null || selectedProjects.has('')}
  onChange={(checked) => {
    if (selectedProjects === null) {
      const full = new Set(['', ...allProjectIds]);
      if (!checked) full.delete('');
      setSelectedProjects(full);
    } else {
      const next = new Set(selectedProjects);
      checked ? next.add('') : next.delete('');
      if (next.size === allProjectIds.length + 1) {
        setSelectedProjects(null);
      } else {
        setSelectedProjects(next);
      }
    }
  }}
/>
{projects.map((p) => (
  <FilterCheckbox
    key={p.id}
    label={`${p.emoji} ${p.name}`}
    checked={selectedProjects === null || selectedProjects.has(p.id)}
    onChange={(checked) => {
      if (selectedProjects === null) {
        const full = new Set(['', ...allProjectIds]);
        if (!checked) full.delete(p.id);
        setSelectedProjects(full);
      } else {
        const next = new Set(selectedProjects);
        checked ? next.add(p.id) : next.delete(p.id);
        if (next.size === allProjectIds.length + 1) {
          setSelectedProjects(null);
        } else {
          setSelectedProjects(next);
        }
      }
    }}
  />
))}
```

- [ ] Update the `FilterDropdown` count line to reflect id-set size rather than name-set size (the count is `selectedProjects?.size ?? allProjectIds.length + 1` — just replace `allProjectNames.length` with `allProjectIds.length`).

- [ ] Handle stale localStorage: in `loadFilters()`, the saved `selectedProjects` array may contain old name strings (from before this migration). The safest approach is to clear stale values on load: after `loadFilters()`, validate that every saved project id exists in `projects`; if any are missing (indicating old name-based data), reset `selectedProjects` to `null`.

```ts
// After useMemo for filteredTasks deps, add a one-time effect:
useEffect(() => {
  if (saved.selectedProjects) {
    const validIds = new Set(projects.map((p) => p.id).concat(['']));
    const allValid = saved.selectedProjects.every((id) => validIds.has(id));
    if (!allValid) {
      setSelectedProjects(null); // stale name-based saved filter — reset gracefully
    }
  }
}, []); // run once on mount
```

- [ ] Update the `FilterDropdown` label for count display — replace `allProjectNames.length` references with `allProjectIds.length`.
- [ ] Confirm no remaining `t.project` or `task.project` references in this file.
- [ ] **Manual verification:** open All Tasks; use the Project filter dropdown — it shows project names with emoji; filter by project and confirm only tasks with that `projectId` show; group by Project — group headers show correct names; search "Design" finds tasks whose projectId resolves to "Design".
- [ ] Commit: `fix(AllTasksView): migrate project filter and grouping to projectId`

---

### Task Q3-9: Migrate `Sidebar.tsx`

**Goal:** the per-project task count uses `t.project === project.name`; replace with `t.projectId === project.id`.

**Before (line ~295):**
```ts
tasks.filter(
  (t) =>
    t.project === project.name &&
    t.status !== 'archived' &&
    t.status !== 'done',
),
```

**After:**
```ts
tasks.filter(
  (t) =>
    t.projectId === project.id &&
    t.status !== 'archived' &&
    t.status !== 'done',
),
```

**Subtasks:**

- [ ] Open `packages/app/src/components/Sidebar.tsx`.
- [ ] Locate the `count` computation inside the `projects.map(...)` block.
- [ ] Replace `t.project === project.name` with `t.projectId === project.id`.
- [ ] Confirm no other `task.project` or `t.project` references remain.
- [ ] **Manual verification:** the project sidebar shows the correct task count next to each project name.
- [ ] Commit: `fix(Sidebar): count project tasks by projectId instead of name`

---

### Task Q3-10: Migrate `ProjectView.tsx`

**Goal:** `projectTasks` filter uses `t.project === project.name`; replace with `t.projectId === project.id`.

**Before (line ~91):**
```ts
let result = tasks
  .filter((t) => t.project === project.name && t.status !== 'archived')
  .sort(...)
```

**After:**
```ts
let result = tasks
  .filter((t) => t.projectId === project.id && t.status !== 'archived')
  .sort(...)
```

**Subtasks:**

- [ ] Open `packages/app/src/components/ProjectView.tsx`.
- [ ] Replace `t.project === project.name` with `t.projectId === project.id`.
- [ ] Confirm no other `t.project` or `task.project` references remain in this file.
- [ ] **Manual verification:** click a project in the sidebar; confirm the ProjectView shows only tasks whose `projectId` matches; confirm tasks created in the dialog with that project appear there.
- [ ] Commit: `fix(ProjectView): filter tasks by projectId`

---

### Task Q3-11: Final sweep — grep proof + build gate

**Goal:** prove that no stray `task.project` (name-string) usage remains in `packages/app/src`, and that both apps build cleanly.

**Subtasks:**

- [ ] Run the grep:

```powershell
# From repo root — PowerShell
Get-ChildItem -Recurse "packages/app/src" -Include "*.ts","*.tsx" |
  Select-String -Pattern "\.project\b" |
  Where-Object { $_ -notmatch "projectId" -and $_ -notmatch "projectName" -and $_ -notmatch "FocusSession" -and $_ -notmatch "ReportData" -and $_ -notmatch "\.project:" -and $_ -notmatch "topProject" }
```

Expected output: **zero lines**. Any match is a remaining stale reference that must be fixed before this task passes.

Also run the POSIX equivalent for CI:

```bash
grep -rn '\.project\b' packages/app/src --include="*.ts" --include="*.tsx" \
  | grep -v 'projectId\|projectName\|topProject\|FocusSession\|ReportData\|\.project:'
```

Expected: **no output**.

- [ ] Run all unit tests: `cd packages/app && npm test` — expect all Q3 tests to pass (the `projectName` selector tests from Q3-2, the `filteredTasks` search tests from Q3-3).

- [ ] Run TypeScript check for the shared package: `cd packages/app && npx tsc --noEmit`

- [ ] Run desktop app build gate: `cd apps/desktop && npm run build` — must exit 0.

- [ ] If `apps/web` is set up by Q1/Q2: `cd apps/web && npm run build` — must exit 0.

- [ ] Capture and record the grep output (empty), the test output (all pass), and the build output (exit 0) as the acceptance evidence for this phase.

- [ ] Commit: `chore(Q3): grep-clean + build gate for projectId migration`

---

### Task Q3-12: Mapper alignment note (for `mappers.ts` author — cross-phase contract)

**Goal:** document the exact contract `HttpDataClient` / `mappers.ts` (Q2) must satisfy so Q3's store code is correct. This is a non-code task — it records a gap if mappers are not yet written, so the Q2 author can align.

- [ ] Confirm that `toTask` in `packages/app/src/data/mappers.ts` maps `serverDto.projectId` → `domainTask.projectId` (uuid string or null). It must **not** set `task.project`.
- [ ] Confirm that `fromTask` in `mappers.ts` maps `domainTask.projectId` → `serverDto.projectId` (not a name string). Creating a task sends `projectId` to the API.
- [ ] Confirm that `toTask` sets `changeSeq: Number(dto.changeSeq ?? 0)` and `updatedAt: dto.updatedAt`.
- [ ] If `mappers.ts` does not yet exist (Q2 not started), leave a `// TODO(Q2): mappers.ts must set task.projectId, not task.project` comment at the top of `packages/app/src/data/HttpDataClient.ts` (or create a stub file with just the comment and an `export {}` so the import graph compiles).
- [ ] Confirm `FocusSession` mapper (if any) passes the resolved project **name** string on the wire (not the id), consistent with `FocusSession.project: string` on the domain type.

This task has no test and no build gate beyond `tsc --noEmit`. It closes the contract loop between Q2 (mappers) and Q3 (store + UI).

---

**Summary of Q3 deliverables:**

| Artifact | Status after Q3 |
|---|---|
| `types.ts` | `Task.projectId: string \| null`; `project: string` removed from Task |
| `store.ts` | `projectName(id)` selector; `filteredTasks` search uses it; `logFocusSession` resolves name from id |
| `__tests__/projectName.test.ts` | All tests green (selector + search filter) |
| `TaskDetailDialog.tsx` | `<select>` keyed by id; saves `projectId` |
| `TaskItem.tsx` | Shows resolved name; guards on `projectId` |
| `DayTimeline.tsx` | Color lookup and label via `projectId` |
| `WeeklyBoardView.tsx` | `#projectName` resolved from id |
| `AllTasksView.tsx` | Filter/group/search all use `projectId`; localStorage stale-value guard |
| `Sidebar.tsx` | Count via `projectId` |
| `ProjectView.tsx` | Filter via `projectId` |
| grep | Zero `\.project\b` matches not on whitelist |
| `npm test` | All pass |
| `npm run build` (both apps) | Exit 0 |

---

## Phase Q4 — Auth: authStore, refreshClient, Login/Register, AppRoot gating

This phase adds the client auth surface: `AuthTokenResponse` types, the `authStore` (Zustand) state machine, the hardened `refreshClient` (single-flight refresh-on-401 + idempotent retry + logout abort), `LoginView`/`RegisterView`, and `AppRoot` gating. Token storage goes through the injected `platform.auth` (desktop keychain-in-main; web httpOnly cookie); access token stays in memory.

### Task Q4-1: Create `auth/types.ts` — `AuthTokenResponse`, `AuthUser`, `ProblemDetails`, and parsing helpers

- [ ] **Check for a pre-existing `AuthTokenResponse`.** Run a grep first; if `packages/app/src/platform/Platform.ts` already `export`s `AuthTokenResponse`, then in `auth/types.ts` **re-export it** (`export type { AuthTokenResponse, AuthUser } from '../platform/Platform';`) instead of redefining, and skip the two type bodies below (keep `ProblemDetails` + helpers). Otherwise define them here and have `Platform.ts` import from here. The shared-contract name is `AuthTokenResponse = { accessToken: string; expiresIn: number; user: { id: string; email: string; timeZoneId: string } }` — do not rename.

```bash
# from repo root
grep -rn "AuthTokenResponse" "packages/app/src/platform/Platform.ts"
```

- [ ] **Write the file** `packages/app/src/auth/types.ts` (full code):

```ts
// packages/app/src/auth/types.ts
// Canonical auth types + ProblemDetails parsing for the SP2 clients.
// AuthTokenResponse name/shape is fixed by the SP2 shared contract — do not rename.

export interface AuthUser {
  id: string;
  email: string;
  timeZoneId: string;
}

export interface AuthTokenResponse {
  accessToken: string;
  expiresIn: number;
  user: AuthUser;
}

/** RFC 9457 ProblemDetails (matches the API's HttpValidationProblemDetails). */
export interface ProblemDetails {
  type?: string | null;
  title?: string | null;
  status?: number | null;
  detail?: string | null;
  instance?: string | null;
  errors?: Record<string, string[]>;
}

/**
 * Runtime guard: narrows an unknown response body to AuthTokenResponse.
 * Defends the auth store against an untyped/regen-lagging OpenAPI body.
 */
export function unwrapAuth(body: unknown): AuthTokenResponse {
  const b = body as Partial<AuthTokenResponse> | null | undefined;
  if (
    !b ||
    typeof b.accessToken !== 'string' ||
    typeof b.expiresIn !== 'number' ||
    !b.user ||
    typeof b.user.id !== 'string' ||
    typeof b.user.email !== 'string' ||
    typeof b.user.timeZoneId !== 'string'
  ) {
    throw new Error('Malformed auth response from server');
  }
  return {
    accessToken: b.accessToken,
    expiresIn: b.expiresIn,
    user: { id: b.user.id, email: b.user.email, timeZoneId: b.user.timeZoneId },
  };
}

/** True for a ProblemDetails-shaped object (has title or errors). */
export function isProblemDetails(x: unknown): x is ProblemDetails {
  if (!x || typeof x !== 'object') return false;
  const p = x as Record<string, unknown>;
  return 'title' in p || 'errors' in p || 'detail' in p || 'status' in p;
}

/**
 * Flattens a ProblemDetails into a single human-readable message for a form banner.
 * Prefers field errors (joined), then detail, then title, then a fallback.
 */
export function problemToMessage(p: ProblemDetails | null | undefined, fallback: string): string {
  if (!p) return fallback;
  if (p.errors) {
    const msgs = Object.values(p.errors).flat().filter(Boolean);
    if (msgs.length) return msgs.join(' ');
  }
  if (p.detail) return p.detail;
  if (p.title) return p.title;
  return fallback;
}
```

- [ ] **No test for plain interfaces**, but `unwrapAuth`/`problemToMessage` are exercised in the authStore tests (Q4-7). Run a quick typecheck to confirm it compiles.

```bash
cd packages/app && npx tsc --noEmit
```

- [ ] **Commit:**

```bash
git add packages/app/src/auth/types.ts && git commit -m "feat(app/auth): AuthTokenResponse + ProblemDetails types and parsing helpers

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task Q4-2: Write the failing test for `refreshClient` — single-flight + queue + one-retry + no-recurse

- [ ] **Create the test** `packages/app/src/auth/__tests__/refreshClient.test.ts`. `refreshClient` wraps a `TmapClient`-shaped object. To unit-test the 401/refresh/retry logic without HTTP, the test injects a **fake client** whose methods (`GET`/`POST`/`PATCH`/`DELETE`) return `{ data, error, response }` exactly like `openapi-fetch`, and a **fake `refresh()`** function (stands in for `platform.auth.refreshAndGetAccess`). The wrapper's contract (from §5):
  - On a `401` from any wrapped call: await one shared in-flight `refresh()`, then retry the original call **once** with the new token applied.
  - Concurrent `401`s share **one** `refresh()` call (single-flight).
  - If `refresh()` fails/returns null: the whole queue **rejects** with an auth error (no further retries), and `onLogout()` is called once.
  - A `401` originating from the refresh path itself must **never recurse** into refresh — it logs out immediately. (Modeled by the fake `refresh()` throwing/returning null.)
  - Retried **creates** (POST with a body carrying `id`) reuse the same client-generated `id` on the retry (no double-create): the wrapper must not mutate the body between attempts.
  - After `signOut()` (logout flag set), the wrapper makes **no new** `refresh()` and rejects pending work; in-flight calls are aborted via the injected `AbortController` signal.

```ts
// packages/app/src/auth/__tests__/refreshClient.test.ts
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { createRefreshClient } from '../refreshClient';
import type { AuthTokenResponse } from '../types';

// A fake openapi-fetch-shaped result.
type FetchResult = { data?: unknown; error?: unknown; response: { status: number } };

function ok(data: unknown): FetchResult {
  return { data, error: undefined, response: { status: 200 } };
}
function unauthorized(): FetchResult {
  return { data: undefined, error: { title: 'Unauthorized' }, response: { status: 401 } };
}

function makeAuth(token: string): AuthTokenResponse {
  return { accessToken: token, expiresIn: 900, user: { id: 'u1', email: 'a@b.c', timeZoneId: 'UTC' } };
}

describe('createRefreshClient', () => {
  let getCalls: { path: string; init: any }[];
  let fakeClient: any;
  let refresh: ReturnType<typeof vi.fn>;
  let onLogout: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    getCalls = [];
  });

  // Builds a fake client whose GET fails with 401 the first `failFirst` times, then 200.
  function buildClient(failFirst: number) {
    let calls = 0;
    return {
      GET: vi.fn(async (path: string, init: any) => {
        getCalls.push({ path, init });
        calls += 1;
        if (calls <= failFirst) return unauthorized();
        return ok({ path, attempt: calls });
      }),
      POST: vi.fn(async (path: string, init: any) => {
        getCalls.push({ path, init });
        calls += 1;
        if (calls <= failFirst) return unauthorized();
        return ok({ id: init?.body?.id, attempt: calls });
      }),
      PATCH: vi.fn(async () => ok({})),
      DELETE: vi.fn(async () => ok({})),
    };
  }

  it('retries once after a single-flight refresh on 401 (GET succeeds on retry)', async () => {
    fakeClient = buildClient(1);
    refresh = vi.fn(async () => makeAuth('new-token'));
    onLogout = vi.fn();
    const rc = createRefreshClient({ client: fakeClient, refresh, onLogout });

    const res = await rc.GET('/api/v1/tasks', {});
    expect(refresh).toHaveBeenCalledTimes(1);
    expect((res as FetchResult).response.status).toBe(200);
    expect(fakeClient.GET).toHaveBeenCalledTimes(2); // original + one retry
  });

  it('coalesces concurrent 401s into a single refresh call', async () => {
    fakeClient = buildClient(1);
    // Each underlying call fails once then succeeds; refresh must be called only ONCE total.
    let calls = 0;
    fakeClient.GET = vi.fn(async (path: string, init: any) => {
      getCalls.push({ path, init });
      calls += 1;
      // first two calls (the two originals) are 401; subsequent (retries) are 200
      return calls <= 2 ? unauthorized() : ok({ path });
    });
    refresh = vi.fn(async () => {
      await new Promise((r) => setTimeout(r, 10));
      return makeAuth('new-token');
    });
    onLogout = vi.fn();
    const rc = createRefreshClient({ client: fakeClient, refresh, onLogout });

    const [a, b] = await Promise.all([rc.GET('/api/v1/tasks', {}), rc.GET('/api/v1/projects', {})]);
    expect(refresh).toHaveBeenCalledTimes(1);
    expect((a as FetchResult).response.status).toBe(200);
    expect((b as FetchResult).response.status).toBe(200);
  });

  it('rejects the whole queue and logs out when refresh fails (returns null)', async () => {
    fakeClient = buildClient(99); // always 401
    refresh = vi.fn(async () => null); // refresh fails
    onLogout = vi.fn();
    const rc = createRefreshClient({ client: fakeClient, refresh, onLogout });

    await expect(rc.GET('/api/v1/tasks', {})).rejects.toThrow();
    expect(onLogout).toHaveBeenCalledTimes(1);
    expect(refresh).toHaveBeenCalledTimes(1);
  });

  it('never recurses: a refresh that throws logs out exactly once', async () => {
    fakeClient = buildClient(99);
    refresh = vi.fn(async () => {
      throw new Error('refresh endpoint 401');
    });
    onLogout = vi.fn();
    const rc = createRefreshClient({ client: fakeClient, refresh, onLogout });

    await expect(rc.GET('/api/v1/tasks', {})).rejects.toThrow();
    expect(refresh).toHaveBeenCalledTimes(1); // no recursion
    expect(onLogout).toHaveBeenCalledTimes(1);
  });

  it('retried create reuses the same client id (no double-create)', async () => {
    fakeClient = buildClient(1);
    refresh = vi.fn(async () => makeAuth('new-token'));
    onLogout = vi.fn();
    const rc = createRefreshClient({ client: fakeClient, refresh, onLogout });

    await rc.POST('/api/v1/tasks', { body: { id: 'uuid-v7-abc', title: 'x' } });
    const ids = getCalls.map((c) => c.init?.body?.id);
    expect(ids).toEqual(['uuid-v7-abc', 'uuid-v7-abc']); // original + retry, same id
  });

  it('after signOut(), makes no new refresh and rejects calls', async () => {
    fakeClient = buildClient(99);
    refresh = vi.fn(async () => makeAuth('new-token'));
    onLogout = vi.fn();
    const rc = createRefreshClient({ client: fakeClient, refresh, onLogout });

    rc.signOut();
    await expect(rc.GET('/api/v1/tasks', {})).rejects.toThrow();
    expect(refresh).not.toHaveBeenCalled();
  });
});
```

- [ ] **Run it — expect FAIL** (module not found):

```bash
cd packages/app && npm test -- refreshClient
```

- [ ] **Commit the failing test:**

```bash
git add packages/app/src/auth/__tests__/refreshClient.test.ts && git commit -m "test(app/auth): failing refreshClient single-flight/queue/no-recurse spec

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task Q4-3: Implement `refreshClient.ts` — minimal code to pass Q4-2

- [ ] **Write the file** `packages/app/src/auth/refreshClient.ts` (full code). It wraps a `TmapClient` and exposes the same four verb methods plus `signOut()` and `setAbortController()`. Single-flight refresh, one retry, no-recurse, queue-reject, idempotent-create (body untouched between attempts), and signal-abort on signout.

```ts
// packages/app/src/auth/refreshClient.ts
// Wraps a TmapClient (openapi-fetch) with refresh-on-401: single-flight refresh,
// one retry per request, no recursion through the refresh path, queue rejection on
// refresh failure, and AbortController-based teardown on logout. (SP2 spec §5.)
import type { TmapClient } from '@tmap/api-client';
import type { AuthTokenResponse } from './types';

type Verb = 'GET' | 'POST' | 'PATCH' | 'DELETE' | 'PUT';
type FetchLike = (path: string, init?: any) => Promise<any>;

/** The subset of TmapClient verbs the store uses; structurally satisfied by the real client. */
export interface RefreshClient {
  GET: FetchLike;
  POST: FetchLike;
  PATCH: FetchLike;
  PUT: FetchLike;
  DELETE: FetchLike;
  /** Marks the wrapper as signing out: no new refresh, pending/new calls reject, in-flight abort. */
  signOut(): void;
  /** Sets the AbortController whose signal is attached to every wrapped request. */
  setAbortController(ac: AbortController): void;
}

export interface RefreshClientOptions {
  /** The underlying typed client (real TmapClient, or a structural fake in tests). */
  client: Pick<TmapClient, Verb> | Record<Verb, FetchLike>;
  /** Performs a single-flight token refresh; returns null/throws to signal failure. */
  refresh: () => Promise<AuthTokenResponse | null>;
  /** Called exactly once when refresh fails or the refresh path itself 401s. */
  onLogout: () => void;
}

class AuthError extends Error {
  constructor(message = 'Authentication required') {
    super(message);
    this.name = 'AuthError';
  }
}

export function createRefreshClient(opts: RefreshClientOptions): RefreshClient {
  const { client, refresh, onLogout } = opts;
  let signingOut = false;
  let loggedOut = false;
  let refreshPromise: Promise<AuthTokenResponse | null> | null = null;
  let abortController: AbortController | undefined;

  function doLogoutOnce(): void {
    if (loggedOut) return;
    loggedOut = true;
    onLogout();
  }

  // Single-flight: concurrent callers share one in-flight refresh promise.
  function sharedRefresh(): Promise<AuthTokenResponse | null> {
    if (!refreshPromise) {
      refreshPromise = (async () => {
        try {
          return await refresh();
        } finally {
          refreshPromise = null;
        }
      })();
    }
    return refreshPromise;
  }

  function withSignal(init: any): any {
    if (abortController?.signal) {
      return { ...(init ?? {}), signal: (init ?? {}).signal ?? abortController.signal };
    }
    return init;
  }

  function call(verb: Verb, path: string, init?: any): Promise<any> {
    if (signingOut || loggedOut) {
      return Promise.reject(new AuthError('Signed out'));
    }
    const fn = (client as Record<Verb, FetchLike>)[verb];
    // NOTE: `init` (incl. body with a client-generated id) is passed unchanged on both
    // the original attempt and the retry, so a retried create cannot double-create.
    return Promise.resolve(fn(path, withSignal(init))).then(async (res) => {
      const status = res?.response?.status;
      if (status !== 401) return res;

      // 401: attempt a single shared refresh, then retry once.
      if (signingOut || loggedOut) throw new AuthError('Signed out');

      let auth: AuthTokenResponse | null;
      try {
        auth = await sharedRefresh();
      } catch {
        doLogoutOnce(); // refresh path failed/401 — never recurse.
        throw new AuthError('Session expired');
      }
      if (!auth) {
        doLogoutOnce();
        throw new AuthError('Session expired');
      }
      if (signingOut || loggedOut) throw new AuthError('Signed out');

      // Retry exactly once. The underlying client's auth middleware reads the now-updated
      // in-memory access token (set by `refresh`), so no header juggling here.
      const retry = await Promise.resolve(fn(path, withSignal(init)));
      return retry;
    });
  }

  return {
    GET: (p, i) => call('GET', p, i),
    POST: (p, i) => call('POST', p, i),
    PATCH: (p, i) => call('PATCH', p, i),
    PUT: (p, i) => call('PUT', p, i),
    DELETE: (p, i) => call('DELETE', p, i),
    signOut() {
      signingOut = true;
      abortController?.abort();
    },
    setAbortController(ac: AbortController) {
      abortController = ac;
    },
  };
}
```

- [ ] **Run — expect PASS:**

```bash
cd packages/app && npm test -- refreshClient
```

- [ ] **Commit:**

```bash
git add packages/app/src/auth/refreshClient.ts && git commit -m "feat(app/auth): refreshClient — single-flight 401 refresh + idempotent one-retry

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task Q4-4: Write the failing test for `authStore` — initial state + register/login transitions

- [ ] **Create the test** `packages/app/src/auth/__tests__/authStore.test.ts`. The store is created by a **factory** `createAuthStore({ client, platform, onAuthed, onLoggedOut })` so each test gets a fresh isolated store (no global singleton leakage between tests). `client` is a structural fake (the four verbs); `platform` is a minimal fake `Pick<Platform,'auth'>`. This first test covers: initial `status === 'loading'`; `register` → `authed` with user + in-memory `accessToken`; `login` → `authed`; a `login` that 401s stays `anonymous` and surfaces a ProblemDetails message; `onAuthed` fires on success.

```ts
// packages/app/src/auth/__tests__/authStore.test.ts
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { createAuthStore } from '../authStore';
import type { AuthTokenResponse } from '../types';

function ok(data: unknown) {
  return { data, error: undefined, response: { status: 200 } };
}
function fail(status: number, problem: unknown) {
  return { data: undefined, error: problem, response: { status } };
}
function auth(token: string): AuthTokenResponse {
  return { accessToken: token, expiresIn: 900, user: { id: 'u1', email: 'a@b.c', timeZoneId: 'UTC' } };
}

function makePlatform(refreshImpl: () => Promise<AuthTokenResponse | null>) {
  return {
    auth: {
      refreshAndGetAccess: vi.fn(refreshImpl),
      clear: vi.fn(async () => {}),
    },
  };
}

describe('authStore — register/login transitions', () => {
  let client: any;
  let onAuthed: ReturnType<typeof vi.fn>;
  let onLoggedOut: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    onAuthed = vi.fn();
    onLoggedOut = vi.fn();
    client = {
      POST: vi.fn(),
      GET: vi.fn(),
      PATCH: vi.fn(),
      PUT: vi.fn(),
      DELETE: vi.fn(),
    };
  });

  it('starts in loading status with no user/token', () => {
    const platform = makePlatform(async () => null);
    const store = createAuthStore({ client, platform, onAuthed, onLoggedOut });
    const s = store.getState();
    expect(s.status).toBe('loading');
    expect(s.user).toBeNull();
    expect(s.accessToken).toBeNull();
  });

  it('register success → authed, sets user + in-memory access token, fires onAuthed', async () => {
    client.POST.mockImplementation(async (path: string) => {
      if (path === '/api/v1/auth/register') return ok(auth('tok-1'));
      return ok({});
    });
    const platform = makePlatform(async () => null);
    const store = createAuthStore({ client, platform, onAuthed, onLoggedOut });

    await store.getState().register('a@b.c', 'password123');
    const s = store.getState();
    expect(s.status).toBe('authed');
    expect(s.user).toEqual({ id: 'u1', email: 'a@b.c', timeZoneId: 'UTC' });
    expect(s.accessToken).toBe('tok-1');
    expect(onAuthed).toHaveBeenCalledTimes(1);
    expect(client.POST).toHaveBeenCalledWith(
      '/api/v1/auth/register',
      expect.objectContaining({ body: { email: 'a@b.c', password: 'password123' } }),
    );
  });

  it('login success → authed and exposes the access token to the client middleware', async () => {
    client.POST.mockImplementation(async (path: string) =>
      path === '/api/v1/auth/login' ? ok(auth('tok-login')) : ok({}),
    );
    const platform = makePlatform(async () => null);
    const store = createAuthStore({ client, platform, onAuthed, onLoggedOut });

    await store.getState().login('a@b.c', 'password123');
    expect(store.getState().status).toBe('authed');
    expect(store.getState().getAccessToken()).toBe('tok-login');
  });

  it('login 401 → stays anonymous and sets an error message from ProblemDetails', async () => {
    client.POST.mockImplementation(async () =>
      fail(401, { title: 'Invalid credentials', status: 401 }),
    );
    const platform = makePlatform(async () => null);
    const store = createAuthStore({ client, platform, onAuthed, onLoggedOut });

    await expect(store.getState().login('a@b.c', 'wrong')).rejects.toThrow();
    const s = store.getState();
    expect(s.status).toBe('anonymous');
    expect(s.error).toMatch(/Invalid credentials/);
    expect(onAuthed).not.toHaveBeenCalled();
  });

  it('register 400 with field errors → anonymous and joined field-error message', async () => {
    client.POST.mockImplementation(async () =>
      fail(400, { title: 'Bad', errors: { Password: ['Password too short'] } }),
    );
    const platform = makePlatform(async () => null);
    const store = createAuthStore({ client, platform, onAuthed, onLoggedOut });

    await expect(store.getState().register('a@b.c', 'x')).rejects.toThrow();
    expect(store.getState().status).toBe('anonymous');
    expect(store.getState().error).toMatch(/Password too short/);
  });
});
```

- [ ] **Run — expect FAIL** (module not found):

```bash
cd packages/app && npm test -- authStore
```

- [ ] **Commit the failing test:**

```bash
git add packages/app/src/auth/__tests__/authStore.test.ts && git commit -m "test(app/auth): failing authStore register/login transition spec

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task Q4-5: Write the failing test for `authStore.bootstrap` — network-vs-401 + logout abort

- [ ] **Append a second describe block** to `packages/app/src/auth/__tests__/authStore.test.ts` (same file). It covers: `bootstrap` with a valid refresh → `authed`; `bootstrap` where refresh returns `null` (a real 401) → `anonymous` **and** `platform.auth.clear()` called; `bootstrap` where refresh **throws a network error** → `anonymous` **but `platform.auth.clear()` NOT called** and `networkError` flag set (don't destroy a valid token); `logout` → `anonymous`, `platform.auth.clear()` called, `onLoggedOut` fired, access token cleared.

```ts
// --- append to packages/app/src/auth/__tests__/authStore.test.ts ---
import { describe as describe2, it as it2, expect as expect2, vi as vi2, beforeEach as beforeEach2 } from 'vitest';

describe2('authStore — bootstrap network-vs-401 + logout', () => {
  let client: any;
  let onAuthed: ReturnType<typeof vi2.fn>;
  let onLoggedOut: ReturnType<typeof vi2.fn>;

  function ok2(data: unknown) {
    return { data, error: undefined, response: { status: 200 } };
  }
  function auth2(token: string) {
    return { accessToken: token, expiresIn: 900, user: { id: 'u1', email: 'a@b.c', timeZoneId: 'UTC' } };
  }
  function makePlatform2(refreshImpl: () => Promise<any>) {
    return { auth: { refreshAndGetAccess: vi2.fn(refreshImpl), clear: vi2.fn(async () => {}) } };
  }

  beforeEach2(() => {
    onAuthed = vi2.fn();
    onLoggedOut = vi2.fn();
    client = { POST: vi2.fn(async () => ok2({})), GET: vi2.fn(), PATCH: vi2.fn(), PUT: vi2.fn(), DELETE: vi2.fn() };
  });

  it2('bootstrap with valid refresh → authed', async () => {
    const platform = makePlatform2(async () => auth2('boot-tok'));
    const store = createAuthStore({ client, platform, onAuthed, onLoggedOut });
    await store.getState().bootstrap();
    const s = store.getState();
    expect2(s.status).toBe('authed');
    expect2(s.accessToken).toBe('boot-tok');
    expect2(s.user?.id).toBe('u1');
    expect2(onAuthed).toHaveBeenCalledTimes(1);
  });

  it2('bootstrap refresh returns null (401) → anonymous AND clears stored token', async () => {
    const platform = makePlatform2(async () => null);
    const store = createAuthStore({ client, platform, onAuthed, onLoggedOut });
    await store.getState().bootstrap();
    expect2(store.getState().status).toBe('anonymous');
    expect2(store.getState().networkError).toBe(false);
    expect2(platform.auth.clear).toHaveBeenCalledTimes(1);
  });

  it2('bootstrap refresh throws network error → anonymous, token NOT cleared, networkError set', async () => {
    const platform = makePlatform2(async () => {
      throw new TypeError('Failed to fetch');
    });
    const store = createAuthStore({ client, platform, onAuthed, onLoggedOut });
    await store.getState().bootstrap();
    const s = store.getState();
    expect2(s.status).toBe('anonymous');
    expect2(s.networkError).toBe(true);
    expect2(platform.auth.clear).not.toHaveBeenCalled();
  });

  it2('logout → anonymous, clears token, calls platform.clear + onLoggedOut', async () => {
    const platform = makePlatform2(async () => auth2('boot-tok'));
    const store = createAuthStore({ client, platform, onAuthed, onLoggedOut });
    await store.getState().bootstrap(); // authed
    await store.getState().logout();
    const s = store.getState();
    expect2(s.status).toBe('anonymous');
    expect2(s.accessToken).toBeNull();
    expect2(s.user).toBeNull();
    expect2(platform.auth.clear).toHaveBeenCalledTimes(1);
    expect2(onLoggedOut).toHaveBeenCalledTimes(1);
    expect2(client.POST).toHaveBeenCalledWith('/api/v1/auth/logout', expect2.anything());
  });
});
```

- [ ] **Run — expect FAIL** (functions/flags not implemented yet):

```bash
cd packages/app && npm test -- authStore
```

- [ ] **Commit the failing test:**

```bash
git add packages/app/src/auth/__tests__/authStore.test.ts && git commit -m "test(app/auth): failing authStore bootstrap network-vs-401 + logout spec

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task Q4-6: Implement `authStore.ts` (factory + default singleton) — pass Q4-4 and Q4-5

- [ ] **Write the file** `packages/app/src/auth/authStore.ts` (full code). It exports `createAuthStore(deps)` (used by tests for isolation) and `useAuthStore` (the app singleton, built lazily from injected deps via `initAuthStore`). The store: `status`, `user`, `accessToken` (memory only), `error`, `networkError`; `getAccessToken()` (handed to `createTmapClient`'s middleware); `register`, `login`, `logout`, `bootstrap`; internal `refresh` single-flight via `platform.auth.refreshAndGetAccess`. Network-vs-401 distinction: `refreshAndGetAccess()` returning `null` is a 401 (clear token); a **thrown** error is a network failure (don't clear). The factory takes `client`, `platform`, `onAuthed`, `onLoggedOut`.

```ts
// packages/app/src/auth/authStore.ts
// Zustand auth store: status machine + in-memory access token + single-flight refresh.
// Token storage is delegated to platform.auth (desktop keychain-in-main / web httpOnly cookie).
import { createStore, type StoreApi } from 'zustand/vanilla';
import { useStore as useZustand } from 'zustand';
import type { Platform } from '../platform/Platform';
import {
  type AuthTokenResponse,
  type AuthUser,
  isProblemDetails,
  problemToMessage,
  unwrapAuth,
} from './types';

type Verb = 'GET' | 'POST' | 'PATCH' | 'PUT' | 'DELETE';
type FetchLike = (path: string, init?: any) => Promise<any>;

export type AuthStatus = 'loading' | 'anonymous' | 'authed';

export interface AuthState {
  status: AuthStatus;
  user: AuthUser | null;
  accessToken: string | null;
  error: string | null;
  /** True when the last bootstrap failed due to a network (not auth) error. */
  networkError: boolean;
  /** Synchronous token getter handed to createTmapClient's middleware. */
  getAccessToken: () => string | null;
  register: (email: string, password: string) => Promise<void>;
  login: (email: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
  bootstrap: () => Promise<void>;
  /** Internal single-flight refresh; returns the new token or null on 401. */
  refresh: () => Promise<AuthTokenResponse | null>;
}

export interface AuthStoreDeps {
  client: Record<Verb, FetchLike>;
  platform: Pick<Platform, 'auth'>;
  /** Called after a successful authed transition (register/login/bootstrap). */
  onAuthed: (auth: AuthTokenResponse) => void;
  /** Called after logout completes (status -> anonymous). */
  onLoggedOut: () => void;
}

function isNetworkError(e: unknown): boolean {
  // fetch network failures surface as TypeError ("Failed to fetch") or AbortError.
  if (e instanceof TypeError) return true;
  const name = (e as { name?: string } | null)?.name;
  return name === 'TypeError' || name === 'AbortError' || name === 'NetworkError';
}

export function createAuthStore(deps: AuthStoreDeps): StoreApi<AuthState> {
  const { client, platform, onAuthed, onLoggedOut } = deps;
  let refreshPromise: Promise<AuthTokenResponse | null> | null = null;

  const store = createStore<AuthState>((set, get) => ({
    status: 'loading',
    user: null,
    accessToken: null,
    error: null,
    networkError: false,

    getAccessToken: () => get().accessToken,

    async register(email, password) {
      set({ error: null });
      const res = await client.POST('/api/v1/auth/register', { body: { email, password } });
      if (res?.error || res?.response?.status >= 400) {
        const msg = isProblemDetails(res?.error)
          ? problemToMessage(res.error, 'Registration failed')
          : 'Registration failed';
        set({ error: msg, status: get().status === 'authed' ? 'authed' : 'anonymous' });
        throw new Error(msg);
      }
      const auth = unwrapAuth(res.data);
      set({ status: 'authed', user: auth.user, accessToken: auth.accessToken, error: null, networkError: false });
      onAuthed(auth);
    },

    async login(email, password) {
      set({ error: null });
      const res = await client.POST('/api/v1/auth/login', { body: { email, password } });
      if (res?.error || res?.response?.status >= 400) {
        const msg = isProblemDetails(res?.error)
          ? problemToMessage(res.error, 'Invalid email or password')
          : 'Invalid email or password';
        set({ error: msg, status: get().status === 'authed' ? 'authed' : 'anonymous' });
        throw new Error(msg);
      }
      const auth = unwrapAuth(res.data);
      set({ status: 'authed', user: auth.user, accessToken: auth.accessToken, error: null, networkError: false });
      onAuthed(auth);
    },

    async logout() {
      // Best-effort server revoke; never block local sign-out on it.
      try {
        await client.POST('/api/v1/auth/logout', { body: { refreshToken: null } });
      } catch {
        /* ignore — local sign-out proceeds regardless */
      }
      try {
        await platform.auth.clear();
      } catch {
        /* ignore */
      }
      set({ status: 'anonymous', user: null, accessToken: null, error: null });
      onLoggedOut();
    },

    refresh() {
      if (!refreshPromise) {
        refreshPromise = (async () => {
          try {
            const auth = await platform.auth.refreshAndGetAccess();
            if (auth) {
              set({ accessToken: auth.accessToken, user: auth.user });
            }
            return auth;
          } finally {
            refreshPromise = null;
          }
        })();
      }
      return refreshPromise;
    },

    async bootstrap() {
      set({ status: 'loading', error: null, networkError: false });
      try {
        const auth = await get().refresh();
        if (auth) {
          set({ status: 'authed', user: auth.user, accessToken: auth.accessToken, networkError: false });
          onAuthed(auth);
        } else {
          // refresh returned null === a real 401: stored token is dead, clear it.
          try {
            await platform.auth.clear();
          } catch {
            /* ignore */
          }
          set({ status: 'anonymous', user: null, accessToken: null, networkError: false });
        }
      } catch (e) {
        if (isNetworkError(e)) {
          // Do NOT destroy a (possibly valid) stored token on a transient network failure.
          set({ status: 'anonymous', user: null, accessToken: null, networkError: true });
        } else {
          try {
            await platform.auth.clear();
          } catch {
            /* ignore */
          }
          set({ status: 'anonymous', user: null, accessToken: null, networkError: false });
        }
      }
    },
  }));

  return store;
}

// --- App singleton (built once from real deps at app entry via initAuthStore) ---
let appStore: StoreApi<AuthState> | null = null;

export function initAuthStore(deps: AuthStoreDeps): StoreApi<AuthState> {
  appStore = createAuthStore(deps);
  return appStore;
}

export function getAuthStore(): StoreApi<AuthState> {
  if (!appStore) throw new Error('authStore not initialized — call initAuthStore() at app entry');
  return appStore;
}

/** React hook over the app singleton; pass a selector. */
export function useAuthStore<T>(selector: (s: AuthState) => T): T {
  return useZustand(getAuthStore(), selector);
}
```

- [ ] **Run — expect PASS for both authStore blocks:**

```bash
cd packages/app && npm test -- authStore
```

- [ ] **Commit:**

```bash
git add packages/app/src/auth/authStore.ts && git commit -m "feat(app/auth): authStore — status machine, in-memory token, single-flight refresh

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task Q4-7: Add the auth barrel `auth/index.ts`

- [ ] **Write** `packages/app/src/auth/index.ts` (full code):

```ts
// packages/app/src/auth/index.ts
export type { AuthTokenResponse, AuthUser, ProblemDetails } from './types';
export { unwrapAuth, isProblemDetails, problemToMessage } from './types';
export { createRefreshClient } from './refreshClient';
export type { RefreshClient, RefreshClientOptions } from './refreshClient';
export {
  createAuthStore,
  initAuthStore,
  getAuthStore,
  useAuthStore,
} from './authStore';
export type { AuthState, AuthStatus, AuthStoreDeps } from './authStore';
export { LoginView } from './LoginView';
export { RegisterView } from './RegisterView';
```

- [ ] **Typecheck** (LoginView/RegisterView don't exist yet — this barrel line will error until Q4-8; create the barrel now but expect the two view exports to fail typecheck until Q4-8 lands. To keep the tree green, temporarily comment the last two lines, then uncomment in Q4-8):

```ts
// (temporary until Q4-8)
// export { LoginView } from './LoginView';
// export { RegisterView } from './RegisterView';
```

- [ ] **Run the full suite — expect PASS (unchanged):**

```bash
cd packages/app && npm test
```

- [ ] **Commit:**

```bash
git add packages/app/src/auth/index.ts && git commit -m "feat(app/auth): auth barrel exports (views pending)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task Q4-8: Implement `LoginView.tsx` and `RegisterView.tsx` (full UI, ProblemDetails errors)

> No component-test harness in this repo (per conventions), so these are full-code + manual-verify tasks. Both use the existing Tailwind utility classes (`.input-base`, `.btn-primary`, `.btn-ghost`, `surface-*`/`accent-*`/`danger`) so they match the app shell.

- [ ] **Write** `packages/app/src/auth/LoginView.tsx` (full code). It reads `status`/`error` from the auth singleton, submits via `login`, shows the ProblemDetails-derived error in a danger banner, disables the form while submitting, and offers a "Create an account" switch.

```tsx
// packages/app/src/auth/LoginView.tsx
import React, { useState } from 'react';
import { useAuthStore } from './authStore';

interface LoginViewProps {
  onSwitchToRegister: () => void;
}

export function LoginView({ onSwitchToRegister }: LoginViewProps) {
  const login = useAuthStore((s) => s.login);
  const error = useAuthStore((s) => s.error);
  const networkError = useAuthStore((s) => s.networkError);
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (submitting) return;
    setSubmitting(true);
    try {
      await login(email.trim(), password);
    } catch {
      /* error surfaced via store.error */
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="h-screen flex items-center justify-center bg-surface-950 text-surface-200 select-none">
      <div className="fixed top-0 left-0 right-0 h-10 titlebar-drag-region z-50" />
      <form
        onSubmit={handleSubmit}
        className="w-full max-w-sm bg-surface-900/60 border border-surface-800/60 rounded-2xl p-8 shadow-2xl"
      >
        <h1 className="text-2xl font-bold text-surface-50 mb-1">Welcome back</h1>
        <p className="text-sm text-surface-400 mb-6">Sign in to your TMap account.</p>

        {networkError && (
          <div className="mb-4 rounded-lg border border-amber-700/40 bg-amber-900/30 px-3 py-2 text-sm text-amber-300">
            Couldn&apos;t reach the server. Check your connection and try again.
          </div>
        )}
        {error && !networkError && (
          <div
            role="alert"
            className="mb-4 rounded-lg border border-red-700/40 bg-red-900/30 px-3 py-2 text-sm text-red-300"
          >
            {error}
          </div>
        )}

        <label className="block mb-3">
          <span className="block text-xs font-medium text-surface-400 mb-1">Email</span>
          <input
            type="email"
            autoComplete="email"
            required
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            disabled={submitting}
            className="input-base w-full"
            placeholder="you@example.com"
          />
        </label>

        <label className="block mb-5">
          <span className="block text-xs font-medium text-surface-400 mb-1">Password</span>
          <input
            type="password"
            autoComplete="current-password"
            required
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            disabled={submitting}
            className="input-base w-full"
            placeholder="••••••••"
          />
        </label>

        <button type="submit" disabled={submitting} className="btn-primary w-full disabled:opacity-50">
          {submitting ? 'Signing in…' : 'Sign in'}
        </button>

        <p className="mt-5 text-center text-sm text-surface-400">
          Don&apos;t have an account?{' '}
          <button
            type="button"
            onClick={onSwitchToRegister}
            className="text-accent-400 hover:text-accent-300 font-medium"
          >
            Create one
          </button>
        </p>
      </form>
    </div>
  );
}
```

- [ ] **Write** `packages/app/src/auth/RegisterView.tsx` (full code). Same shape; adds a client-side password-length hint (server is source of truth — passphrase length policy per SP1 §3.1), submits via `register`, shows ProblemDetails field errors, and a "Sign in instead" switch.

```tsx
// packages/app/src/auth/RegisterView.tsx
import React, { useState } from 'react';
import { useAuthStore } from './authStore';

interface RegisterViewProps {
  onSwitchToLogin: () => void;
}

const MIN_PASSWORD = 8;

export function RegisterView({ onSwitchToLogin }: RegisterViewProps) {
  const register = useAuthStore((s) => s.register);
  const error = useAuthStore((s) => s.error);
  const networkError = useAuthStore((s) => s.networkError);
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const tooShort = password.length > 0 && password.length < MIN_PASSWORD;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (submitting || tooShort) return;
    setSubmitting(true);
    try {
      await register(email.trim(), password);
    } catch {
      /* error surfaced via store.error */
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="h-screen flex items-center justify-center bg-surface-950 text-surface-200 select-none">
      <div className="fixed top-0 left-0 right-0 h-10 titlebar-drag-region z-50" />
      <form
        onSubmit={handleSubmit}
        className="w-full max-w-sm bg-surface-900/60 border border-surface-800/60 rounded-2xl p-8 shadow-2xl"
      >
        <h1 className="text-2xl font-bold text-surface-50 mb-1">Create your account</h1>
        <p className="text-sm text-surface-400 mb-6">Start planning your days with TMap.</p>

        {networkError && (
          <div className="mb-4 rounded-lg border border-amber-700/40 bg-amber-900/30 px-3 py-2 text-sm text-amber-300">
            Couldn&apos;t reach the server. Check your connection and try again.
          </div>
        )}
        {error && !networkError && (
          <div
            role="alert"
            className="mb-4 rounded-lg border border-red-700/40 bg-red-900/30 px-3 py-2 text-sm text-red-300"
          >
            {error}
          </div>
        )}

        <label className="block mb-3">
          <span className="block text-xs font-medium text-surface-400 mb-1">Email</span>
          <input
            type="email"
            autoComplete="email"
            required
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            disabled={submitting}
            className="input-base w-full"
            placeholder="you@example.com"
          />
        </label>

        <label className="block mb-2">
          <span className="block text-xs font-medium text-surface-400 mb-1">Password</span>
          <input
            type="password"
            autoComplete="new-password"
            required
            minLength={MIN_PASSWORD}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            disabled={submitting}
            className="input-base w-full"
            placeholder="At least 8 characters"
          />
        </label>
        <p className={`mb-5 text-xs ${tooShort ? 'text-red-400' : 'text-surface-500'}`}>
          Use {MIN_PASSWORD}+ characters. A passphrase is stronger than a short complex password.
        </p>

        <button
          type="submit"
          disabled={submitting || tooShort}
          className="btn-primary w-full disabled:opacity-50"
        >
          {submitting ? 'Creating account…' : 'Create account'}
        </button>

        <p className="mt-5 text-center text-sm text-surface-400">
          Already have an account?{' '}
          <button
            type="button"
            onClick={onSwitchToLogin}
            className="text-accent-400 hover:text-accent-300 font-medium"
          >
            Sign in
          </button>
        </p>
      </form>
    </div>
  );
}
```

- [ ] **Uncomment** the two view exports in `packages/app/src/auth/index.ts` (re-enable the lines disabled in Q4-7).

- [ ] **Typecheck — expect PASS:**

```bash
cd packages/app && npx tsc --noEmit
```

- [ ] **Commit:**

```bash
git add packages/app/src/auth/LoginView.tsx packages/app/src/auth/RegisterView.tsx packages/app/src/auth/index.ts && git commit -m "feat(app/auth): LoginView + RegisterView with ProblemDetails error display

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task Q4-9: Implement `AppRoot.tsx` — gate loading/anonymous/authed; wire dataClient into the store

> **Store contract this task requires (surface if missing).** `AppRoot` must, on `authed`, inject the `dataClient` into the shared store and trigger the initial load; on `logout`, reset the store. The shared `store.ts` (built in an earlier SP2 phase) must expose **either**: (a) a `setDataClient(dataClient: DataClient)` action **and** a `reset()` action, **or** (b) accept the `dataClient` via a one-time `initStore({ dataClient })`. This phase assumes **option (a)** with these exact names: `useStore.getState().setDataClient(dataClient)` and `useStore.getState().reset()`. If the store from the earlier phase named them differently (e.g. `injectDataClient`), do **not** invent a second name here — open a one-line gap note and align on the store's existing names before writing `AppRoot`.

- [ ] **Confirm the store hooks exist** (grep before writing):

```bash
grep -nE "setDataClient|reset\b|initStore|injectDataClient" "packages/app/src/store.ts"
```

- [ ] **Write** `packages/app/src/AppRoot.tsx` (full code). It receives `{ dataClient, platform }`, builds the auth singleton via `initAuthStore`, calls `bootstrap()` once on mount, and renders: spinner when `loading`, `LoginView`/`RegisterView` (local toggle) when `anonymous`, and the full `<App />` when `authed`. `onAuthed` injects the `dataClient` into the store and kicks the initial load; `onLoggedOut` resets the store. The `tmapClient` and `refreshClient` are built by the host entry (`apps/*/src/main.tsx`) and passed in via `dataClient` already wired; `AppRoot` only needs the raw `tmapClient` for auth POSTs — so it also receives that.

```tsx
// packages/app/src/AppRoot.tsx
import React, { useEffect, useMemo, useRef, useState } from 'react';
import App from './App';
import { useStore } from './store';
import type { DataClient } from './data/DataClient';
import type { Platform } from './platform/Platform';
import type { TmapClient } from '@tmap/api-client';
import {
  initAuthStore,
  getAuthStore,
  useAuthStore,
  createRefreshClient,
  type AuthState,
} from './auth';

export interface AppRootProps {
  /** Data seam the store calls; built by the host entry over the (refresh-wrapped) TmapClient. */
  dataClient: DataClient;
  /** Host capabilities + token storage + events. */
  platform: Platform;
  /** Raw typed client for auth POSTs (register/login/logout) and 401-retry wrapping. */
  tmapClient: TmapClient;
}

type AnonScreen = 'login' | 'register';

export function AppRoot({ dataClient, platform, tmapClient }: AppRootProps) {
  const initialized = useRef(false);
  const [anonScreen, setAnonScreen] = useState<AnonScreen>('login');

  // Build the auth singleton + refresh wrapper exactly once.
  if (!initialized.current) {
    initialized.current = true;
    const refreshClient = createRefreshClient({
      client: tmapClient as any,
      refresh: () => platform.auth.refreshAndGetAccess(),
      onLogout: () => {
        // refresh path gave up: drive a full logout through the store.
        void getAuthStore().getState().logout();
      },
    });
    refreshClient.setAbortController(new AbortController());

    initAuthStore({
      client: tmapClient as any,
      platform,
      onAuthed: () => {
        useStore.getState().setDataClient(dataClient);
        // Initial load over HTTP (tasks/projects/noteGroups/settings, etc.).
        void useStore.getState().initialLoad?.();
      },
      onLoggedOut: () => {
        refreshClient.signOut();
        useStore.getState().reset();
      },
    });
  }

  const status = useAuthStore((s: AuthState) => s.status);

  useEffect(() => {
    void getAuthStore().getState().bootstrap();
  }, []);

  const content = useMemo(() => {
    if (status === 'loading') {
      return (
        <div className="h-screen flex flex-col items-center justify-center bg-surface-950 text-surface-400 select-none">
          <div className="fixed top-0 left-0 right-0 h-10 titlebar-drag-region z-50" />
          <div className="w-8 h-8 rounded-full border-2 border-surface-700 border-t-accent-500 animate-spin" />
          <p className="mt-4 text-sm">Loading…</p>
        </div>
      );
    }
    if (status === 'anonymous') {
      return anonScreen === 'login' ? (
        <LoginView onSwitchToRegister={() => setAnonScreen('register')} />
      ) : (
        <RegisterView onSwitchToLogin={() => setAnonScreen('login')} />
      );
    }
    return <App />;
  }, [status, anonScreen]);

  return content;
}

// Imported lazily-at-bottom to keep the gating block readable.
import { LoginView } from './auth/LoginView';
import { RegisterView } from './auth/RegisterView';
```

> **Note on `initialLoad`/`reset`/`setDataClient`:** these are store actions owned by an earlier SP2 phase (store refactor). `AppRoot` calls `initialLoad?.()` defensively (optional chaining) so it builds even if the action lands one task later; `setDataClient` and `reset` are required (no optional chaining) — if absent, the typecheck fails loudly, which is the intended gap signal.

- [ ] **Typecheck — expect PASS** (assuming `store.ts` exposes `setDataClient`/`reset`; if it fails on those names, that is the gap to resolve with the store phase, not to paper over):

```bash
cd packages/app && npx tsc --noEmit
```

- [ ] **Run the full unit suite — expect PASS (auth tests unaffected):**

```bash
cd packages/app && npm test
```

- [ ] **Commit:**

```bash
git add packages/app/src/AppRoot.tsx && git commit -m "feat(app): AppRoot — loading/anonymous/authed gate; wires dataClient + bootstrap

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task Q4-10: Manual verification — auth flow on desktop and web

> Requires the host entries (`apps/desktop/src/main.tsx`, `apps/web/src/main.tsx`) and the running SP1 API from their respective phases. This task does **not** create them; it verifies Q4's surface once they exist. If a host entry isn't ready yet, run the partial checks that are.

- [ ] **Start the API** (Q0 backend): `dotnet run --project backend/src/Tmap.Api` (or the project path the backend phase documents). Confirm `http://localhost:<port>/api/v1/auth/register` responds.
- [ ] **Web:** from `apps/web`, `npm run dev`; open the app. Confirm the **loading spinner** shows briefly, then the **LoginView** (anonymous). Click "Create one" → **RegisterView**. Register a new account → app loads (authed). Reload the page → spinner → app loads directly (stay-signed-in via the httpOnly refresh cookie, no re-login). Submit a **wrong** login (after logout) → red ProblemDetails banner ("Invalid email or password"); status stays anonymous.
- [ ] **Web network-vs-401:** stop the API, reload → the amber "Couldn't reach the server" banner appears on the login screen (not a destructive logout loop); restart API + reload → recovers.
- [ ] **Desktop:** from `apps/desktop`, `npm run dev`; same register/login/stay-signed-in checks. Confirm the refresh token is stored via the main-process `secureStore` (it never appears in the renderer; verify by inspecting the renderer's `window`/memory — no token there). Quit + relaunch → app comes back authed without re-login.
- [ ] **Logout:** trigger logout (Settings or wherever the logout action is wired by the settings/store phase); confirm it returns to **LoginView**, the store is reset (no stale tasks flash), and `/api/v1/auth/logout` was called (Network tab on web).
- [ ] **401 refresh-on-the-fly:** while authed on web, delete/expire the access token in memory (or wait past `expiresIn`), perform an action that hits the API → the request transparently refreshes once and succeeds (single retry), no logout. (Spot-check via the Network tab: one `/auth/refresh` then the retried call returns 200.)
- [ ] **No commit** (verification only). If any check fails, file the defect against the responsible phase (host entry / store refactor) rather than patching around it in Q4.

---

**Phase Q4 done when:** all unit tests in `packages/app/src/auth/__tests__/` pass (`npm test`), `npx tsc --noEmit` is clean in `packages/app`, and the manual flow (register/login/stay-signed-in/logout/401-refresh/network-banner) works on both desktop and web against the SP1 API.

---

## Phase Q5 — Platform abstraction, Desktop/Web adapters, main.ts unwire, reminders, settings split

This phase makes the shared `packages/app` host-agnostic and wires the two hosts to it through a single `Platform` seam. It **creates**: `packages/app/src/platform/Platform.ts`, `packages/app/src/reminders/reminderScheduler.ts`, `packages/app/src/reminders/reminderScheduler.test.ts`, `packages/app/src/components/OnlineErrorBanner.tsx`, `packages/app/src/lib/localPrefs.ts`, `packages/app/src/lib/localPrefs.test.ts`, `apps/desktop/src/DesktopPlatform.ts`, `apps/web/src/WebPlatform.ts`. It **modifies**: `apps/desktop/electron/main.ts` (unwire data/db/services/seed/reminder + add `secureStore` IPC), `apps/desktop/electron/preload.ts` (drop data channels, add `secureStore`, add focus reverse channels), `apps/desktop/electron/database.ts`-consumers removal only via main, `packages/app/src/components/SettingsDialog.tsx` (capability-gated, Data section removed, settings split), `packages/app/src/components/focus/FocusModeOverlay.tsx` (route through `platform.focusWidget`/`platform.on`), `packages/app/src/App.tsx` (`navigate` via `platform.on`), `packages/app/src/store.ts` (settings split read/write + reminder bootstrap hook), `packages/app/src/AppRoot.tsx` (mount reminder scheduler + OnlineErrorBanner + provide platform via context). It assumes Q1 created `packages/app` (with `@/` alias, `vitest.config.ts`), Q2 created `AppRoot.tsx`/`auth/authStore.ts`/`AuthTokenResponse`/`refreshClient.ts`, Q3 created `data/DataClient.ts`/`HttpDataClient.ts`/`mappers.ts`, Q4 created the typed auth client + `apps/web`/`apps/desktop` thin entries and `config.ts` (`API_BASE_URL`). Where this phase depends on a Q2/Q3/Q4 name, it only **imports** it; if a name is missing, surface the gap, do not redefine.

**Branch:** `feat/sp2-online-clients`. **Test command (from `packages/app`):** `npm test`. **Build gates:** `npm run build` in `apps/desktop` and `apps/web`.

---

### Task Q5-1: Define the `Platform` interface (spec §4 verbatim) and supporting types

- [ ] Write a failing test that imports the Platform types and asserts the contract shape compiles + a tiny stub satisfies it.

Create `packages/app/src/platform/Platform.test.ts`:

```ts
import { describe, it, expect } from 'vitest';
import type {
  Platform,
  AppChannel,
  FocusWidgetState,
  PlatformCapabilities,
} from './Platform';
import type { AuthTokenResponse } from '../auth/authStore';

describe('Platform contract', () => {
  it('a minimal stub satisfies the Platform interface (web-shaped: no focusWidget/autoLaunch)', () => {
    const caps: PlatformCapabilities = {
      tray: false,
      focusWidgetWindow: false,
      autoLaunch: false,
      dataPort: false,
    };
    const stub: Platform = {
      capabilities: caps,
      auth: {
        refreshAndGetAccess: async (): Promise<AuthTokenResponse | null> => null,
        clear: async () => {},
      },
      notify: () => {},
      on: () => {},
      off: () => {},
    };
    expect(stub.capabilities.tray).toBe(false);
    expect(stub.focusWidget).toBeUndefined();
    expect(stub.autoLaunch).toBeUndefined();
  });

  it('the AppChannel union includes the five known channels', () => {
    const channels: AppChannel[] = [
      'navigate',
      'focus:togglePlayPause',
      'focus:stop',
      'focus:done',
      'focus:resyncWidget',
    ];
    expect(channels).toHaveLength(5);
  });

  it('FocusWidgetState carries the timer fields the widget needs', () => {
    const s: FocusWidgetState = {
      taskTitle: 'Write spec',
      isPlaying: true,
      sessionStartTime: Date.now(),
      accumulatedMinutes: 12,
      plannedMinutes: 30,
      canComplete: true,
    };
    expect(s.taskTitle).toBe('Write spec');
  });
});
```

- [ ] Run `npm test` from `packages/app` → expect FAIL (module `./Platform` not found).

- [ ] Create `packages/app/src/platform/Platform.ts` with the full interface:

```ts
import type { AuthTokenResponse } from '../auth/authStore';

/**
 * Host capability flags. The shared app feature-gates UI off these
 * (hide auto-launch / OS tray / separate-widget-window / data export-import on web).
 */
export interface PlatformCapabilities {
  tray: boolean;
  focusWidgetWindow: boolean;
  autoLaunch: boolean;
  dataPort: boolean;
}

/** Event channels the shared app listens on; web no-ops, desktop maps to IPC. */
export type AppChannel =
  | 'navigate'
  | 'focus:togglePlayPause'
  | 'focus:stop'
  | 'focus:done'
  | 'focus:resyncWidget';

/** Snapshot pushed to the desktop always-on-top focus widget window. */
export interface FocusWidgetState {
  taskTitle: string;
  isPlaying: boolean;
  /** Wall-clock ms when the current running session began, or null when paused. */
  sessionStartTime: number | null;
  accumulatedMinutes: number;
  plannedMinutes: number;
  /** Whether the widget's "done" button is meaningful (tasks yes, projects no). */
  canComplete: boolean;
}

/** Actions the desktop focus widget can fire back at the app. */
export type FocusWidgetAction = 'togglePlayPause' | 'stop' | 'done';

/**
 * Host adapter seam. DesktopPlatform (apps/desktop) and WebPlatform (apps/web)
 * implement this; the shared app in packages/app depends only on this interface.
 */
export interface Platform {
  capabilities: PlatformCapabilities;

  /**
   * Token plumbing. Desktop keeps the refresh token in the main process
   * (safeStorage) and returns only the access token to the renderer; web
   * uses the httpOnly refresh cookie. `clear` revokes/forgets the stored token.
   */
  auth: {
    refreshAndGetAccess(): Promise<AuthTokenResponse | null>;
    clear(): Promise<void>;
  };

  /** OS notification on desktop; permission-gated Web Notifications on web. */
  notify(title: string, body: string): void;

  /** Subscribe to a host event (web no-ops). */
  on(channel: AppChannel, cb: (...args: any[]) => void): void;
  /** Unsubscribe (web no-ops). */
  off(channel: AppChannel, cb: (...args: any[]) => void): void;

  /** Desktop only — present iff capabilities.focusWidgetWindow. */
  focusWidget?: {
    pushState(state: FocusWidgetState): void;
    show(): void;
    hide(): void;
    onAction(cb: (action: FocusWidgetAction) => void): void;
    onResyncRequest(cb: () => void): void;
  };

  /** Desktop only — present iff capabilities.autoLaunch. */
  autoLaunch?: {
    get(): Promise<boolean>;
    set(on: boolean): Promise<void>;
  };
}
```

- [ ] Run `npm test` → expect PASS.
- [ ] `git add packages/app/src/platform/Platform.ts packages/app/src/platform/Platform.test.ts && git commit -m "feat(platform): add Platform interface (spec §4) + types

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`

---

### Task Q5-2: `localPrefs.ts` — typed localStorage for local-only UI prefs

Local-only keys (spec §6): `sidebarCollapsed`, `notesCollapsed`, `projectsCollapsed`. Synced keys (`workStartHour`, `workEndHour`, `timeIncrement`, `timeZoneId`) go through `dataClient.settings`, NOT here.

- [ ] Write failing test `packages/app/src/lib/localPrefs.test.ts`:

```ts
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { loadLocalPrefs, saveLocalPref, LOCAL_PREF_KEYS, type LocalPrefs } from './localPrefs';

function memoryStorage(): Storage {
  let m: Record<string, string> = {};
  return {
    get length() {
      return Object.keys(m).length;
    },
    clear: () => {
      m = {};
    },
    getItem: (k: string) => (k in m ? m[k] : null),
    key: (i: number) => Object.keys(m)[i] ?? null,
    removeItem: (k: string) => {
      delete m[k];
    },
    setItem: (k: string, v: string) => {
      m[k] = v;
    },
  };
}

describe('localPrefs', () => {
  let store: Storage;
  beforeEach(() => {
    store = memoryStorage();
  });

  it('returns defaults (all false) when nothing is stored', () => {
    expect(loadLocalPrefs(store)).toEqual({
      sidebarCollapsed: false,
      notesCollapsed: false,
      projectsCollapsed: false,
    });
  });

  it('round-trips a single pref via saveLocalPref', () => {
    saveLocalPref('sidebarCollapsed', true, store);
    expect(loadLocalPrefs(store)).toEqual({
      sidebarCollapsed: true,
      notesCollapsed: false,
      projectsCollapsed: false,
    });
  });

  it('ignores malformed JSON and falls back to default for that key', () => {
    store.setItem('tmap.pref.notesCollapsed', '{not json');
    const prefs = loadLocalPrefs(store);
    expect(prefs.notesCollapsed).toBe(false);
  });

  it('exposes exactly the three local-only keys', () => {
    expect([...LOCAL_PREF_KEYS].sort()).toEqual(
      ['notesCollapsed', 'projectsCollapsed', 'sidebarCollapsed'].sort(),
    );
  });

  it('is a no-op (no throw) when storage access throws', () => {
    const throwing: Storage = {
      ...memoryStorage(),
      getItem: () => {
        throw new Error('blocked');
      },
      setItem: () => {
        throw new Error('blocked');
      },
    };
    expect(() => loadLocalPrefs(throwing)).not.toThrow();
    expect(() => saveLocalPref('sidebarCollapsed', true, throwing)).not.toThrow();
    const _vi = vi; // keep import used
  });
});
```

- [ ] Run `npm test` → expect FAIL (no `./localPrefs`).

- [ ] Create `packages/app/src/lib/localPrefs.ts`:

```ts
/** Local-only UI prefs (not synced to the server). Spec §6. */
export interface LocalPrefs {
  sidebarCollapsed: boolean;
  notesCollapsed: boolean;
  projectsCollapsed: boolean;
}

export const LOCAL_PREF_KEYS = [
  'sidebarCollapsed',
  'notesCollapsed',
  'projectsCollapsed',
] as const;

export type LocalPrefKey = (typeof LOCAL_PREF_KEYS)[number];

const PREFIX = 'tmap.pref.';

const DEFAULTS: LocalPrefs = {
  sidebarCollapsed: false,
  notesCollapsed: false,
  projectsCollapsed: false,
};

function resolveStorage(explicit?: Storage): Storage | null {
  if (explicit) return explicit;
  try {
    return typeof localStorage !== 'undefined' ? localStorage : null;
  } catch {
    return null;
  }
}

/** Read all local-only prefs; any missing/malformed key falls back to its default. */
export function loadLocalPrefs(storage?: Storage): LocalPrefs {
  const s = resolveStorage(storage);
  if (!s) return { ...DEFAULTS };
  const out: LocalPrefs = { ...DEFAULTS };
  for (const key of LOCAL_PREF_KEYS) {
    try {
      const raw = s.getItem(PREFIX + key);
      if (raw == null) continue;
      const parsed = JSON.parse(raw);
      if (typeof parsed === 'boolean') out[key] = parsed;
    } catch {
      // malformed or storage error → keep default
    }
  }
  return out;
}

/** Persist one local-only pref. Never throws (storage may be unavailable). */
export function saveLocalPref(key: LocalPrefKey, value: boolean, storage?: Storage): void {
  const s = resolveStorage(storage);
  if (!s) return;
  try {
    s.setItem(PREFIX + key, JSON.stringify(value));
  } catch {
    // storage full / blocked → ignore
  }
}
```

- [ ] Run `npm test` → expect PASS.
- [ ] `git add packages/app/src/lib/localPrefs.ts packages/app/src/lib/localPrefs.test.ts && git commit -m "feat(settings): add localPrefs for local-only UI prefs (settings split)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`

---

### Task Q5-3: `reminderScheduler.ts` — client-side due-selection logic (pure, unit-tested)

Ports the removed main-process `setInterval` (main.ts ~134–181). Pure selection function `selectDueReminders` decides which tasks should fire **now**, given the already-notified set; the scheduler shell wires it to a timer + `platform.notify`.

- [ ] Write failing test `packages/app/src/reminders/reminderScheduler.test.ts`:

```ts
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import {
  selectDueReminders,
  pruneNotified,
  startReminderScheduler,
  type ReminderTask,
} from './reminderScheduler';

const MIN = 60_000;

function task(p: Partial<ReminderTask>): ReminderTask {
  return {
    id: 'id',
    title: 'T',
    status: 'scheduled',
    scheduledStart: null,
    reminderMinutes: 0,
    ...p,
  };
}

describe('selectDueReminders', () => {
  const now = Date.parse('2026-06-08T09:00:00Z');

  it('fires when notifyAt (start - reminderMinutes) has arrived and start is not >5m past', () => {
    const t = task({
      id: 'a',
      scheduledStart: new Date(now + 10 * MIN).toISOString(),
      reminderMinutes: 10,
    });
    expect(selectDueReminders([t], now, new Set()).map((x) => x.id)).toEqual(['a']);
  });

  it('does not fire before notifyAt', () => {
    const t = task({
      id: 'a',
      scheduledStart: new Date(now + 20 * MIN).toISOString(),
      reminderMinutes: 10,
    });
    expect(selectDueReminders([t], now, new Set())).toEqual([]);
  });

  it('does not fire once already notified', () => {
    const t = task({
      id: 'a',
      scheduledStart: new Date(now + 1 * MIN).toISOString(),
      reminderMinutes: 10,
    });
    expect(selectDueReminders([t], now, new Set(['a']))).toEqual([]);
  });

  it('does not fire for tasks whose start is more than 5 minutes in the past', () => {
    const t = task({
      id: 'a',
      scheduledStart: new Date(now - 6 * MIN).toISOString(),
      reminderMinutes: 0,
    });
    expect(selectDueReminders([t], now, new Set())).toEqual([]);
  });

  it('fires at exactly start time when reminderMinutes is 0 ("Starting now")', () => {
    const t = task({ id: 'a', scheduledStart: new Date(now).toISOString(), reminderMinutes: 0 });
    expect(selectDueReminders([t], now, new Set()).map((x) => x.id)).toEqual(['a']);
  });

  it('skips tasks without a scheduledStart', () => {
    const t = task({ id: 'a', scheduledStart: null, reminderMinutes: 10 });
    expect(selectDueReminders([t], now, new Set())).toEqual([]);
  });

  it('skips tasks with null reminderMinutes (reminders disabled)', () => {
    const t = task({
      id: 'a',
      scheduledStart: new Date(now).toISOString(),
      reminderMinutes: null,
    });
    expect(selectDueReminders([t], now, new Set())).toEqual([]);
  });

  it('skips done/archived tasks', () => {
    const base = {
      id: 'a',
      scheduledStart: new Date(now).toISOString(),
      reminderMinutes: 0,
    };
    expect(selectDueReminders([task({ ...base, status: 'done' })], now, new Set())).toEqual([]);
    expect(selectDueReminders([task({ ...base, status: 'archived' })], now, new Set())).toEqual([]);
  });
});

describe('pruneNotified', () => {
  const now = Date.parse('2026-06-08T09:00:00Z');
  it('drops ids whose task started more than 1 hour ago', () => {
    const tasks = [task({ id: 'a', scheduledStart: new Date(now - 70 * MIN).toISOString() })];
    const next = pruneNotified(new Set(['a', 'ghost']), tasks, now);
    expect(next.has('a')).toBe(false);
    // ids no longer present in the task list are kept (can't prove they're old) — or dropped; assert 'a' specifically
  });

  it('keeps ids whose task started within the last hour', () => {
    const tasks = [task({ id: 'a', scheduledStart: new Date(now - 10 * MIN).toISOString() })];
    const next = pruneNotified(new Set(['a']), tasks, now);
    expect(next.has('a')).toBe(true);
  });
});

describe('startReminderScheduler', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('polls on the interval and calls notify for each newly-due task exactly once', () => {
    const now = Date.parse('2026-06-08T09:00:00Z');
    vi.setSystemTime(now);
    const notify = vi.fn();
    const due = task({
      id: 'a',
      title: 'Standup',
      scheduledStart: new Date(now).toISOString(),
      reminderMinutes: 0,
    });
    const stop = startReminderScheduler({
      getTasks: () => [due],
      notify,
      intervalMs: 30_000,
    });
    vi.advanceTimersByTime(30_000);
    expect(notify).toHaveBeenCalledTimes(1);
    expect(notify).toHaveBeenCalledWith('Standup', 'Starting now');
    // second tick must not re-notify the same task
    vi.advanceTimersByTime(30_000);
    expect(notify).toHaveBeenCalledTimes(1);
    stop();
  });

  it('uses "Starting in N minutes" copy when reminderMinutes > 0', () => {
    const now = Date.parse('2026-06-08T09:00:00Z');
    vi.setSystemTime(now);
    const notify = vi.fn();
    const due = task({
      id: 'a',
      title: 'Review',
      scheduledStart: new Date(now + 5 * MIN).toISOString(),
      reminderMinutes: 5,
    });
    const stop = startReminderScheduler({ getTasks: () => [due], notify, intervalMs: 30_000 });
    vi.advanceTimersByTime(30_000);
    expect(notify).toHaveBeenCalledWith('Review', 'Starting in 5 minutes');
    stop();
  });

  it('stop() clears the interval (no further notifies)', () => {
    const now = Date.parse('2026-06-08T09:00:00Z');
    vi.setSystemTime(now);
    const notify = vi.fn();
    const stop = startReminderScheduler({
      getTasks: () => [
        task({ id: 'a', scheduledStart: new Date(now).toISOString(), reminderMinutes: 0 }),
      ],
      notify,
      intervalMs: 30_000,
    });
    stop();
    vi.advanceTimersByTime(120_000);
    expect(notify).not.toHaveBeenCalled();
  });
});
```

- [ ] Run `npm test` → expect FAIL (no `./reminderScheduler`).

- [ ] Create `packages/app/src/reminders/reminderScheduler.ts`:

```ts
/** Minimal shape the scheduler needs from a Task (decoupled from the full domain type). */
export interface ReminderTask {
  id: string;
  title: string;
  status: string;
  scheduledStart: string | null;
  reminderMinutes: number | null;
}

const FIVE_MIN_MS = 5 * 60_000;
const ONE_HOUR_MS = 60 * 60_000;

/**
 * Decide which tasks should fire a reminder right now.
 * Mirrors the removed main-process scheduler: fire when
 * (start - reminderMinutes) <= now AND start > now - 5m, skipping already-notified
 * and non-active tasks.
 */
export function selectDueReminders(
  tasks: ReminderTask[],
  now: number,
  notified: ReadonlySet<string>,
): ReminderTask[] {
  const due: ReminderTask[] = [];
  for (const t of tasks) {
    if (notified.has(t.id)) continue;
    if (t.status === 'done' || t.status === 'archived') continue;
    if (!t.scheduledStart) continue;
    if (t.reminderMinutes == null) continue;

    const startTime = new Date(t.scheduledStart).getTime();
    if (Number.isNaN(startTime)) continue;

    const notifyAt = startTime - t.reminderMinutes * 60_000;
    if (notifyAt <= now && startTime > now - FIVE_MIN_MS) {
      due.push(t);
    }
  }
  return due;
}

/** Remove notified ids whose task started more than an hour ago (matches old cleanup). */
export function pruneNotified(
  notified: ReadonlySet<string>,
  tasks: ReminderTask[],
  now: number,
): Set<string> {
  const next = new Set(notified);
  for (const id of notified) {
    const t = tasks.find((x) => x.id === id);
    if (t?.scheduledStart) {
      const startTime = new Date(t.scheduledStart).getTime();
      if (!Number.isNaN(startTime) && startTime < now - ONE_HOUR_MS) {
        next.delete(id);
      }
    }
  }
  return next;
}

export function reminderBody(reminderMinutes: number): string {
  return reminderMinutes === 0 ? 'Starting now' : `Starting in ${reminderMinutes} minutes`;
}

export interface ReminderSchedulerOptions {
  /** Snapshot of the current in-store tasks (called every tick). */
  getTasks: () => ReminderTask[];
  /** Host notification sink (platform.notify). */
  notify: (title: string, body: string) => void;
  /** Poll interval; defaults to 30s to match the old main scheduler. */
  intervalMs?: number;
}

/**
 * Client-side reminder timer. Replaces the deleted main-process setInterval.
 * Host-agnostic: callers pass platform.notify. Returns a stop() disposer.
 */
export function startReminderScheduler(opts: ReminderSchedulerOptions): () => void {
  const intervalMs = opts.intervalMs ?? 30_000;
  let notified = new Set<string>();

  const handle = setInterval(() => {
    try {
      const now = Date.now();
      const tasks = opts.getTasks();
      const due = selectDueReminders(tasks, now, notified);
      for (const t of due) {
        opts.notify(t.title, reminderBody(t.reminderMinutes ?? 0));
        notified.add(t.id);
      }
      notified = pruneNotified(notified, tasks, now);
    } catch (e) {
      // never let a tick crash the timer
      console.error('Reminder scheduler tick failed:', e);
    }
  }, intervalMs);

  return () => clearInterval(handle);
}
```

- [ ] Run `npm test` → expect PASS.
- [ ] `git add packages/app/src/reminders/reminderScheduler.ts packages/app/src/reminders/reminderScheduler.test.ts && git commit -m "feat(reminders): client-side reminder scheduler over the in-store task list

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`

---

### Task Q5-4: Desktop preload — drop data channels, add `secureStore` + focus reverse channels

Rewrites `apps/desktop/electron/preload.ts` so the renderer no longer has any `tasks/subtasks/projects/noteGroups/notes/recurrence/focusSessions/dailyPlans/reports/settings/data` data IPC; the data path is HTTP now. Keep desktop-only surface and **add** `secureStore` (refresh-token plumbing in main) and focus reverse-channel helpers (`onAction`/`onResyncRequest` are consumed by `DesktopPlatform`).

- [ ] No unit test (preload runs only in Electron). Verification is the desktop build + manual run gates at the end.

- [ ] Replace the entire contents of `apps/desktop/electron/preload.ts`:

```ts
import { contextBridge, ipcRenderer } from 'electron';

/**
 * Desktop-only bridge. SP2 moves ALL data CRUD to HTTP (@tmap/api-client),
 * so this preload exposes ONLY host capabilities: secure refresh-token store,
 * notifications, auto-launch, tray/navigation events, the focus widget, and
 * app metadata. No tasks/projects/notes/settings data channels remain.
 */
const api = {
  app: {
    getVersion: (): Promise<string> => ipcRenderer.invoke('app:getVersion'),
    showNotification: (title: string, body: string): void =>
      ipcRenderer.send('app:showNotification', title, body),
    getAutoLaunch: (): Promise<boolean> => ipcRenderer.invoke('app:getAutoLaunch'),
    setAutoLaunch: (enabled: boolean): Promise<boolean> =>
      ipcRenderer.invoke('app:setAutoLaunch', enabled),
  },

  /**
   * Refresh token lives ONLY in the main process (safeStorage). The renderer can
   * ask main to perform /auth/refresh and receive only the access token back; it
   * can store a freshly-issued refresh token (after login/register) and clear it.
   */
  secureStore: {
    setRefreshToken: (token: string): Promise<void> =>
      ipcRenderer.invoke('secureStore:setRefreshToken', token),
    clear: (): Promise<void> => ipcRenderer.invoke('secureStore:clear'),
    /** Performs POST /auth/refresh in main using the stored refresh token. */
    refreshAndGetAccess: (): Promise<{
      accessToken: string;
      expiresIn: number;
      user: { id: string; email: string; timeZoneId: string };
    } | null> => ipcRenderer.invoke('secureStore:refreshAndGetAccess'),
  },

  focus: {
    // forward state to the always-on-top widget window
    showWidget: (): void => ipcRenderer.send('focus:showWidget'),
    hideWidget: (): void => ipcRenderer.send('focus:hideWidget'),
    sendWidgetState: (data: unknown): void => ipcRenderer.send('focus:widgetState', data),
    updateTray: (data: {
      taskTitle: string | null;
      elapsed: string | null;
      isPlaying: boolean;
    }): void => ipcRenderer.send('focus:updateTray', data),
  },

  on: (channel: string, callback: (...args: any[]) => void): void => {
    ipcRenderer.on(channel, (_e, ...args) => callback(...args));
  },
  off: (channel: string, callback: (...args: any[]) => void): void => {
    ipcRenderer.removeListener(channel, callback);
  },
  removeAllListeners: (channel: string): void => {
    ipcRenderer.removeAllListeners(channel);
  },
};

contextBridge.exposeInMainWorld('api', api);

export type ElectronAPI = typeof api;
```

> Note: `app:showNotification` switches from `invoke` to `send` (fire-and-forget) — Q5-5 registers it as `ipcMain.on`. The `focus:updateTray` channel is preserved (tray tooltip). `getElapsed`/elapsed formatting stays in the renderer (FocusModeOverlay) as today.

- [ ] `git add apps/desktop/electron/preload.ts && git commit -m "refactor(desktop): preload drops data IPC, adds secureStore + focus host bridge

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`

---

### Task Q5-5: Desktop main — unwire DB/services/seed/reminder/data-IPC; add `secureStore`; keep desktop IPC

Rewrites `apps/desktop/electron/main.ts`. Removes: `initDatabase`/`getDatabase`/`saveDatabase` imports + the 6 service imports + `seedDemoData` import, the `whenReady` DB init + service construction + seed, the 30s reminder `setInterval`, and **all** data `ipcMain.handle` registrations (tasks/subtasks/projects/noteGroups/notes/recurrence/focusSessions/dailyPlans/reports/settings/data export+import). Keeps: window, tray, `navigate`, global shortcut, focus widget (window + `focus:*` channels), auto-launch, version, notification. Adds the `secureStore` channel that holds the refresh token in `safeStorage` and performs `/auth/refresh` in main.

- [ ] No unit test (Electron main). Gate = desktop build + manual run.

- [ ] Replace the entire contents of `apps/desktop/electron/main.ts`:

```ts
import {
  app,
  BrowserWindow,
  ipcMain,
  Tray,
  Menu,
  nativeImage,
  Notification,
  screen,
  globalShortcut,
  safeStorage,
} from 'electron';
import path from 'path';
import fs from 'fs';
import { API_BASE_URL } from './config';

let mainWindow: BrowserWindow | null = null;
let focusWidget: BrowserWindow | null = null;
let tray: Tray | null = null;

// ─── Secure refresh-token store (main-process only) ────────────
function refreshTokenPath(): string {
  return path.join(app.getPath('userData'), 'refresh.bin');
}

function persistRefreshToken(token: string): void {
  try {
    const buf = safeStorage.isEncryptionAvailable()
      ? safeStorage.encryptString(token)
      : Buffer.from(token, 'utf-8');
    fs.writeFileSync(refreshTokenPath(), buf);
  } catch (e) {
    console.error('Failed to persist refresh token:', e);
  }
}

function readRefreshToken(): string | null {
  try {
    const p = refreshTokenPath();
    if (!fs.existsSync(p)) return null;
    const buf = fs.readFileSync(p);
    return safeStorage.isEncryptionAvailable()
      ? safeStorage.decryptString(buf)
      : buf.toString('utf-8');
  } catch (e) {
    console.error('Failed to read refresh token:', e);
    return null;
  }
}

function clearRefreshToken(): void {
  try {
    const p = refreshTokenPath();
    if (fs.existsSync(p)) fs.unlinkSync(p);
  } catch (e) {
    console.error('Failed to clear refresh token:', e);
  }
}

function createFocusWidget() {
  if (focusWidget && !focusWidget.isDestroyed()) {
    focusWidget.show();
    return;
  }

  const { width } = screen.getPrimaryDisplay().workAreaSize;

  focusWidget = new BrowserWindow({
    width: 420,
    height: 52,
    x: Math.round(width / 2 - 210),
    y: 8,
    frame: false,
    transparent: true,
    resizable: false,
    skipTaskbar: true,
    alwaysOnTop: true,
    focusable: true,
    hasShadow: false,
    webPreferences: {
      nodeIntegration: true,
      contextIsolation: false,
    },
  });

  if (process.env.VITE_DEV_SERVER_URL) {
    focusWidget.loadFile(path.join(__dirname, '../public/focus-widget.html'));
  } else {
    focusWidget.loadFile(path.join(__dirname, '../dist/focus-widget.html'));
  }

  focusWidget.setAlwaysOnTop(true, 'screen-saver');

  focusWidget.on('closed', () => {
    focusWidget = null;
  });
}

const gotTheLock = app.requestSingleInstanceLock();

if (!gotTheLock) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (mainWindow) {
      if (mainWindow.isMinimized()) mainWindow.restore();
      mainWindow.focus();
    }
  });

  app.setAppUserModelId('com.tmap.app');

  app.whenReady().then(() => {
    createWindow();
    createTray();
    registerIpcHandlers();

    // Global shortcut: Ctrl+Shift+Q to toggle the focus timer widget
    globalShortcut.register('Ctrl+Shift+Q', () => {
      if (focusWidget && !focusWidget.isDestroyed()) {
        focusWidget.close();
        focusWidget = null;
      } else {
        createFocusWidget();
        // Ask the renderer to resync the new widget with current focus state
        mainWindow?.webContents.send('focus:resyncWidget');
      }
    });
  });

  app.on('will-quit', () => {
    globalShortcut.unregisterAll();
  });

  app.on('window-all-closed', () => {
    // On Windows, don't quit — minimize to tray
  });

  app.on('activate', () => {
    if (mainWindow === null) createWindow();
  });
}

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1400,
    height: 900,
    minWidth: 1000,
    minHeight: 700,
    frame: false,
    titleBarStyle: 'hidden',
    titleBarOverlay: {
      color: '#020617',
      symbolColor: '#94a3b8',
      height: 40,
    },
    backgroundColor: '#020617',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
    },
    show: false,
  });

  mainWindow.once('ready-to-show', () => {
    mainWindow?.show();
  });

  mainWindow.on('close', (e) => {
    e.preventDefault();
    mainWindow?.hide();
  });

  if (process.env.VITE_DEV_SERVER_URL) {
    mainWindow.loadURL(process.env.VITE_DEV_SERVER_URL);
  } else {
    mainWindow.loadFile(path.join(__dirname, '../dist/index.html'));
  }
}

function createTray() {
  const icon = nativeImage.createFromDataURL(
    'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAYAAABzenr0AAAAAXNSR0IArs4c6QAAAARnQU1BAACxjwv8YQUAAAAJcEhZcwAADsMAAA7DAcdvqGQAAAEeSURBVFhH7ZZBDoMwDATz/0+nV3pBQkns9dqCHpBWGu+OHYcQ8vX19Z8cHpBcHpBcHpBcHpBcPwcUJxck1c8BNckF0QswkFwQvQAD6UVxAoakF8UJGJJeFCdgSC+KEzAkvShOwJD0ojgBQ9KL4gQMSS+KEzAkvShOwJD0ojgBQ9KL4gQMSS+KEzAkvShOwJD0ojgBQ9KL4v8Aezhe4GIXuxjf24X8m+2dvmcsbufiU3+h28Vu5+JTP6PLxW7n4lO/oMvFbufidT7RzMXrfKKZi9f5RDMX+0QzF/tEMxf7RDMX+0QzF/tEMxf7RDMX+0QzF/tEMxf7RLPy/gBgIL0ouQBD0ouSBzCQXpQ8gIH04v8DQvgGdIlrOH7OONYAAAAASUVORK5CYII=',
  );
  tray = new Tray(icon);

  const contextMenu = Menu.buildFromTemplate([
    {
      label: 'Open TMap',
      click: () => {
        mainWindow?.show();
        mainWindow?.focus();
      },
    },
    {
      label: 'Plan Today',
      click: () => {
        mainWindow?.show();
        mainWindow?.focus();
        mainWindow?.webContents.send('navigate', 'plan-today');
      },
    },
    { type: 'separator' },
    {
      label: 'Quit',
      click: () => {
        mainWindow?.destroy();
        app.quit();
      },
    },
  ]);

  tray.setToolTip('TMap');
  tray.setContextMenu(contextMenu);

  tray.on('double-click', () => {
    mainWindow?.show();
    mainWindow?.focus();
  });
}

function registerIpcHandlers() {
  // ─── App metadata / notifications ──────────────────────────
  ipcMain.handle('app:getVersion', () => app.getVersion());

  ipcMain.on('app:showNotification', (_e, title: string, body: string) => {
    const n = new Notification({
      title,
      body,
      icon: path.join(__dirname, '../build/icon.png'),
    });
    n.on('click', () => {
      mainWindow?.show();
      mainWindow?.focus();
    });
    n.show();
  });

  // ─── Auto-Launch ───────────────────────────────────────────
  ipcMain.handle('app:getAutoLaunch', () => app.getLoginItemSettings().openAtLogin);

  ipcMain.handle('app:setAutoLaunch', (_e, enabled: boolean) => {
    app.setLoginItemSettings({ openAtLogin: enabled });
    return true;
  });

  // ─── Secure refresh-token store + refresh ──────────────────
  ipcMain.handle('secureStore:setRefreshToken', (_e, token: string) => {
    persistRefreshToken(token);
  });

  ipcMain.handle('secureStore:clear', () => {
    clearRefreshToken();
  });

  ipcMain.handle('secureStore:refreshAndGetAccess', async () => {
    const refreshToken = readRefreshToken();
    if (!refreshToken) return null;
    try {
      const res = await fetch(`${API_BASE_URL}/api/v1/auth/refresh`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        // Native path: refresh token in the body (SP1 §3.4).
        body: JSON.stringify({ refreshToken }),
      });
      if (res.status === 401) {
        // Refresh token rejected/rotated-away → forget it; renderer logs out.
        clearRefreshToken();
        return null;
      }
      if (!res.ok) {
        // Network/5xx — do NOT destroy a possibly-valid token; signal "couldn't refresh".
        return null;
      }
      const data = (await res.json()) as {
        accessToken: string;
        expiresIn: number;
        refreshToken?: string;
        user: { id: string; email: string; timeZoneId: string };
      };
      // Rotation: store the new refresh token if one was returned.
      if (data.refreshToken) persistRefreshToken(data.refreshToken);
      return {
        accessToken: data.accessToken,
        expiresIn: data.expiresIn,
        user: data.user,
      };
    } catch (e) {
      console.error('refreshAndGetAccess failed:', e);
      return null;
    }
  });

  // ─── Focus Timer Tray Widget ───────────────────────────────
  ipcMain.on(
    'focus:updateTray',
    (_e, data: { taskTitle: string | null; elapsed: string | null; isPlaying: boolean }) => {
      if (!tray) return;

      if (data.taskTitle && data.elapsed) {
        const state = data.isPlaying ? '▶' : '⏸';
        tray.setToolTip(`${state} ${data.taskTitle}\n⏱ ${data.elapsed}`);
        const contextMenu = Menu.buildFromTemplate([
          { label: `${state} ${data.taskTitle}`, enabled: false },
          { label: `⏱ ${data.elapsed}`, enabled: false },
          { type: 'separator' },
          {
            label: data.isPlaying ? '⏸ Pause' : '▶ Resume',
            click: () => mainWindow?.webContents.send('focus:togglePlayPause'),
          },
          {
            label: '⏹ Stop Timer',
            click: () => mainWindow?.webContents.send('focus:stop'),
          },
          { type: 'separator' },
          {
            label: 'Open TMap',
            click: () => {
              mainWindow?.show();
              mainWindow?.focus();
            },
          },
          {
            label: 'Quit',
            click: () => {
              mainWindow?.destroy();
              app.quit();
            },
          },
        ]);
        tray.setContextMenu(contextMenu);
      } else {
        tray.setToolTip('TMap');
        const contextMenu = Menu.buildFromTemplate([
          {
            label: 'Open TMap',
            click: () => {
              mainWindow?.show();
              mainWindow?.focus();
            },
          },
          { type: 'separator' },
          {
            label: 'Quit',
            click: () => {
              mainWindow?.destroy();
              app.quit();
            },
          },
        ]);
        tray.setContextMenu(contextMenu);
      }
    },
  );

  // ─── Focus Widget Window (Always-on-Top) ───────────────────
  ipcMain.on('focus:showWidget', () => {
    createFocusWidget();
  });

  ipcMain.on('focus:hideWidget', () => {
    if (focusWidget && !focusWidget.isDestroyed()) {
      focusWidget.close();
      focusWidget = null;
    }
  });

  ipcMain.on('focus:widgetState', (_e, data: unknown) => {
    if (focusWidget && !focusWidget.isDestroyed()) {
      focusWidget.webContents.send('focus:state', data);
    }
  });

  // Widget button actions → forward to main renderer
  ipcMain.on('focus:widgetAction', (_e, action: string) => {
    if (!mainWindow) return;
    switch (action) {
      case 'togglePlayPause':
        mainWindow.webContents.send('focus:togglePlayPause');
        break;
      case 'stop':
        mainWindow.webContents.send('focus:stop');
        break;
      case 'done':
        mainWindow.webContents.send('focus:done');
        break;
    }
  });
}
```

> The SQL.js source files (`database.ts`, `taskService.ts`, etc.) **stay in the repo** (dormant, SP3 revives them) but are no longer imported by `main.ts`. Q5-6 removes the now-dead `sql.js`/`uuid` externals from the desktop Vite config so the prod build doesn't bundle them.

- [ ] Create `apps/desktop/electron/config.ts` (build-time API base for the main-process refresh call; mirrors the renderer `config.ts` from Q4):

```ts
/**
 * API base URL for main-process calls (the secureStore refresh path).
 * Overridable via TMAP_API_BASE_URL env at launch so a packaged build can be
 * pointed at another server without rebuilding. Defaults to local dev.
 */
export const API_BASE_URL =
  process.env.TMAP_API_BASE_URL?.replace(/\/$/, '') ?? 'http://localhost:5050';
```

> If Q4 already created `apps/desktop/electron/config.ts`, IMPORT it instead of creating a second one — surface the duplication rather than overwrite. The concrete default port (`5050`) must match Q0/Q4's API launch port; if Q4 used a different constant name (e.g. `DESKTOP_API_BASE_URL`), use that name and note the gap.

- [ ] `git add apps/desktop/electron/main.ts apps/desktop/electron/config.ts && git commit -m "refactor(desktop): unwire DB/services/seed/reminder/data-IPC; add secureStore refresh

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`

---

### Task Q5-6: Desktop Vite config — drop `sql.js`/`uuid` externals; verify `base: './'`

- [ ] Read `apps/desktop/vite.config.ts` and confirm the electron-main rollup `external` array lists `sql.js`/`uuid` (and any `better-sqlite3`); remove those entries (main no longer imports them). Ensure the renderer `base` is `'./'` for `file://` prod loading (spec §1). Leave electron-builder/`vite-plugin-electron` wiring intact.

- [ ] Apply the edit. Example shape (adapt to the actual file — only remove the dead externals and set `base`):

```ts
// apps/desktop/vite.config.ts (excerpt — remove sql.js/uuid from external)
export default defineConfig({
  base: './',
  // ...plugins: react(), electron({ main: { entry: 'electron/main.ts',
  //   vite: { build: { rollupOptions: { external: ['electron'] /* sql.js, uuid removed */ } } } },
  //   preload: { input: 'electron/preload.ts' } }),
});
```

- [ ] Run `npm run build` in `apps/desktop` → expect it to compile (it may still fail on renderer imports of removed channels until Q5-7..Q5-10 land; that is expected — note it and proceed).

- [ ] `git add apps/desktop/vite.config.ts && git commit -m "build(desktop): drop sql.js/uuid externals; confirm base './' for file:// prod

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`

---

### Task Q5-7: `DesktopPlatform` (apps/desktop/src) — implement `Platform` over the new preload

Lives in the app shell, NOT in `packages/app`. Maps `capabilities` to all-true, `auth.refreshAndGetAccess` to the `secureStore` channel, `notify` to the Electron notification, `on/off` to `window.api.on/off` for `navigate` + `focus:*`, `focusWidget` to the focus IPC (`show`/`hide`/`sendWidgetState` + reverse `focus:togglePlayPause`/`stop`/`done`/`resyncWidget`), and `autoLaunch` to the app channel. It also exposes a way to persist the refresh token after login/register (used by Q2's authStore via the platform — surfaced as `auth.setRefreshToken`; if Q2's `Platform` consumer expects only `refreshAndGetAccess`/`clear`, add `setRefreshToken` to the desktop `auth` object as an extra and have the desktop entry pass it to authStore — note the seam).

- [ ] No unit test (depends on the Electron `window.api`). Gate = desktop build + manual run.

- [ ] Create `apps/desktop/src/DesktopPlatform.ts`:

```ts
import type {
  Platform,
  AppChannel,
  FocusWidgetState,
  FocusWidgetAction,
} from '@/platform/Platform';
import type { AuthTokenResponse } from '@/auth/authStore';

/**
 * Desktop host adapter. Talks to the Electron preload (`window.api`). All data
 * goes over HTTP elsewhere; this only covers host capabilities.
 */
export class DesktopPlatform implements Platform {
  readonly capabilities = {
    tray: true,
    focusWidgetWindow: true,
    autoLaunch: true,
    dataPort: false,
  };

  readonly auth = {
    refreshAndGetAccess: async (): Promise<AuthTokenResponse | null> => {
      const res = await window.api.secureStore.refreshAndGetAccess();
      return res;
    },
    clear: async (): Promise<void> => {
      await window.api.secureStore.clear();
    },
    /** Desktop extra: persist a freshly-issued refresh token in main (safeStorage). */
    setRefreshToken: async (token: string): Promise<void> => {
      await window.api.secureStore.setRefreshToken(token);
    },
  };

  notify(title: string, body: string): void {
    window.api.app.showNotification(title, body);
  }

  on(channel: AppChannel, cb: (...args: any[]) => void): void {
    window.api.on(channel, cb);
  }

  off(channel: AppChannel, cb: (...args: any[]) => void): void {
    window.api.off(channel, cb);
  }

  readonly focusWidget = {
    pushState: (state: FocusWidgetState): void => {
      window.api.focus.sendWidgetState(state);
    },
    show: (): void => {
      window.api.focus.showWidget();
    },
    hide: (): void => {
      window.api.focus.hideWidget();
    },
    onAction: (cb: (action: FocusWidgetAction) => void): void => {
      window.api.on('focus:togglePlayPause', () => cb('togglePlayPause'));
      window.api.on('focus:stop', () => cb('stop'));
      window.api.on('focus:done', () => cb('done'));
    },
    onResyncRequest: (cb: () => void): void => {
      window.api.on('focus:resyncWidget', cb);
    },
  };

  readonly autoLaunch = {
    get: (): Promise<boolean> => window.api.app.getAutoLaunch(),
    set: async (on: boolean): Promise<void> => {
      await window.api.app.setAutoLaunch(on);
    },
  };
}
```

- [ ] Add a renderer-side `ElectronAPI` type for `window.api` matching the new preload. Create `apps/desktop/src/electron-api.d.ts`:

```ts
export interface DesktopApi {
  app: {
    getVersion(): Promise<string>;
    showNotification(title: string, body: string): void;
    getAutoLaunch(): Promise<boolean>;
    setAutoLaunch(enabled: boolean): Promise<boolean>;
  };
  secureStore: {
    setRefreshToken(token: string): Promise<void>;
    clear(): Promise<void>;
    refreshAndGetAccess(): Promise<{
      accessToken: string;
      expiresIn: number;
      user: { id: string; email: string; timeZoneId: string };
    } | null>;
  };
  focus: {
    showWidget(): void;
    hideWidget(): void;
    sendWidgetState(data: unknown): void;
    updateTray(data: { taskTitle: string | null; elapsed: string | null; isPlaying: boolean }): void;
  };
  on(channel: string, callback: (...args: any[]) => void): void;
  off(channel: string, callback: (...args: any[]) => void): void;
  removeAllListeners(channel: string): void;
}

declare global {
  interface Window {
    api: DesktopApi;
  }
}

export {};
```

> Q5 deletes the old `window.api` data surface that lived in `packages/app/src/types.ts` (the `ElectronAPI` interface + `Window.api` augmentation). Remove that block in Q5-9 when refactoring the store, so the only `Window.api` declaration is this desktop-local one (the web build has no `window.api`).

- [ ] `npm run build` in `apps/desktop` → expect compile of `DesktopPlatform` (other renderer errors from removed channels remain until Q5-8..Q5-10). 
- [ ] `git add apps/desktop/src/DesktopPlatform.ts apps/desktop/src/electron-api.d.ts && git commit -m "feat(desktop): DesktopPlatform adapter implementing Platform over preload

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`

---

### Task Q5-8: `WebPlatform` (apps/web/src) — cookie-based refresh, Web Notifications, no-op events

- [ ] No unit test for the fetch/Notification wiring (host-specific), but write a focused unit test for the permission-gating helper so the web logic has coverage. Create `apps/web/src/WebPlatform.test.ts`:

```ts
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { WebPlatform } from './WebPlatform';

describe('WebPlatform', () => {
  const origFetch = globalThis.fetch;
  afterEach(() => {
    globalThis.fetch = origFetch;
    vi.restoreAllMocks();
  });

  it('has web-shaped capabilities (no tray/widget/autoLaunch) and no focusWidget/autoLaunch', () => {
    const p = new WebPlatform('http://api.test');
    expect(p.capabilities).toEqual({
      tray: false,
      focusWidgetWindow: false,
      autoLaunch: false,
      dataPort: false,
    });
    expect(p.focusWidget).toBeUndefined();
    expect(p.autoLaunch).toBeUndefined();
  });

  it('on/off are no-ops (do not throw)', () => {
    const p = new WebPlatform('http://api.test');
    const cb = () => {};
    expect(() => p.on('navigate', cb)).not.toThrow();
    expect(() => p.off('navigate', cb)).not.toThrow();
  });

  it('refreshAndGetAccess POSTs /auth/refresh with credentials include + X-Tmap-Refresh, returns token', async () => {
    const fetchMock = vi.fn(async () =>
      new Response(
        JSON.stringify({
          accessToken: 'AT',
          expiresIn: 900,
          user: { id: 'u1', email: 'a@b.c', timeZoneId: 'UTC' },
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      ),
    );
    globalThis.fetch = fetchMock as unknown as typeof fetch;

    const p = new WebPlatform('http://api.test');
    const res = await p.auth.refreshAndGetAccess();

    expect(res?.accessToken).toBe('AT');
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe('http://api.test/api/v1/auth/refresh');
    expect(init.method).toBe('POST');
    expect(init.credentials).toBe('include');
    expect((init.headers as Record<string, string>)['X-Tmap-Refresh']).toBe('1');
  });

  it('returns null on a 401 from refresh (clears session upstream)', async () => {
    globalThis.fetch = vi.fn(async () => new Response('', { status: 401 })) as unknown as typeof fetch;
    const p = new WebPlatform('http://api.test');
    expect(await p.auth.refreshAndGetAccess()).toBeNull();
  });

  it('returns null (does not throw) on network failure', async () => {
    globalThis.fetch = vi.fn(async () => {
      throw new TypeError('Failed to fetch');
    }) as unknown as typeof fetch;
    const p = new WebPlatform('http://api.test');
    expect(await p.auth.refreshAndGetAccess()).toBeNull();
  });
});
```

> This test runs in `apps/web` — ensure Q4 gave `apps/web` a `vitest.config.ts`. If it lacks one, this task adds a minimal `apps/web/vitest.config.ts` (`environment: 'jsdom'`, `globals: true`) and a `"test": "vitest run"` script; otherwise reuse the existing one. Surface the gap rather than assuming.

- [ ] Run `npm test` (from `apps/web`, or root if the workspace test script fans out) → expect FAIL (no `./WebPlatform`).

- [ ] Create `apps/web/src/WebPlatform.ts`:

```ts
import type { Platform, AppChannel } from '@/platform/Platform';
import type { AuthTokenResponse } from '@/auth/authStore';

/**
 * Web host adapter. The refresh token lives in an httpOnly cookie (never visible
 * to JS); refresh is a credentialed POST with the CSRF custom header. No tray,
 * no widget window, no auto-launch. Notifications use the Web Notifications API.
 */
export class WebPlatform implements Platform {
  readonly capabilities = {
    tray: false,
    focusWidgetWindow: false,
    autoLaunch: false,
    dataPort: false,
  };

  private readonly baseUrl: string;

  constructor(baseUrl: string) {
    this.baseUrl = baseUrl.replace(/\/$/, '');
  }

  readonly auth = {
    refreshAndGetAccess: async (): Promise<AuthTokenResponse | null> => {
      try {
        const res = await fetch(`${this.baseUrl}/api/v1/auth/refresh`, {
          method: 'POST',
          credentials: 'include',
          headers: { 'X-Tmap-Refresh': '1' },
        });
        if (res.status === 401) return null; // cookie missing/expired → anonymous
        if (!res.ok) return null; // network/5xx → couldn't refresh (don't clear)
        const data = (await res.json()) as AuthTokenResponse;
        return data;
      } catch {
        return null; // network failure → couldn't refresh
      }
    },
    clear: async (): Promise<void> => {
      // The httpOnly cookie is cleared server-side by /auth/logout (called by authStore.logout).
      // Nothing JS-accessible to clear here.
    },
  };

  notify(title: string, body: string): void {
    try {
      if (typeof Notification === 'undefined') return;
      if (Notification.permission === 'granted') {
        new Notification(title, { body });
      } else if (Notification.permission === 'default') {
        void Notification.requestPermission().then((perm) => {
          if (perm === 'granted') new Notification(title, { body });
        });
      }
      // 'denied' → silent
    } catch {
      // Notifications unavailable → silent
    }
  }

  // Web has no host event channels; on/off are no-ops by design (spec §4).
  on(_channel: AppChannel, _cb: (...args: any[]) => void): void {}
  off(_channel: AppChannel, _cb: (...args: any[]) => void): void {}

  // focusWidget and autoLaunch intentionally absent (capabilities are false).
}
```

- [ ] Run `npm test` → expect PASS.
- [ ] `git add apps/web/src/WebPlatform.ts apps/web/src/WebPlatform.test.ts && git commit -m "feat(web): WebPlatform adapter (cookie refresh, Web Notifications, no-op events)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`

---

### Task Q5-9: Store + AppRoot — settings split (synced vs local) and platform context

The store currently loads/saves all settings via `window.api.settings`. Split: synced (`workStartHour`/`workEndHour`/`timeIncrement` + `timeZoneId`) flow through `dataClient.settings`; local-only (`sidebarCollapsed`/`notesCollapsed`/`projectsCollapsed`) through `localPrefs`. Also expose the platform to components via React context and provide a `getReminderTasks` selector.

- [ ] Write a failing test for the store's settings partitioning helpers. Create `packages/app/src/store.settingsSplit.test.ts`:

```ts
import { describe, it, expect } from 'vitest';
import {
  splitSyncedSettings,
  applyLoadedSettings,
  type SyncedSettings,
} from './store';

describe('settings split', () => {
  it('splitSyncedSettings stringifies the three numeric synced values and carries timeZoneId top-level', () => {
    const { settings, timeZoneId } = splitSyncedSettings({
      workStartHour: 8,
      workEndHour: 20,
      timeIncrement: 15,
      timeZoneId: 'Europe/Berlin',
    });
    expect(settings).toEqual({
      workStartHour: '8',
      workEndHour: '20',
      timeIncrement: '15',
    });
    expect(timeZoneId).toBe('Europe/Berlin');
  });

  it('applyLoadedSettings parses string→number for the three synced values', () => {
    const applied = applyLoadedSettings(
      { workStartHour: '9', workEndHour: '17', timeIncrement: '30' },
      'America/New_York',
    );
    expect(applied).toEqual({
      workStartHour: 9,
      workEndHour: 17,
      timeIncrement: 30,
      timeZoneId: 'America/New_York',
    });
  });

  it('applyLoadedSettings ignores non-numeric / missing values (keeps undefined for the caller to default)', () => {
    const applied = applyLoadedSettings({ workStartHour: 'oops' }, 'UTC');
    expect(applied.workStartHour).toBeUndefined();
    expect(applied.timeZoneId).toBe('UTC');
  });
});
```

- [ ] Run `npm test` → expect FAIL (helpers not exported from `store.ts`).

- [ ] In `packages/app/src/store.ts`: add the two exported pure helpers + the `SyncedSettings` type near the top (above the store), and refactor `loadSettings`/`setWorkHours`/`setTimeIncrement`/`setNotesCollapsed`/`setProjectsCollapsed`/`setSidebarCollapsed` to use them. Concretely:

```ts
// ─── Settings split helpers (synced via dataClient.settings; local via localPrefs) ───
import { loadLocalPrefs, saveLocalPref } from './lib/localPrefs';

export interface SyncedSettings {
  workStartHour: number;
  workEndHour: number;
  timeIncrement: number;
  timeZoneId: string;
}

/** Domain (numbers) → wire (string map + top-level timeZoneId). */
export function splitSyncedSettings(s: SyncedSettings): {
  settings: Record<string, string>;
  timeZoneId: string;
} {
  return {
    settings: {
      workStartHour: String(s.workStartHour),
      workEndHour: String(s.workEndHour),
      timeIncrement: String(s.timeIncrement),
    },
    timeZoneId: s.timeZoneId,
  };
}

/** Wire (string map + timeZoneId) → partial domain (numbers); skips non-numeric. */
export function applyLoadedSettings(
  settings: Record<string, unknown>,
  timeZoneId: string,
): Partial<SyncedSettings> {
  const out: Partial<SyncedSettings> = { timeZoneId };
  const num = (v: unknown): number | undefined => {
    const n = Number(v);
    return v != null && v !== '' && !Number.isNaN(n) ? n : undefined;
  };
  const ws = num(settings.workStartHour);
  const we = num(settings.workEndHour);
  const ti = num(settings.timeIncrement);
  if (ws !== undefined) out.workStartHour = ws;
  if (we !== undefined) out.workEndHour = we;
  if (ti !== undefined) out.timeIncrement = ti;
  return out;
}
```

Then rewrite the relevant store actions (replace the `window.api.settings` body). Use the injected `dataClient` (assume Q3 placed it on the store as `get().dataClient`; if Q3 named it differently, use that name and note the gap):

```ts
loadSettings: async () => {
  // Local-only prefs (no network).
  const local = loadLocalPrefs();
  set({
    sidebarCollapsed: local.sidebarCollapsed,
    notesCollapsed: local.notesCollapsed,
    projectsCollapsed: local.projectsCollapsed,
  });
  // Synced settings (after auth).
  try {
    const { settings, timeZoneId } = await get().dataClient.settings.get();
    const applied = applyLoadedSettings(settings, timeZoneId);
    const updates: Partial<{ workStartHour: number; workEndHour: number; timeIncrement: number; timeZoneId: string }> = {};
    if (applied.workStartHour !== undefined) updates.workStartHour = applied.workStartHour;
    if (applied.workEndHour !== undefined) updates.workEndHour = applied.workEndHour;
    if (applied.timeIncrement !== undefined) updates.timeIncrement = applied.timeIncrement;
    if (applied.timeZoneId) updates.timeZoneId = applied.timeZoneId;
    set(updates);
  } catch (e) {
    console.error('Failed to load synced settings:', e);
    get().setOnlineError?.('Couldn’t load your settings from the server.');
  }
},

persistSyncedSettings: async () => {
  const { workStartHour, workEndHour, timeIncrement, timeZoneId } = get();
  const { settings, timeZoneId: tz } = splitSyncedSettings({
    workStartHour,
    workEndHour,
    timeIncrement,
    timeZoneId: timeZoneId ?? 'UTC',
  });
  try {
    await get().dataClient.settings.save(settings, tz);
  } catch (e) {
    console.error('Failed to save synced settings:', e);
    get().setOnlineError?.('Couldn’t save your settings to the server.');
  }
},

setTimeIncrement: (inc: number) => {
  set({ timeIncrement: inc });
  void get().persistSyncedSettings();
},

setWorkHours: (start: number, end: number) => {
  set({ workStartHour: start, workEndHour: end });
  void get().persistSyncedSettings();
},

setSidebarCollapsed: (collapsed: boolean) => {
  set({ sidebarCollapsed: collapsed });
  saveLocalPref('sidebarCollapsed', collapsed);
},

setNotesCollapsed: (collapsed: boolean) => {
  set({ notesCollapsed: collapsed });
  saveLocalPref('notesCollapsed', collapsed);
},

setProjectsCollapsed: (collapsed: boolean) => {
  set({ projectsCollapsed: collapsed });
  saveLocalPref('projectsCollapsed', collapsed);
},
```

Add `timeZoneId: string`, `onlineError: string | null`, `setOnlineError`, and `getReminderTasks` to the store state/type:

```ts
// in the state interface
timeZoneId: string;
onlineError: string | null;
setOnlineError: (msg: string | null) => void;
getReminderTasks: () => { id: string; title: string; status: string; scheduledStart: string | null; reminderMinutes: number | null }[];

// in the initial state
timeZoneId: 'UTC',
onlineError: null,

// in the actions
setOnlineError: (msg) => set({ onlineError: msg }),
getReminderTasks: () =>
  get().tasks.map((t) => ({
    id: t.id,
    title: t.title,
    status: t.status,
    scheduledStart: t.scheduledStart,
    reminderMinutes: t.reminderMinutes,
  })),
```

> Remove the old `settings.save` / `window.api.settings.*` calls and the `ElectronAPI`/`Window.api` declaration block from `packages/app/src/types.ts` (data IPC is gone; the only `window.api` is desktop-local from Q5-7). If Q2/Q3 already removed it, skip and note it. The `timeZoneId` field default of `'UTC'` is overwritten by `loadSettings` post-auth and by the authed user's `user.timeZoneId` (authStore) — wire authStore's `user.timeZoneId` into the store on login if Q2 exposes a hook; otherwise `loadSettings` covers it.

- [ ] Run `npm test` → expect PASS (settings-split helper tests).

- [ ] Provide platform via React context. In `packages/app/src/AppRoot.tsx`, add a `PlatformContext` and a `usePlatform` hook (so `FocusModeOverlay`/`SettingsDialog`/`App` read the platform without prop-drilling). Append to `AppRoot.tsx`:

```tsx
import { createContext, useContext } from 'react';
import type { Platform } from './platform/Platform';

const PlatformContext = createContext<Platform | null>(null);

export function usePlatform(): Platform {
  const p = useContext(PlatformContext);
  if (!p) throw new Error('usePlatform must be used within <AppRoot>');
  return p;
}

export { PlatformContext };
```

Then ensure `AppRoot`'s authed branch wraps the app tree in `<PlatformContext.Provider value={platform}>` (where `platform` is the prop AppRoot already receives per Q2's signature `{ dataClient, platform }`). Surface the gap if Q2's AppRoot signature differs.

- [ ] `git add packages/app/src/store.ts packages/app/src/store.settingsSplit.test.ts packages/app/src/AppRoot.tsx packages/app/src/types.ts && git commit -m "feat(settings): split synced vs local settings; add platform context + online-error state

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`

---

### Task Q5-10: `FocusModeOverlay` — route all tray/widget/IPC through `platform.focusWidget`/`on`

Replace every `window.api.focus.*` and `window.api.on/removeAllListeners('focus:*')` call with `platform.focusWidget?.*` and `platform.focusWidget?.onAction`/`onResyncRequest`. The tray `updateTray` call has no Platform method (it is desktop-internal tray tooltip plumbing). Per spec §4, focus-widget wiring goes through `platform.focusWidget?`; the tray tooltip update is folded into `focusWidget.pushState` on desktop, OR kept as an optional desktop-only side effect. To keep behavior identical without adding a tray method to `Platform`, gate the tray update behind `capabilities.tray` and call it through a narrow optional. Simplest faithful approach: keep `pushState` for the widget and add the tray tooltip inside DesktopPlatform's `pushState` (desktop pushes both widget state AND tray) — so the overlay only calls `pushState`.

- [ ] No unit test (UI). Gate = both builds + manual run.

- [ ] Update `DesktopPlatform.focusWidget.pushState` to also drive the tray tooltip (so the overlay stays host-agnostic). Replace the `pushState` body in `apps/desktop/src/DesktopPlatform.ts`:

```ts
pushState: (state: FocusWidgetState): void => {
  window.api.focus.sendWidgetState(state);
  // Desktop also reflects the running session in the tray tooltip.
  // The overlay no longer formats elapsed for the tray; pass a coarse label.
  window.api.focus.updateTray({
    taskTitle: state.taskTitle || null,
    elapsed: state.taskTitle ? '' : null,
    isPlaying: state.isPlaying,
  });
},
```

> The fine-grained per-second tray elapsed string previously came from the overlay's 1s tick. Moving it into `pushState` would push every second. To preserve the existing tray "elapsed" tooltip without per-second IPC spam, keep a dedicated overlay→tray push for elapsed BUT gate it on `capabilities.tray`. Implement that in the overlay below (a separate effect that only runs when `platform.capabilities.tray`), calling a new `platform.focusWidget?.pushState` is wrong for elapsed; instead expose the tray as part of focusWidget is out-of-interface. DECISION (surface as a deliberate scope note): the SP2 spec's `Platform.focusWidget` has no tray-elapsed method, so SP2 drops the *per-second tray tooltip elapsed* (the widget window already shows live elapsed). The tray still shows play/pause + title via `pushState`. This is an intentional, documented minor regression of the tray tooltip's seconds; note it in the PR.

- [ ] Replace the IPC-coupled blocks in `packages/app/src/components/focus/FocusModeOverlay.tsx`:

  1. Change the imports/top to read the platform:
  ```tsx
  import { usePlatform } from '../../AppRoot';
  // ...inside the component, after useStore():
  const platform = usePlatform();
  ```

  2. Replace the show/hide widget effect (old lines ~138–150):
  ```tsx
  // ─── Always-on-top Widget Window (desktop only) ───────────
  useEffect(() => {
    if (!platform.focusWidget) return;
    if (target) {
      platform.focusWidget.show();
    } else {
      platform.focusWidget.hide();
    }
    return () => {
      platform.focusWidget?.hide();
    };
  }, [!!target, platform]);
  ```

  3. Replace the push-state effect (old lines ~152–173):
  ```tsx
  useEffect(() => {
    if (!platform.focusWidget || !target) return;
    platform.focusWidget.pushState({
      taskTitle: target.title,
      isPlaying: focusMode.isPlaying,
      sessionStartTime: focusMode.sessionStartTime,
      accumulatedMinutes: target.accumulatedMinutes,
      plannedMinutes: target.plannedMinutes,
      canComplete: target.kind === 'task',
    });
  }, [
    focusMode.targetId,
    target?.title,
    target?.accumulatedMinutes,
    target?.plannedMinutes,
    target?.kind,
    focusMode.isPlaying,
    focusMode.sessionStartTime,
    platform,
  ]);
  ```

  4. DELETE the old tray-communication effect (old lines ~175–188, the per-second `window.api.focus.updateTray`) entirely — superseded by `pushState` (see scope note above).

  5. Replace the command-listener effect (old lines ~190–263) to use `platform.focusWidget?.onAction`/`onResyncRequest`. Since `onAction`/`onResyncRequest` register-only (no off), guard against double-registration by registering once on mount and reading live state via `useStore.getState()`:
  ```tsx
  useEffect(() => {
    if (!platform.focusWidget) return;

    const handleAction = (action: 'togglePlayPause' | 'stop' | 'done') => {
      const state = useStore.getState();
      const fm = state.focusMode;
      if (!fm.targetId) return;
      if (action === 'togglePlayPause') {
        if (fm.isPlaying) state.pauseFocusSession();
        else if (fm.targetType === 'task') state.startFocusSession(fm.targetId);
        else if (fm.targetType === 'project') state.startProjectFocus(fm.targetId);
      } else if (action === 'stop') {
        void state.stopFocusSession();
      } else if (action === 'done') {
        void (async () => {
          await state.stopFocusSession();
          if (fm.targetType === 'task') await state.markDone(fm.targetId!);
        })();
      }
    };

    const handleResync = () => {
      const s = useStore.getState();
      const fm = s.focusMode;
      const t = fm.targetType === 'task' ? s.tasks.find((x) => x.id === fm.targetId) : undefined;
      const p =
        fm.targetType === 'project' ? s.projects.find((x) => x.id === fm.targetId) : undefined;
      if (t) {
        platform.focusWidget?.pushState({
          taskTitle: t.title,
          isPlaying: fm.isPlaying,
          sessionStartTime: fm.sessionStartTime,
          accumulatedMinutes: t.actualTimeMinutes || 0,
          plannedMinutes: t.durationMinutes || 0,
          canComplete: true,
        });
      } else if (p) {
        platform.focusWidget?.pushState({
          taskTitle: `${p.emoji} ${p.name}`,
          isPlaying: fm.isPlaying,
          sessionStartTime: fm.sessionStartTime,
          accumulatedMinutes: p.actualTimeMinutes || 0,
          plannedMinutes: 0,
          canComplete: false,
        });
      }
    };

    platform.focusWidget.onAction(handleAction);
    platform.focusWidget.onResyncRequest(handleResync);
    // Note: DesktopPlatform registers via window.api.on; the overlay mounts once
    // for the app lifetime, so no explicit teardown is needed here. Web has no
    // focusWidget so this effect is a no-op.
  }, [platform]);
  ```

> The in-app draggable overlay UI (the JSX from old line ~265 onward) is unchanged and renders on **both** hosts (web shows in-app overlay only; desktop additionally drives the OS widget window). No web branch needed in the render — the `platform.focusWidget?` guards already make the widget-window calls no-op on web.

- [ ] `npm run build` in `apps/desktop` and `apps/web` → expect compile (overlay no longer references `window.api`). 
- [ ] `git add packages/app/src/components/focus/FocusModeOverlay.tsx apps/desktop/src/DesktopPlatform.ts && git commit -m "refactor(focus): route widget/tray/IPC through platform.focusWidget; web in-app only

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`

---

### Task Q5-11: `App.tsx` — `navigate` via `platform.on`; mount reminder scheduler + OnlineErrorBanner

- [ ] No unit test (UI). Gate = builds + manual run.

- [ ] In `packages/app/src/App.tsx` replace the IPC navigation effect (old lines ~57–71) with platform events and add the reminder scheduler lifecycle + banner:

```tsx
import { usePlatform } from './AppRoot';
import { startReminderScheduler } from './reminders/reminderScheduler';
import { OnlineErrorBanner } from './components/OnlineErrorBanner';
// ...inside App(), after useStore():
const platform = usePlatform();
const getReminderTasks = useStore((s) => s.getReminderTasks);

// Navigation events from the host (desktop tray); web no-ops.
useEffect(() => {
  const onNavigate = (route: string) => {
    if (route === 'plan-today') startPlanningFlow();
  };
  platform.on('navigate', onNavigate);
  return () => platform.off('navigate', onNavigate);
}, [platform, startPlanningFlow]);

// Client-side reminder timer (replaces the removed main-process scheduler).
useEffect(() => {
  const stop = startReminderScheduler({
    getTasks: () => getReminderTasks(),
    notify: (title, body) => platform.notify(title, body),
  });
  return stop;
}, [platform, getReminderTasks]);
```

> Remove the now-empty `handleKeyDown`/`window.addEventListener('keydown', ...)` scaffold if it's still dead, OR leave it untouched if other phases rely on it — prefer minimal diff: keep the keydown listener block as-is, only swap the IPC nav for `platform.on`.

- [ ] Render the banner in the App tree (top of the layout, non-destructive). Add near the title-bar drag region:
```tsx
<OnlineErrorBanner />
```

- [ ] `git add packages/app/src/App.tsx && git commit -m "feat(app): navigate via platform.on; mount client reminder scheduler + error banner

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`

---

### Task Q5-12: `OnlineErrorBanner.tsx` — non-destructive unreachable-server banner

Reads `onlineError` from the store (set by `loadSettings`, the data client on write failure, rollover failures, and bootstrap network errors). Dismissible; auto-hides when cleared.

- [ ] No unit test (presentational). Optionally a tiny render-logic test is skipped since there is no component-test harness (spec convention).

- [ ] Create `packages/app/src/components/OnlineErrorBanner.tsx`:

```tsx
import React from 'react';
import { AlertTriangle, X } from 'lucide-react';
import { useStore } from '../store';

/**
 * Non-destructive banner shown when the app can't reach the server. No local
 * cache in SP2 — failed reads/writes set `onlineError` and surface here.
 */
export function OnlineErrorBanner() {
  const onlineError = useStore((s) => s.onlineError);
  const setOnlineError = useStore((s) => s.setOnlineError);

  if (!onlineError) return null;

  return (
    <div className="fixed top-10 left-1/2 -translate-x-1/2 z-[9998] max-w-xl w-[calc(100%-2rem)]">
      <div className="flex items-center gap-3 px-4 py-2.5 rounded-xl bg-danger-600/15 border border-danger-500/40 text-danger-200 shadow-lg shadow-black/30 backdrop-blur-md animate-fade-in">
        <AlertTriangle className="w-4 h-4 flex-shrink-0 text-danger-400" />
        <span className="text-sm flex-1">{onlineError}</span>
        <button
          onClick={() => setOnlineError(null)}
          className="p-1 -mr-1 rounded-md text-danger-300 hover:text-danger-100 hover:bg-danger-600/20 transition-colors"
          aria-label="Dismiss"
        >
          <X className="w-4 h-4" />
        </button>
      </div>
    </div>
  );
}
```

> If the project's Tailwind config lacks a `danger-*` scale, fall back to `red-*` classes (CLAUDE.md lists `danger` as a custom color, so `danger-*` should exist). Verify against `tailwind.config.js`; surface a gap if absent.

- [ ] `npm run build` in both apps → expect compile.
- [ ] `git add packages/app/src/components/OnlineErrorBanner.tsx && git commit -m "feat(app): non-destructive online-error banner

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`

---

### Task Q5-13: `SettingsDialog.tsx` — capability-gate auto-launch, REMOVE Data export/import, settings split

Removes the entire Data section (export/import + status state + handlers + the `data:`/`loadTasks`/`loadProjects` reload wiring tied to import). Auto-launch goes through `platform.autoLaunch?` gated by `capabilities.autoLaunch` (hidden on web). Work hours / increment continue to save through the (now settings-split) store actions.

- [ ] No unit test (UI). Gate = builds + manual run.

- [ ] Replace `packages/app/src/components/SettingsDialog.tsx` with the gated, Data-free version:

```tsx
import React, { useState, useEffect } from 'react';
import { useStore } from '../store';
import { X, Save, Clock, Power } from 'lucide-react';
import { clsx } from 'clsx';
import { usePlatform } from '../AppRoot';

const TIME_OPTIONS = Array.from({ length: 24 }, (_, i) => ({
  value: i,
  label: i === 0 ? '12:00 AM' : i < 12 ? `${i}:00 AM` : i === 12 ? '12:00 PM' : `${i - 12}:00 PM`,
}));

export function SettingsDialog() {
  const {
    settingsOpen,
    setSettingsOpen,
    workStartHour,
    workEndHour,
    timeIncrement,
    setWorkHours,
    setTimeIncrement,
  } = useStore();
  const platform = usePlatform();

  const [start, setStart] = useState(workStartHour);
  const [end, setEnd] = useState(workEndHour);
  const [increment, setIncrement] = useState(timeIncrement);
  const [autoLaunch, setAutoLaunch] = useState(false);

  const autoLaunchSupported = platform.capabilities.autoLaunch && !!platform.autoLaunch;

  useEffect(() => {
    if (settingsOpen) {
      setStart(workStartHour);
      setEnd(workEndHour);
      setIncrement(timeIncrement);
      if (autoLaunchSupported) {
        platform.autoLaunch!.get().then(setAutoLaunch).catch(() => {});
      }
    }
  }, [settingsOpen, workStartHour, workEndHour, timeIncrement, autoLaunchSupported, platform]);

  if (!settingsOpen) return null;

  const handleSave = () => {
    if (start < end) {
      setWorkHours(start, end);
      setTimeIncrement(increment);
    }
    setSettingsOpen(false);
  };

  return (
    <div
      className="fixed inset-0 z-50 bg-black/60 backdrop-blur-sm flex items-center justify-center p-4"
      onClick={(e) => {
        if (e.target === e.currentTarget) setSettingsOpen(false);
      }}
      onKeyDown={(e) => {
        if (e.key === 'Escape') setSettingsOpen(false);
      }}
    >
      <div className="w-full max-w-md bg-surface-900 border border-surface-700/60 rounded-2xl shadow-2xl flex flex-col animate-scale-in overflow-hidden max-h-[90vh]">
        {/* Header */}
        <div className="flex items-center justify-between px-6 py-4 border-b border-surface-800">
          <h2 className="text-lg font-semibold text-surface-100 flex items-center gap-2">
            <Clock className="w-5 h-5 text-accent-400" />
            Settings
          </h2>
          <button
            onClick={() => setSettingsOpen(false)}
            className="p-2 -mr-2 text-surface-400 hover:text-surface-100 rounded-lg hover:bg-surface-800 transition-colors"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Body */}
        <div className="p-6 space-y-6 overflow-y-auto">
          {/* Startup (desktop only) */}
          {autoLaunchSupported && (
            <div>
              <h3 className="text-xs font-semibold uppercase tracking-wider text-surface-400 mb-3">
                Startup
              </h3>
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <Power className="w-4 h-4 text-surface-400" />
                  <span className="text-sm text-surface-200">Launch on system startup</span>
                </div>
                <button
                  onClick={() => {
                    const next = !autoLaunch;
                    setAutoLaunch(next);
                    void platform.autoLaunch!.set(next);
                  }}
                  className={clsx(
                    'relative w-10 h-5 rounded-full transition-colors',
                    autoLaunch ? 'bg-accent-600' : 'bg-surface-700',
                  )}
                >
                  <span
                    className={clsx(
                      'absolute top-0.5 left-0.5 w-4 h-4 rounded-full bg-white transition-transform',
                      autoLaunch && 'translate-x-5',
                    )}
                  />
                </button>
              </div>
            </div>
          )}

          {/* Work Hours */}
          <div>
            <h3 className="text-xs font-semibold uppercase tracking-wider text-surface-400 mb-3">
              Work Hours
            </h3>
            <p className="text-xs text-surface-500 mb-3">
              The timeline will show only the hours within your work schedule.
            </p>
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="block text-xs text-surface-400 mb-1.5">Start</label>
                <select
                  value={start}
                  onChange={(e) => setStart(Number(e.target.value))}
                  className="w-full px-3 py-2 bg-surface-950 border border-surface-700/60 rounded-xl text-sm text-surface-100 focus:outline-none focus:border-accent-500/50 focus:ring-1 focus:ring-accent-500/20 [color-scheme:dark]"
                >
                  {TIME_OPTIONS.filter((t) => t.value < end).map((t) => (
                    <option key={t.value} value={t.value}>
                      {t.label}
                    </option>
                  ))}
                </select>
              </div>
              <div>
                <label className="block text-xs text-surface-400 mb-1.5">End</label>
                <select
                  value={end}
                  onChange={(e) => setEnd(Number(e.target.value))}
                  className="w-full px-3 py-2 bg-surface-950 border border-surface-700/60 rounded-xl text-sm text-surface-100 focus:outline-none focus:border-accent-500/50 focus:ring-1 focus:ring-accent-500/20 [color-scheme:dark]"
                >
                  {TIME_OPTIONS.filter((t) => t.value > start).map((t) => (
                    <option key={t.value} value={t.value}>
                      {t.label}
                    </option>
                  ))}
                </select>
              </div>
            </div>

            <div className="mt-3 px-3 py-2 bg-surface-950 rounded-lg border border-surface-800/60 text-xs text-surface-400 flex items-center gap-2">
              <Clock className="w-3.5 h-3.5" />
              <span>
                Timeline: {TIME_OPTIONS.find((t) => t.value === start)?.label} –{' '}
                {TIME_OPTIONS.find((t) => t.value === end)?.label} ({end - start} hours)
              </span>
            </div>
          </div>

          {/* Time Increment */}
          <div>
            <h3 className="text-xs font-semibold uppercase tracking-wider text-surface-400 mb-3">
              Time Increment
            </h3>
            <p className="text-xs text-surface-500 mb-3">
              Snap tasks to these intervals when dragging on the timeline.
            </p>
            <div className="flex items-center gap-2">
              {[5, 10, 15, 30].map((inc) => (
                <button
                  key={inc}
                  onClick={() => setIncrement(inc)}
                  className={clsx(
                    'px-4 py-2 rounded-lg text-sm font-medium transition-all border',
                    increment === inc
                      ? 'bg-accent-600/20 border-accent-500/40 text-accent-400'
                      : 'bg-surface-950 border-surface-700/60 text-surface-400 hover:text-surface-200 hover:border-surface-600',
                  )}
                >
                  {inc}m
                </button>
              ))}
            </div>
          </div>
          {/* Data export/import section intentionally removed in SP2 (online-only; spec §6/§7). */}
        </div>

        {/* Footer */}
        <div className="flex items-center justify-end px-6 py-4 border-t border-surface-800 gap-3">
          <button
            onClick={() => setSettingsOpen(false)}
            className="px-4 py-2 text-sm text-surface-300 hover:text-surface-100 rounded-lg hover:bg-surface-800 transition-colors"
          >
            Cancel
          </button>
          <button
            onClick={handleSave}
            className="flex items-center gap-2 px-5 py-2 text-sm font-medium rounded-lg transition-all shadow-lg bg-accent-600 hover:bg-accent-500 text-white shadow-accent-500/20"
          >
            <Save className="w-4 h-4" />
            Save
          </button>
        </div>
      </div>
    </div>
  );
}
```

> Dropped imports (`Download`/`Upload`/`CheckCircle`/`AlertCircle`/`Loader2`) and the `loadTasks`/`loadProjects`/`loadSettings`/`dataStatus` usage are removed with the Data section. `loadSettings` is no longer needed here (the import-reload path is gone); `loadSettings` is still called once at app boot in `App.tsx`.

- [ ] `npm run build` in both apps → expect compile.
- [ ] `git add packages/app/src/components/SettingsDialog.tsx && git commit -m "feat(settings): gate auto-launch by capability; remove Data export/import (SP2 online-only)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`

---

### Task Q5-14: Wire `secureStore.setRefreshToken` into login/register (desktop) — auth seam glue

The desktop refresh path only works if a refresh token was persisted at login/register. Q2's authStore owns login/register; this task connects it to `platform.auth` so the desktop persists the issued refresh token in main.

- [ ] No unit test for the wiring (host-specific), but the authStore single-flight is already covered by Q2's tests.

- [ ] Confirm Q2's `Platform.auth` consumer contract. The shared `Platform.auth` declares `refreshAndGetAccess`/`clear`. Desktop login/register receive a `refreshToken` in the typed `AuthTokenResponse`-adjacent body. Two seam options — pick the one matching Q2:
  - **(A) If Q2's authStore calls `platform.auth.setRefreshToken?.(token)` after login/register:** add `setRefreshToken?(token: string): Promise<void>` to the `Platform.auth` shape in `Platform.ts` (optional, desktop-only), already implemented in `DesktopPlatform.auth.setRefreshToken` (Q5-7) and a web no-op.
  - **(B) If Q2 expects the host to persist via a dedicated method NOT on `Platform`:** leave `Platform.auth` as-is and document that the desktop entry (apps/desktop/src/main.tsx) passes a callback. 

  Default to **(A)**: edit `Platform.ts` `auth` to add the optional method:

```ts
  auth: {
    refreshAndGetAccess(): Promise<AuthTokenResponse | null>;
    clear(): Promise<void>;
    /** Desktop-only: persist a freshly-issued refresh token (web uses the cookie). */
    setRefreshToken?(token: string): Promise<void>;
  };
```

  And add a web no-op in `WebPlatform.auth`:
```ts
    setRefreshToken: async (): Promise<void> => {
      // Web: refresh token is set as an httpOnly cookie by the server response.
    },
```

- [ ] Update the Platform contract test (Q5-1) stub to include the optional method only if Q2 requires it non-optional — keep it optional, so the existing test still passes. Re-run `npm test` → expect PASS.

- [ ] `git add packages/app/src/platform/Platform.ts apps/web/src/WebPlatform.ts && git commit -m "feat(auth): optional platform.auth.setRefreshToken (desktop persists refresh in main)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`

---

### Task Q5-15: Final build + test gates (both apps + shared package)

- [ ] From `packages/app`: `npm test` → expect ALL PASS (Platform contract, localPrefs, reminderScheduler, settings-split helpers; plus Q2/Q3 tests unaffected).
- [ ] From `apps/web`: `npm test` → expect PASS (WebPlatform tests).
- [ ] From `apps/desktop`: `npm run build` → expect green (TS compile of electron main/preload/config + renderer; `file://` `base: './'`; no `sql.js`/`uuid` in main bundle; no `window.api` data references remain).
- [ ] From `apps/web`: `npm run build` → expect green (no `window.api`/Electron references; `base: '/'`).
- [ ] Manual verification (no automated harness): 
  1. Start API (`dotnet run` per Q0) + web (`npm run dev` in `apps/web`) + desktop (`npm run dev` in `apps/desktop`).
  2. Desktop: register/login → confirm refresh token persisted (relaunch app → stays signed in via `secureStore.refreshAndGetAccess`); web: register/login → relaunch tab → stays signed in via cookie refresh.
  3. Schedule a task with a reminder a minute out → confirm a desktop OS notification AND (with permission granted) a web notification fire once, via the client scheduler.
  4. Desktop: start a focus session → confirm the always-on-top widget appears, play/pause/stop/done from the widget AND tray drive the app; `Ctrl+Shift+Q` toggles the widget and resyncs. Web: focus session shows the in-app overlay only (no OS widget).
  5. Settings: desktop shows "Launch on system startup"; web hides it; no Data export/import on either; changing work hours/increment round-trips through the server (reload, value persists; verify on the other client).
  6. Kill the API → trigger a settings save/load → confirm the non-destructive OnlineErrorBanner appears and is dismissible; no crash, no data loss.
- [ ] `git commit --allow-empty -m "chore(sp2): Q5 gates green — platform/adapters/reminders/settings-split/main-unwire

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`

---

**Cross-phase gaps surfaced (for the orchestrator):**
- **Q4 dependency — `config.ts` / `API_BASE_URL`:** Q5 imports `API_BASE_URL` in `apps/desktop/electron/config.ts` (main) and `WebPlatform`'s base comes from the web entry (`import.meta.env.VITE_API_BASE_URL`). If Q4 named these differently, Q5 uses Q4's names; the port default `5050` must match Q0/Q4.
- **Q3 dependency — store `dataClient` accessor:** Q5's `loadSettings`/`persistSyncedSettings` call `get().dataClient.settings.*`. If Q3 injected the data client under a different store key, adapt.
- **Q2 dependency — `AppRoot({ dataClient, platform })` + `AuthTokenResponse` export + authStore login/register persisting refresh:** Q5 adds `PlatformContext`/`usePlatform` to `AppRoot.tsx` and `setRefreshToken` to `Platform.auth`; the desktop persists the issued refresh token at login/register. If Q2 already added a platform context, reuse it instead of redefining.
- **Tray-elapsed minor regression:** SP2's `Platform.focusWidget` has no per-second tray-tooltip-elapsed method; the live elapsed remains in the in-app overlay and the desktop widget window, while the tray tooltip shows title + play/pause only. Documented, intentional.

---

## Phase Q6 — Web app entry, config, wiring, end-to-end integration

This phase finishes both thin app entries (`apps/web` + `apps/desktop` `main.tsx` building `Platform` + `HttpDataClient` → `AppRoot`), config (`VITE_API_BASE_URL` + desktop base-URL mechanism), CORS verification, and the end-to-end manual integration gate proving both clients share one account against the running API.

### Task Q6-1: Add web dev origin to the API CORS allowlist

**Why first:** every browser-side test in Q6 requires the API to accept `Origin: http://localhost:5174`. The allowlist lives in `appsettings.Development.json` which the spec (§0.6) calls out explicitly.

- [ ] Read `backend/src/Tmap.Api/appsettings.Development.json` to confirm current `Cors.AllowedOrigins`.
- [ ] Edit `appsettings.Development.json` — add `"http://localhost:5174"` and `"http://127.0.0.1:5174"` to `Cors.AllowedOrigins`:

  ```json
  "Cors": {
    "AllowedOrigins": [
      "http://localhost:5173",
      "http://127.0.0.1:5173",
      "http://localhost:5174",
      "http://127.0.0.1:5174"
    ]
  }
  ```

- [ ] Verify the `CorsServiceCollectionExtensions` already calls `.AllowCredentials()` (needed for the web refresh cookie) — confirmed in the existing file, no code change needed there.
- [ ] Document desktop Origin handling in a code comment inside `CorsServiceCollectionExtensions.cs` (the comment already exists: "Desktop clients send no Origin header and are unaffected by CORS") — confirm it is present; no change needed.
- [ ] Build check — run `dotnet build backend/Tmap.sln` to confirm the allowlist change compiles (it is config, not code, but validate the solution is still clean):
  ```
  dotnet build backend/Tmap.sln
  ```
- [ ] Commit: `fix(cors): add web dev origin http://localhost:5174 to CORS allowlist`

---

### Task Q6-2: Add `secureStore` IPC channel to Electron main + preload

**Why:** `DesktopPlatform.auth.refreshAndGetAccess()` calls into main process, which holds the refresh token in `safeStorage`/keychain. The renderer must never see the raw refresh token. This task adds the two-sided IPC plumbing (main handles `secureStore:*`; preload exposes it).

- [ ] In `apps/desktop/electron/main.ts`, add the following at the top-level alongside the existing IPC handlers (inside `registerIpcHandlers()`), and import `safeStorage` from `'electron'`:

  ```typescript
  import { safeStorage } from 'electron';
  // Key used to persist the encrypted refresh token in userData
  const REFRESH_TOKEN_FILE = path.join(app.getPath('userData'), '.rt');

  function readRefreshToken(): string | null {
    try {
      if (!fs.existsSync(REFRESH_TOKEN_FILE)) return null;
      const buf = fs.readFileSync(REFRESH_TOKEN_FILE);
      if (!safeStorage.isEncryptionAvailable()) return null;
      return safeStorage.decryptString(buf);
    } catch { return null; }
  }

  function writeRefreshToken(token: string): void {
    if (!safeStorage.isEncryptionAvailable()) return;
    const buf = safeStorage.encryptString(token);
    fs.writeFileSync(REFRESH_TOKEN_FILE, buf);
  }

  function clearRefreshToken(): void {
    try { fs.rmSync(REFRESH_TOKEN_FILE, { force: true }); } catch {}
  }
  ```

- [ ] Inside `registerIpcHandlers()`, add the three `secureStore` handlers after the existing `settings` handlers:

  ```typescript
  // ─── Secure Token Store (refresh token lives here only) ─
  ipcMain.handle('secureStore:getRefreshToken', () => readRefreshToken());

  ipcMain.handle('secureStore:setRefreshToken', (_e: any, token: string) => {
    writeRefreshToken(token);
    return true;
  });

  ipcMain.handle('secureStore:clearRefreshToken', () => {
    clearRefreshToken();
    return true;
  });

  // Performs /auth/refresh in main so the refresh token never enters the renderer.
  // Returns { accessToken, expiresIn, user } on success; null on 401/network error.
  ipcMain.handle('secureStore:refreshAndGetAccess', async () => {
    const rt = readRefreshToken();
    if (!rt) return null;
    try {
      const apiBase = process.env.TMAP_API_BASE_URL ?? 'http://localhost:5120';
      const res = await fetch(`${apiBase}/api/v1/auth/refresh`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken: rt }),
      });
      if (!res.ok) {
        if (res.status === 401) clearRefreshToken();
        return null;
      }
      const data: any = await res.json();
      // data shape: AuthTokenResponse { accessToken, expiresIn, user }
      if (data.refreshToken) writeRefreshToken(data.refreshToken);
      return { accessToken: data.accessToken, expiresIn: data.expiresIn, user: data.user };
    } catch {
      return null; // network error — do not clear token
    }
  });
  ```

- [ ] In `apps/desktop/electron/preload.ts`, add the `secureStore` bridge alongside the existing `api` object (do NOT remove existing entries yet — they will be cleaned in Q6-3):

  ```typescript
  secureStore: {
    getRefreshToken: () => ipcRenderer.invoke('secureStore:getRefreshToken'),
    setRefreshToken: (token: string) => ipcRenderer.invoke('secureStore:setRefreshToken', token),
    clearRefreshToken: () => ipcRenderer.invoke('secureStore:clearRefreshToken'),
    refreshAndGetAccess: () => ipcRenderer.invoke('secureStore:refreshAndGetAccess'),
  },
  ```

  Expose it via `contextBridge.exposeInMainWorld('secureStore', ...)` as a separate key so `DesktopPlatform` can import it cleanly without mixing with the legacy `window.api`.

- [ ] Manual verify: start `npm run dev` in `apps/desktop`, open DevTools console, run `await window.secureStore.refreshAndGetAccess()` — expect `null` (no token stored yet) with no crash.
- [ ] Commit: `feat(desktop/ipc): add secureStore IPC channel for refresh-token custody in main process`

---

### Task Q6-3: Create `apps/desktop/src/config.ts` — desktop API base URL mechanism

**Why:** The desktop renderer needs a base URL for the `TmapClient`. Build-time `import.meta.env.VITE_API_BASE_URL` is the primary mechanism; a fallback to `http://localhost:5120` covers local dev.

- [ ] Create `apps/desktop/src/config.ts`:

  ```typescript
  /**
   * Desktop API base URL.
   *
   * Set VITE_API_BASE_URL at build time to point a packaged build at a different
   * server (e.g. VITE_API_BASE_URL=https://api.yourdomain.com npm run build).
   *
   * The main process also reads TMAP_API_BASE_URL from its own environment for the
   * secureStore:refreshAndGetAccess IPC call — keep both in sync in production.
   *
   * In dev (npm run dev) the default resolves to http://localhost:5120 which is the
   * local dotnet run default port.
   */
  export const API_BASE_URL: string =
    (import.meta.env.VITE_API_BASE_URL as string | undefined) ?? 'http://localhost:5120';
  ```

- [ ] In `apps/desktop/vite.config.ts`, ensure `define` exposes the env var (Vite already handles `import.meta.env.*` automatically for renderer code — no `define` block needed; just confirm no explicit block overrides it). Confirm the existing config does not have a `define` that would shadow it.
- [ ] Manual verify: `console.log(API_BASE_URL)` from the renderer shows `http://localhost:5120` in dev.
- [ ] Commit: `feat(desktop): add config.ts with VITE_API_BASE_URL-driven API base URL`

---

### Task Q6-4: Create `apps/desktop/src/DesktopPlatform.ts`

**Why:** The spec §4 defines the `Platform` interface; the desktop host adapter wires it to Electron IPC, safeStorage-backed auth, and the existing focus widget / tray channels.

- [ ] Create `apps/desktop/src/DesktopPlatform.ts` with complete code:

  ```typescript
  import type { Platform, AppChannel, AuthTokenResponse, FocusWidgetState } from '@tmap/app/src/platform/Platform';
  import { API_BASE_URL } from './config';

  type Listener = (...args: any[]) => void;
  const _listeners = new Map<AppChannel, Set<Listener>>();

  function ipcOn(channel: AppChannel) {
    window.api.on(channel, (...args: any[]) => {
      _listeners.get(channel)?.forEach((cb) => cb(...args));
    });
  }

  // Register reverse channels once
  (['navigate', 'focus:togglePlayPause', 'focus:stop', 'focus:done', 'focus:resyncWidget'] as AppChannel[]).forEach(ipcOn);

  export const DesktopPlatform: Platform = {
    capabilities: {
      tray: true,
      focusWidgetWindow: true,
      autoLaunch: true,
      dataPort: false,
    },

    auth: {
      async refreshAndGetAccess(): Promise<AuthTokenResponse | null> {
        // Delegated entirely to main process — refresh token never crosses to renderer.
        return (window as any).secureStore.refreshAndGetAccess() as Promise<AuthTokenResponse | null>;
      },
      async clear(): Promise<void> {
        await (window as any).secureStore.clearRefreshToken();
      },
    },

    notify(title: string, body: string): void {
      window.api.app.showNotification(title, body).catch(() => {});
    },

    on(channel: AppChannel, cb: Listener): void {
      if (!_listeners.has(channel)) _listeners.set(channel, new Set());
      _listeners.get(channel)!.add(cb);
    },

    off(channel: AppChannel, cb: Listener): void {
      _listeners.get(channel)?.delete(cb);
    },

    focusWidget: {
      pushState(s: FocusWidgetState): void {
        window.api.focus.sendWidgetState(s);
        window.api.focus.updateTray({
          taskTitle: s.taskTitle ?? null,
          elapsed: s.elapsed ?? null,
          isPlaying: s.isPlaying,
        });
      },
      show(): void {
        window.api.focus.showWidget();
      },
      hide(): void {
        window.api.focus.hideWidget();
      },
      onAction(cb: (a: 'togglePlayPause' | 'stop' | 'done') => void): void {
        // focus:widgetAction is already forwarded from main to renderer as focus:togglePlayPause etc.
        // The on/off seam handles these above; DesktopPlatform.on('focus:togglePlayPause', ...) routes them.
        // This dedicated callback is a convenience shim over the event seam:
        DesktopPlatform.on('focus:togglePlayPause', () => cb('togglePlayPause'));
        DesktopPlatform.on('focus:stop', () => cb('stop'));
        DesktopPlatform.on('focus:done', () => cb('done'));
      },
      onResyncRequest(cb: () => void): void {
        DesktopPlatform.on('focus:resyncWidget', cb);
      },
    },

    autoLaunch: {
      async get(): Promise<boolean> {
        return window.api.app.getAutoLaunch();
      },
      async set(on: boolean): Promise<void> {
        await window.api.app.setAutoLaunch(on);
      },
    },
  };
  ```

  > Note: `window.api` shape still comes from the existing preload. In this task, do NOT remove any legacy preload entries — that cleanup belongs to Q6-6 (post-integration-gate cleanup). The `FocusWidgetState` type and `AuthTokenResponse` type are imported from `packages/app`.

- [ ] Confirm TypeScript strict-mode: no `any` on the `Platform` method signatures (use the imported types); the `(window as any).secureStore` cast is intentional and documented.
- [ ] Manual verify: in dev, call `DesktopPlatform.auth.clear()` from console, verify no crash.
- [ ] Commit: `feat(desktop): add DesktopPlatform adapter implementing Platform interface`

---

### Task Q6-5: Create `apps/web/` package — scaffold, config, WebPlatform, env, main.tsx

**Why:** The web app does not exist yet. This task creates the full thin shell: `package.json`, `vite.config.ts`, `tsconfig.json`, `index.html`, `.env.example`, `WebPlatform.ts`, and `main.tsx`.

- [ ] Create `apps/web/package.json`:

  ```json
  {
    "name": "@tmap/web",
    "version": "1.0.0",
    "private": true,
    "type": "module",
    "scripts": {
      "dev": "vite --port 5174",
      "build": "tsc && vite build",
      "preview": "vite preview --port 5174"
    },
    "dependencies": {
      "@tmap/app": "*",
      "@tmap/api-client": "*"
    },
    "devDependencies": {
      "@vitejs/plugin-react": "^4.3.4",
      "typescript": "^5.4.0",
      "vite": "^6.0.0"
    }
  }
  ```

- [ ] Create `apps/web/tsconfig.json`:

  ```json
  {
    "extends": "../../packages/app/tsconfig.json",
    "compilerOptions": {
      "composite": true,
      "outDir": "./dist",
      "rootDir": "./src",
      "paths": {
        "@/*": ["../../packages/app/src/*"]
      }
    },
    "include": ["src"],
    "references": [
      { "path": "../../packages/app" }
    ]
  }
  ```

- [ ] Create `apps/web/vite.config.ts`:

  ```typescript
  import { defineConfig } from 'vite';
  import react from '@vitejs/plugin-react';
  import path from 'path';

  export default defineConfig({
    base: '/',
    plugins: [react()],
    resolve: {
      alias: {
        '@': path.resolve(__dirname, '../../packages/app/src'),
      },
    },
    server: {
      port: 5174,
    },
    build: {
      outDir: 'dist',
    },
  });
  ```

- [ ] Create `apps/web/index.html`:

  ```html
  <!doctype html>
  <html lang="en">
    <head>
      <meta charset="UTF-8" />
      <meta name="viewport" content="width=device-width, initial-scale=1.0" />
      <title>TMap</title>
    </head>
    <body>
      <div id="root"></div>
      <script type="module" src="/src/main.tsx"></script>
    </body>
  </html>
  ```

- [ ] Create `apps/web/.env.example`:

  ```
  # TMap Web App — environment variables
  # Copy this file to .env (gitignored) and fill in your values.

  # Base URL of the TMap API. In local dev this points to the locally-running .NET API.
  # For production, replace with your deployed API URL (e.g. https://api.yourdomain.com).
  VITE_API_BASE_URL=http://localhost:5120
  ```

- [ ] Create `apps/web/.env` (local only, gitignored):

  ```
  VITE_API_BASE_URL=http://localhost:5120
  ```

- [ ] Verify `.gitignore` at repo root or `apps/web/` ignores `.env` (but not `.env.example`). Add `apps/web/.env` to root `.gitignore` if needed:

  ```
  apps/web/.env
  ```

- [ ] Create `apps/web/src/WebPlatform.ts`:

  ```typescript
  import type { Platform, AppChannel, AuthTokenResponse } from '@tmap/app/src/platform/Platform';

  const API_BASE_URL: string =
    (import.meta.env.VITE_API_BASE_URL as string | undefined) ?? 'http://localhost:5120';

  export const WebPlatform: Platform = {
    capabilities: {
      tray: false,
      focusWidgetWindow: false,
      autoLaunch: false,
      dataPort: false,
    },

    auth: {
      /**
       * Web: calls /auth/refresh with credentials:'include' (sends the httpOnly cookie)
       * and the X-Tmap-Refresh CSRF header (required by the API, spec §0.5).
       * Returns AuthTokenResponse on success; null on 401 (session expired) or network error.
       * Note: a network error does NOT clear the session (bootstrap network-vs-401 distinction).
       */
      async refreshAndGetAccess(): Promise<AuthTokenResponse | null> {
        try {
          const res = await fetch(`${API_BASE_URL}/api/v1/auth/refresh`, {
            method: 'POST',
            credentials: 'include',
            headers: {
              'Content-Type': 'application/json',
              'X-Tmap-Refresh': '1',
            },
            body: JSON.stringify({}),
          });
          if (!res.ok) return null; // 401 → null → authStore goes anonymous
          const data: any = await res.json();
          return { accessToken: data.accessToken, expiresIn: data.expiresIn, user: data.user };
        } catch {
          // Network failure: return a sentinel so bootstrap can distinguish from 401.
          // authStore treats null as "could not reach server" when status was loading.
          return null;
        }
      },
      async clear(): Promise<void> {
        // Cookie is cleared by calling /auth/logout (done by authStore.logout, not here).
        // This method is a no-op on web — the cookie is httpOnly and cannot be cleared from JS.
      },
    },

    notify(title: string, body: string): void {
      if ('Notification' in window && Notification.permission === 'granted') {
        new Notification(title, { body });
      }
      // If permission not granted, silently drop — web notifications are best-effort.
    },

    // Web has no IPC channels; on/off are no-ops.
    on(_channel: AppChannel, _cb: (...a: any[]) => void): void {},
    off(_channel: AppChannel, _cb: (...a: any[]) => void): void {},

    // focusWidget and autoLaunch absent on web (capabilities flags gate all UI for them).
  };
  ```

- [ ] Create `apps/web/src/main.tsx`:

  ```typescript
  import React from 'react';
  import ReactDOM from 'react-dom/client';
  import { createTmapClient } from '@tmap/api-client';
  import { HttpDataClient } from '@tmap/app/src/data/HttpDataClient';
  import { refreshClient } from '@tmap/app/src/auth/refreshClient';
  import { useAuthStore } from '@tmap/app/src/auth/authStore';
  import { AppRoot } from '@tmap/app/src/AppRoot';
  import { WebPlatform } from './WebPlatform';
  // Import shared styles from packages/app
  import '@tmap/app/src/index.css';

  const API_BASE_URL: string =
    (import.meta.env.VITE_API_BASE_URL as string | undefined) ?? 'http://localhost:5120';

  // Build the typed HTTP client with refresh-on-401 wrapping.
  // credentials:'include' is required for the web refresh cookie to be sent.
  const rawClient = createTmapClient({
    baseUrl: API_BASE_URL,
    credentials: 'include',
    getAccessToken: () => useAuthStore.getState().accessToken ?? undefined,
  });

  const tmapClient = refreshClient(rawClient, {
    onRefresh: () => WebPlatform.auth.refreshAndGetAccess(),
    onLogout: () => useAuthStore.getState().logout(),
  });

  const dataClient = new HttpDataClient(tmapClient);

  ReactDOM.createRoot(document.getElementById('root')!).render(
    <React.StrictMode>
      <AppRoot dataClient={dataClient} platform={WebPlatform} />
    </React.StrictMode>,
  );
  ```

  > Note: `refreshClient`, `useAuthStore`, `AppRoot`, `HttpDataClient` are all provided by previous phases (Q1–Q5). This file is intentionally thin — it only wires the seams.

- [ ] Run `npm install` from the repo root to link the new `@tmap/web` workspace package:
  ```
  npm install
  ```
- [ ] Build check: `npm run build --workspace @tmap/web` (expects TypeScript errors only if `packages/app` source is not yet complete — this task focuses on structure; full build gate is Q6-10).
- [ ] Commit: `feat(web): scaffold apps/web package with WebPlatform, main.tsx, vite config, and .env.example`

---

### Task Q6-6: Wire `apps/desktop/src/main.tsx` — replace legacy App mount with AppRoot

**Why:** The existing `apps/desktop/src/main.tsx` renders the legacy `App` component. It must now build `DesktopPlatform + HttpDataClient` and render `AppRoot`, exactly as `apps/web/src/main.tsx` does but for Electron.

- [ ] Replace `apps/desktop/src/main.tsx` completely:

  ```typescript
  import React from 'react';
  import ReactDOM from 'react-dom/client';
  import { createTmapClient } from '@tmap/api-client';
  import { HttpDataClient } from '@tmap/app/src/data/HttpDataClient';
  import { refreshClient } from '@tmap/app/src/auth/refreshClient';
  import { useAuthStore } from '@tmap/app/src/auth/authStore';
  import { AppRoot } from '@tmap/app/src/AppRoot';
  import { DesktopPlatform } from './DesktopPlatform';
  import { API_BASE_URL } from './config';
  // Shared styles from packages/app
  import '@tmap/app/src/index.css';

  // Desktop: no credentials:'include' needed for the refresh cookie path.
  // The refresh token lives in the main process (safeStorage); only the access token
  // enters the renderer. A null Origin from file:// never triggers CORS — the API
  // confirms no Origin header from Electron is blocked (see docs/sp2 §0.6).
  const rawClient = createTmapClient({
    baseUrl: API_BASE_URL,
    getAccessToken: () => useAuthStore.getState().accessToken ?? undefined,
  });

  const tmapClient = refreshClient(rawClient, {
    onRefresh: () => DesktopPlatform.auth.refreshAndGetAccess(),
    onLogout: () => useAuthStore.getState().logout(),
  });

  const dataClient = new HttpDataClient(tmapClient);

  ReactDOM.createRoot(document.getElementById('root')!).render(
    <React.StrictMode>
      <AppRoot dataClient={dataClient} platform={DesktopPlatform} />
    </React.StrictMode>,
  );
  ```

- [ ] In `apps/desktop/vite.config.ts`, update the `@/` alias to resolve through `packages/app/src` so shared components still work (the existing alias `'@': path.resolve(__dirname, './src')` must become `'@': path.resolve(__dirname, '../../packages/app/src')`):

  ```typescript
  resolve: {
    alias: {
      '@': path.resolve(__dirname, '../../packages/app/src'),
    },
  },
  ```

- [ ] In `apps/desktop/package.json`, add `"@tmap/app": "*"` and `"@tmap/api-client": "*"` to `dependencies`. Remove `"sql.js"` and `"uuid"` from `dependencies` (they are now dormant — kept in repo but not bundled by the renderer). Keep `electron`, `electron-builder`, and all UI deps.
- [ ] In `apps/desktop/electron/main.ts`, remove the data service imports and calls (the ~9 import lines for `initDatabase`, `TaskService`, `ProjectService`, `NoteService`, `FocusSessionService`, `DailyPlanService`, `ReportService`, `seedDemoData`, and their `app.whenReady` initialization block and the `setInterval` notification scheduler). Also remove the `data:export` and `data:import` IPC handlers and all the data `ipcMain.handle(...)` blocks (`tasks:*`, `subtasks:*`, `projects:*`, `noteGroups:*`, `notes:*`, `recurrence:*`, `focusSessions:add`, `dailyPlans:*`, `reports:getData`). Keep: `app:getVersion`, `app:showNotification`, `app:getAutoLaunch`, `app:setAutoLaunch`, `settings:get`, `settings:save`, focus widget IPC (`focus:updateTray`, `focus:showWidget`, `focus:hideWidget`, `focus:widgetState`, `focus:widgetAction`), tray, `secureStore:*` (added in Q6-2).
- [ ] In `apps/desktop/electron/preload.ts`, remove the data-related `window.api.*` entries (tasks, subtasks, projects, noteGroups, notes, recurrence, focusSessions, dailyPlans, reports, data). Keep: `app`, `settings`, `focus`, `on`, `off`, `removeAllListeners`. The `secureStore` bridge (added in Q6-2) is already a separate `contextBridge.exposeInMainWorld` call — leave it intact.
- [ ] Build check: `npm run build --workspace tmap` (desktop build). TypeScript may emit errors from components still importing `window.api.*` for data calls — those are resolved as `packages/app` provides the data layer. The build gate for both apps is Q6-10.
- [ ] Commit: `feat(desktop): wire AppRoot with DesktopPlatform + HttpDataClient; remove legacy data IPC`

---

### Task Q6-7: Copy/link shared CSS and Tailwind config into apps/web

**Why:** `packages/app/src/index.css` and `packages/app/tailwind.config.js` define the `surface-*` custom colors and dark-mode class. The web app must pick them up identically.

- [ ] In `apps/web/vite.config.ts`, confirm there is no explicit CSS config needed beyond the import in `main.tsx` (`import '@tmap/app/src/index.css'` — Vite resolves workspace packages).
- [ ] Create `apps/web/tailwind.config.js` that extends the shared config:

  ```javascript
  /** @type {import('tailwindcss').Config} */
  export default {
    // Inherit all custom colors and plugins from the shared app config.
    presets: [require('../../packages/app/tailwind.config.js')],
    // Scan web-specific files AND shared packages/app source.
    content: [
      './src/**/*.{ts,tsx}',
      '../../packages/app/src/**/*.{ts,tsx}',
    ],
    darkMode: 'class',
  };
  ```

- [ ] Create `apps/web/postcss.config.js`:

  ```javascript
  export default {
    plugins: {
      tailwindcss: {},
      autoprefixer: {},
    },
  };
  ```

- [ ] In `apps/web/package.json`, add `tailwindcss`, `autoprefixer`, and `postcss` to `devDependencies`:

  ```json
  "tailwindcss": "^3.4.17",
  "autoprefixer": "^10.4.20",
  "postcss": "^8.4.49"
  ```

- [ ] Run `npm install` from repo root to pick up the new dev deps.
- [ ] Manual verify: in `apps/web` dev server, background is `surface-950` dark. Open DevTools → computed background color is not the browser default white.
- [ ] Commit: `feat(web): configure Tailwind to extend shared packages/app config and scan app source`

---

### Task Q6-8: Auth bootstrap + store token persistence (desktop: store refresh token after login/register)

**Why:** When the user logs in or registers on desktop, `authStore` receives the access token in memory. But the refresh token must be handed to `main` via `secureStore:setRefreshToken` so `DesktopPlatform.auth.refreshAndGetAccess()` works on next launch. The `authStore` (built in a previous phase) needs a hook for this.

- [ ] In `packages/app/src/auth/authStore.ts` (from prior phase), confirm that after a successful `login` or `register` the store calls `platform.auth.storeRefreshToken?.(refreshToken)` — or, if the auth responses no longer return the raw refresh token to the renderer (on desktop, the token pair already goes through main for the `secureStore:refreshAndGetAccess` handler), document how the token reaches `safeStorage`:

  **Correct flow for desktop login:**
  1. `authStore.login(email, password)` calls `POST /auth/login` via the typed client.
  2. The response includes `AuthTokenResponse { accessToken, expiresIn, user }` (Q0 typed the response). On desktop, the response also includes `refreshToken` in the body (server sends it because the request did not come with a cookie-capable browser). The `authStore` must hand the refresh token to main via `(window as any).secureStore.setRefreshToken(data.refreshToken)`.

  - [ ] Add an optional `storeRefreshToken?(token: string): Promise<void>` to the `Platform.auth` interface in `packages/app/src/platform/Platform.ts`.
  - [ ] Implement it in `DesktopPlatform.auth`:
    ```typescript
    async storeRefreshToken(token: string): Promise<void> {
      await (window as any).secureStore.setRefreshToken(token);
    },
    ```
  - [ ] In `WebPlatform.auth`, the method is absent (web uses httpOnly cookie — no JS-visible refresh token to store):
    ```typescript
    // storeRefreshToken not implemented on web (cookie is set by the server, not JS)
    ```
  - [ ] In `authStore` `login` and `register` actions, after successful response: call `platform.auth.storeRefreshToken?.(data.refreshToken)` if `data.refreshToken` is present.

- [ ] Verify the `authStore.bootstrap()` flow:
  - Desktop: calls `platform.auth.refreshAndGetAccess()` → IPC to main → reads encrypted token from disk → calls `/auth/refresh` in main process → returns `{ accessToken, expiresIn, user }` → store sets `status: 'authed'`.
  - Web: calls `platform.auth.refreshAndGetAccess()` → fetch with `credentials:'include'` + `X-Tmap-Refresh: 1` → returns access token → same store logic.
  - **Network failure (not 401):** both hosts return `null` from `refreshAndGetAccess`; `bootstrap` must distinguish "null because 401" from "null because network error". The `Platform.auth` contract does not distinguish — instead, `authStore.bootstrap` catches `null` and sets `status: 'anonymous'` without clearing the platform token (so the next retry attempt can still succeed). Document this behavior with a code comment.

- [ ] Manual verify: log in on desktop, quit the app, relaunch — user is already signed in (bootstrap reads the stored refresh token).
- [ ] Commit: `feat(auth): add storeRefreshToken to Platform.auth; desktop stores refresh token in safeStorage on login`

---

### Task Q6-9: Implement `Notification.requestPermission` in web app startup

**Why:** `WebPlatform.notify` is a no-op when permission is not granted. Request permission at app startup (after the user is authed) so reminders actually fire on web.

- [ ] In `packages/app/src/AppRoot.tsx` (or the component that renders when `status === 'authed'`), add a one-time effect that requests notification permission on web:

  ```typescript
  useEffect(() => {
    if ('Notification' in window && Notification.permission === 'default') {
      Notification.requestPermission().catch(() => {});
    }
  }, []); // runs once when the authed app mounts
  ```

  This is host-agnostic (on desktop, Electron's `Notification` is not `window.Notification`, so the `'Notification' in window` guard prevents double-requesting).

- [ ] Manual verify on web: first load after login shows the browser notification permission prompt.
- [ ] Commit: `feat(web): request Notification permission on app mount for web reminder delivery`

---

### Task Q6-10: Full build gate — both apps build clean

**Why:** Before the manual integration tests, verify that both `apps/desktop` and `apps/web` emit zero TypeScript errors and Vite bundles complete. This is the hard gate from spec §1.

- [ ] From `apps/desktop`, run the build:
  ```
  npm run build --workspace tmap
  ```
  Fix any TypeScript errors. Common issues to watch for:
  - Any `window.api.*` data calls that are still imported in component files (should be gone once `packages/app` store uses `dataClient`).
  - Missing type declarations for `window.secureStore` — add `declare global { interface Window { secureStore: { ... } } }` to `apps/desktop/src/DesktopPlatform.ts` or a `.d.ts` file.
  - `sql.js` / `uuid` still in externals list of `vite.config.ts` `rollupOptions.external` — safe to leave (external means "don't bundle"; if the import is removed from main.ts the bundler won't look for it anyway).

- [ ] From `apps/web`, run the build:
  ```
  npm run build --workspace @tmap/web
  ```
  Fix any TypeScript errors. Common issues:
  - `tailwindcss` `presets` reference failing — if `packages/app/tailwind.config.js` is CJS and `apps/web/tailwind.config.js` is ESM, change `require(...)` to an `import` and use `export default { presets: [sharedConfig] }`.
  - Missing `index.css` — confirm `packages/app/src/index.css` exists (from the monorepo extraction phase Q1).

- [ ] Run backend build + tests:
  ```
  dotnet build backend/Tmap.sln
  dotnet test backend/Tmap.sln
  ```
  Fix any regression. (This should be green from Q0.)

- [ ] Run `packages/app` unit tests:
  ```
  npm test --workspace @tmap/app
  ```
  (Mappers, `rankBetween`, `refreshClient`, `authStore`, `projectName` selector — all from prior phases.)

- [ ] Commit: `chore: build-gate fix — resolve TypeScript errors in desktop + web prod builds`

---

### Task Q6-11: Manual integration gate — environment startup

**Why:** Verifies the full end-to-end path before the scenario tests.

- [ ] **Start the database:**
  ```
  docker compose up -d postgres
  ```
  Wait for `tmap-postgres` to be healthy (`docker compose ps`).

- [ ] **Start the API:**
  ```
  cd backend/src/Tmap.Api && dotnet run
  ```
  Confirm it listens on `http://localhost:5120` (or the configured port) and logs "Application started".

- [ ] **Start the web app:**
  ```
  npm run dev --workspace @tmap/web
  ```
  Confirm it binds to `http://localhost:5174`.

- [ ] **Start the desktop app:**
  ```
  npm run dev --workspace tmap
  ```
  Confirm the Electron window opens without a white screen of death.

- [ ] **Verify CORS:** open `http://localhost:5174` in the browser, open DevTools Network tab, click "Register" — confirm no CORS error on `POST /api/v1/auth/register`.

- [ ] **Verify desktop Origin:** in the desktop app, open DevTools (`Ctrl+Shift+I`), Network tab — observe that `POST /api/v1/auth/register` from Electron either has no `Origin` header or `Origin: file://`. Confirm the request succeeds (API does not block requests without an `Origin` header — only browsers enforce CORS, so Electron's `fetch` in the renderer is browser-based but `Origin` handling varies by Electron version). If a CORS rejection occurs, route the desktop HTTP calls through the main process `net` module and document the change.

  > **Documented decision for desktop CORS:** Electron 33 renderer uses Chromium's `fetch` and sends `Origin: null` for `file://`-loaded pages. The TMap API's CORS policy only grants access when Origin is in the allowlist; `null` does not match. Mitigation: the desktop app must either (a) load from a local `http://` URL in dev (Vite already does this — `VITE_DEV_SERVER_URL` is `http://localhost:5173`), which sends a real Origin that should be in the allowlist, or (b) in production (`file://` load), route data calls through the Electron main process `net` module which bypasses CORS. In SP2, desktop dev uses the Vite dev server URL so Origin is `http://localhost:5173` (already in the allowlist); production file-mode is handled by routing through main. Document this in `apps/desktop/src/DesktopPlatform.ts` and in a code comment in `apps/desktop/electron/main.ts`.

- [ ] Commit: `docs(desktop): document desktop CORS/Origin handling — dev uses Vite origin, prod routes via main net`

---

### Task Q6-12: Manual integration gate — auth scenarios

**Step-by-step test script.** Each sub-step is a checkbox.

- [ ] **Register on web:** open `http://localhost:5174`, complete registration form with `test@example.com` / `Password1!`. Confirm redirect to the main app (no error toast). Open DevTools → Application → Cookies: confirm `refreshToken` httpOnly cookie is present on `localhost:5174`.

- [ ] **Login on desktop with the same account:** in the Electron window, go to login screen, enter `test@example.com` / `Password1!`. Confirm the app loads. Check that after restart (quit + relaunch), the user is still signed in (bootstrap works).

- [ ] **Stay signed in — web:** refresh `http://localhost:5174` (F5). Confirm the app loads directly without showing the login screen (bootstrap calls `/auth/refresh` with the cookie and recovers the session).

- [ ] **Token expiry simulation — web:** set `AccessTokenMinutes: 1` in `appsettings.Development.json`, restart the API, wait 1 minute, then perform a task update. Confirm the update succeeds (the `refreshClient` intercepted the 401, obtained a new token, and retried). Revert `AccessTokenMinutes` to 15 afterward.

- [ ] **Logout — web:** click Logout. Confirm the login screen is shown. Confirm the `refreshToken` cookie is cleared (DevTools → Application → Cookies shows it absent). Confirm that a manual call to `GET /api/v1/tasks` with the old access token returns 401 within 15 min (the JWT is still valid until expiry — this is the known limitation; logout only invalidates the refresh token, not the access token in SP2).

- [ ] **Logout — desktop:** log in on desktop, click Logout. Confirm the login screen shows. Relaunch the app — confirm the login screen is shown again (refresh token was cleared from safeStorage).

- [ ] Commit: `test(e2e): auth scenarios green — register, login, stay-signed-in, refresh-on-401, logout`

---

### Task Q6-13: Manual integration gate — full CRUD round-trip

**Test script: create on web, verify on desktop, and vice versa.**

- [ ] **Tasks — create:** on web, create a task "Test cross-client task" with status `inbox`. Open desktop (logged in same account), click Reload / navigate away and back — confirm the task appears.

- [ ] **Tasks — update:** on desktop, change the task title to "Updated title", set status to `planned`, add a `plannedDate`. Refresh the web app — confirm both changes are visible.

- [ ] **Tasks — complete:** on web, mark the task `done`. On desktop — confirm `done` state.

- [ ] **Tasks — delete:** on desktop, delete the task. On web — confirm it is gone.

- [ ] **Subtasks:** create a task on web, add two subtasks via the detail dialog, check the first one. On desktop — confirm both subtasks appear with the correct completed state.

- [ ] **Projects — create with project picker:** on web, create a project "Test Project" (color + emoji). Create a task and assign it to "Test Project" via the project picker (`TaskDetailDialog`). On desktop — confirm the task shows the correct project name and color.

- [ ] **Projects — update + reorder:** on web, rename the project. On desktop — confirm the new name. Drag projects to reorder them on desktop — confirm order is preserved after refresh (rank→order mapping works).

- [ ] **Notes + NoteGroups:** create a note group and a note on web. On desktop — confirm both appear. Edit the note content on desktop — confirm on web.

- [ ] **Recurring task — create and verify full instances:** on web, create a daily recurring task starting today for 5 days. Confirm that the `ensure-instances` call returns full `TaskResponse[]` (no stubs — task items show titles and all fields, not empty objects). On desktop — confirm the same 5 instances appear.

- [ ] **Focus session — log and verify reports:** on desktop, start a focus session for 5 minutes on a task, stop it. Navigate to Reports → confirm the session appears in the focus minutes and the project breakdown.

- [ ] **Daily plan — commit:** on web, go through the planning flow (Review → Triage → Timebox → Commit). Confirm the `dailyPlans.upsert` call succeeds. On desktop — navigate to today's plan; confirm the committed task list matches.

- [ ] **Reorder — tasks:** on desktop, drag-reorder three tasks in the task list. Refresh the web app — confirm the same order (rank↔order mapping round-trips correctly).

- [ ] Commit: `test(e2e): full CRUD round-trip green — tasks, subtasks, projects, notes, recurrence, focus, daily-plan, reorder`

---

### Task Q6-14: Manual integration gate — desktop-only feature gating on web

**Verify that web correctly hides capabilities it does not have.**

- [ ] **Tray:** on web, confirm there is no tray icon reference, no "Minimize to tray" option, and no tray-related UI. The `capabilities.tray: false` flag must gate these UI elements.

- [ ] **Focus widget window:** on web, start a focus session. Confirm the focus overlay appears _in-app_ (no attempt to open a separate OS window). The "Show as floating widget" button (if present in `FocusModeOverlay`) must be hidden when `capabilities.focusWidgetWindow === false`.

- [ ] **Auto-launch:** open Settings on web. Confirm the "Launch at startup" toggle is absent (gated by `capabilities.autoLaunch === false` and `platform.autoLaunch === undefined`).

- [ ] **Data export/import:** confirm the Data section in Settings is hidden (deferred per spec §7 — the export/import UI is hidden in SP2 for all clients, not just web). If the desktop SettingsDialog still shows the export button, that is a separate cleanup: remove or disable it with a comment referencing the SP2 deferral.

- [ ] Commit: `feat(web): confirm desktop-only features (tray, widget-window, auto-launch, data-export) are hidden on web`

---

### Task Q6-15: Manual integration gate — reminders fire via `platform.notify`

**Why:** The old notification scheduler (`setInterval` over the local DB) has been removed from `electron/main.ts`. The client-side `reminderScheduler.ts` (from `packages/app`, prior phase) must fire instead.

- [ ] **Desktop:** schedule a task 2 minutes from now with a 1-minute reminder. Confirm the Electron `Notification` appears at the correct time (fired via `DesktopPlatform.notify` → `window.api.app.showNotification`).

- [ ] **Web:** grant notification permission. Schedule a task 2 minutes from now with a 1-minute reminder. Confirm the browser `Notification` appears at the correct time.

- [ ] Confirm: if the same account is open in two clients simultaneously (desktop + web), two notifications arrive — that is the known SP2 behavior (spec §7 note: "reminder timer duplication across two open clients is acceptable in SP2").

- [ ] Commit: `test(e2e): client-side reminder scheduler fires via platform.notify on both desktop and web`

---

### Task Q6-16: Manual integration gate — offline-error banner

**Why:** spec §7 requires a clear non-destructive banner when the server is unreachable.

- [ ] Stop the API: `Ctrl+C` in the dotnet run terminal (or `docker compose stop` if running containerized).

- [ ] **Web:** attempt to create a task. Confirm a visible "Couldn't reach the server" banner or toast appears. Confirm the task list is not corrupted or cleared (non-destructive — the existing in-memory state remains).

- [ ] **Desktop:** same test. Confirm the banner appears.

- [ ] Restart the API. Confirm the banner dismisses on next successful request (or on page refresh for web). No data was lost.

- [ ] Confirm: auth failures (the `refreshClient` returns a 401-chain that leads to logout) show a "Session expired, please log in again" message rather than a generic network error (these are distinct code paths).

- [ ] Commit: `test(e2e): offline-error banner shows on server unreachable; non-destructive`

---

### Task Q6-17: Final acceptance criteria checklist

Run through spec §10 line by line and check every item off before closing the branch.

- [ ] **§10.1** — Q0 backend touch-ups done; `packages/api-client` regenerated; backend suite green. Verify: `dotnet test backend/Tmap.sln` → all pass.

- [ ] **§10.2** — `packages/app` extracted; both `apps/desktop` and `apps/web` build and run. Verify:
  ```
  npm run build --workspace tmap
  npm run build --workspace @tmap/web
  ```
  Both exit 0.

- [ ] **§10.3** — Register + login from both desktop and web against the API. Covered by Q6-12.

- [ ] **§10.4** — Full CRUD persists to the cloud and is identical across both clients; reorder works (order↔rank); recurrence instances render as full tasks (no stubs). Covered by Q6-13.

- [ ] **§10.5** — `projectId` end-to-end; display names resolved; no `.project` name-string left in the client. Verify: `grep -r '\.project\b' packages/app/src apps/desktop/src apps/web/src` returns zero hits (excluding `FocusSession.project` which is a string by spec design, and `ReportData.completedTasks[].project` which is also intentionally name-based per spec).

- [ ] **§10.6** — Auth: refresh-on-401 (single-flight, idempotent retry), stay-signed-in, logout (abort in-flight), bootstrap network-vs-401 — all correct on both hosts; no token in JS-readable web storage. Covered by Q6-12 and Q6-8. Verify: DevTools → Application → LocalStorage / SessionStorage on web contains no `refreshToken` key.

- [ ] **§10.7** — Reminders fire from client timer via `platform.notify`; desktop-only features adapted/hidden on web; settings split works. Covered by Q6-14 and Q6-15.

- [ ] **§10.8** — Graceful non-destructive error when the server is unreachable. Covered by Q6-16.

- [ ] **§10.9** — Unit tests pass. Verify: `npm test --workspace @tmap/app` → all pass.

- [ ] Once all boxes are checked, create the final commit:
  ```
  git commit -m "$(cat <<'EOF'
  feat(sp2/Q6): complete web+desktop wiring — integration gate green, all §10 AC checked

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  EOF
  )"
  ```

---

### Task Q6-18: Produce `.env.example` at repo root (desktop-facing)

**Why:** The desktop dev experience requires `TMAP_API_BASE_URL` in the main process environment and `VITE_API_BASE_URL` for the renderer. A root `.env.example` documents both alongside the existing Postgres vars.

- [ ] Read the existing root `.env.example` if present; if absent, create it:

  ```
  # TMap Monorepo — local dev environment
  # Copy to .env (gitignored) and fill in values.

  # PostgreSQL (consumed by docker-compose.yml)
  POSTGRES_USER=tmap
  POSTGRES_PASSWORD=changeme
  POSTGRES_DB=tmap

  # .NET API connection string — set in backend/src/Tmap.Api/appsettings.Development.json
  # or via user-secrets: dotnet user-secrets set "ConnectionStrings:Default" "..."

  # Desktop renderer: API base URL (VITE_ prefix = exposed to renderer via import.meta.env)
  VITE_API_BASE_URL=http://localhost:5120

  # Desktop main process: API base URL for secureStore:refreshAndGetAccess IPC handler
  # Set this as an OS env var or in a .env loaded by the electron main process.
  TMAP_API_BASE_URL=http://localhost:5120
  ```

- [ ] Verify `.gitignore` at root includes `.env` (not `.env.example`).
- [ ] Commit: `docs: add root .env.example documenting desktop API base URL env vars`
