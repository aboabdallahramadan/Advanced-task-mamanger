using System.Net.Http.Json;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Tmap.Api.Common;
using Tmap.Api.Infrastructure;
using Tmap.Api.Infrastructure.Persistence;
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
    private readonly List<ServiceProvider> _scopedProviders = new();

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
        foreach (var provider in _scopedProviders)
        {
            provider.Dispose();
        }

        Client.Dispose();
        _factory.Dispose();
        return Task.CompletedTask;
    }

    /// <summary>
    /// Resolves an <see cref="AppDbContext"/> whose injected <see cref="ICurrentUser"/> reports
    /// the given authed user's id (an HTTP-style scope, not <see cref="SystemCurrentUser"/>),
    /// configured exactly like <see cref="PersistenceExtensions.AddPersistence(IServiceCollection, string)"/>
    /// so the same connection + ownership interceptors run. The backing provider is tracked and
    /// disposed when the test completes.
    /// </summary>
    protected AppDbContext NewScopedDbContextFor(AuthedClient client)
    {
        var services = new ServiceCollection();
        services.AddSingleton<ICurrentUser>(new FixedCurrentUser(client.UserId));
        services.AddPersistence(_fixture.ConnectionString);
        var provider = services.BuildServiceProvider();
        _scopedProviders.Add(provider);
        return provider.GetRequiredService<AppDbContext>();
    }

    /// <summary>
    /// Registers a new user via POST /api/v1/auth/register, attaches the returned Bearer token
    /// to a fresh <see cref="HttpClient"/>, calls GET /api/v1/auth/me to obtain the canonical
    /// user id, and returns an <see cref="AuthedClient"/> ready for authenticated slice tests.
    /// CONTRACT SIGNATURE — frozen in P0.
    /// </summary>
    protected async Task<AuthedClient> RegisterAsync(
        string? email = null,
        string password = "Password123!x"
    )
    {
        email ??= $"user-{Guid.NewGuid():N}@tmap.test";

        var registerRes = await Client.PostAsJsonAsync("/api/v1/auth/register", new { email, password });
        registerRes.EnsureSuccessStatusCode();
        var pair = await registerRes.Content.ReadFromJsonAsync<TokenPairDto>()
                   ?? throw new InvalidOperationException("register returned no token pair");

        // Fresh HttpClient bound to the same in-process server, with Bearer preset.
        var authedHttp = _factory.CreateClient();
        authedHttp.DefaultRequestHeaders.Authorization =
            new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", pair.AccessToken);

        var meRes = await authedHttp.GetAsync("/api/v1/auth/me");
        meRes.EnsureSuccessStatusCode();
        var me = await meRes.Content.ReadFromJsonAsync<MeDto>()
                 ?? throw new InvalidOperationException("me returned no profile");

        return new AuthedClient(authedHttp, me.Id);
    }

    // Local DTOs for the harness (kept private so they don't leak into slice tests).
    private sealed record TokenPairDto(string AccessToken, string RefreshToken, DateTimeOffset AccessTokenExpiresAt);
    private sealed record MeDto(Guid Id, string Email, string TimeZoneId);

    /// <summary>
    /// A DbContext bound to an elevated current user for arrange/assert in tests. Query filters
    /// (added in P1) are available; the caller passes the owning user id so the EF
    /// <see cref="AppDbContext.TenantFilter"/> resolves to that user's rows. When
    /// <paramref name="userId"/> is null the context behaves as <see cref="SystemCurrentUser"/>.
    /// Caller disposes the returned context.
    /// The connection's <c>app.user_id</c> GUC is always set to the elevated system id so writes
    /// and reads against RLS <c>FORCE</c>-protected synced tables satisfy the
    /// <c>tenant_isolation</c> policy (which explicitly allows the system id) until the P1-8
    /// connection interceptor lands.
    /// </summary>
    protected AppDbContext NewElevatedDbContext(Guid? userId = null)
    {
        var options = new DbContextOptionsBuilder<AppDbContext>()
            .UseNpgsql(_fixture.ConnectionString)
            .UseSnakeCaseNamingConvention()
            .Options;

        // EF tenant filter resolves to the requested user (default: the elevated system id).
        ICurrentUser currentUser =
            userId is { } id ? new FixedCurrentUser(id) : new SystemCurrentUser();
        var ctx = new AppDbContext(options, currentUser);

        // Pin app.user_id to the elevated system id for this context's connection so RLS FORCE
        // does not block cross-user arrange/assert. Session-level SET (not SET LOCAL) persists for
        // the connection's lifetime; multiplexing is off (see PostgresFixture) so it stays pinned.
        ctx.Database.OpenConnection();
        var systemUserId = SystemCurrentUser.SystemUserId.ToString();
        // set_config(..., is_local: false) = session-level GUC; parameterized to avoid EF1002.
        ctx.Database.ExecuteSql(
            $"SELECT set_config('app.user_id', {systemUserId}, false);"
        );

        return ctx;
    }
}

/// <summary>
/// Test-only <see cref="ICurrentUser"/> bound to a fixed, known user id so the elevated
/// DbContext's tenant query filter resolves to a specific user's rows during arrange/assert.
/// </summary>
internal sealed class FixedCurrentUser(Guid id) : ICurrentUser
{
    public bool IsAuthenticated => true;
    public Guid Id { get; } = id;
}

/// <summary>
/// An authenticated client bundle: an <see cref="HttpClient"/> with the Bearer access token
/// already attached, plus the registered user's id. CONTRACT TYPE — shape frozen in P0.
/// </summary>
public sealed record AuthedClient(HttpClient Client, Guid UserId);
