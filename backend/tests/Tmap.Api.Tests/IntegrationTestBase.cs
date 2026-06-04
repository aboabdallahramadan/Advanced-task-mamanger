using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Tmap.Api.Common;
using Tmap.Api.Infrastructure;
using Tmap.Api.Infrastructure.Entities;
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
    /// Registers a new user and returns an <see cref="AuthedClient"/> whose
    /// <see cref="AuthedClient.UserId"/> is the new user's id.
    /// CONTRACT SIGNATURE — frozen in P0. P1 minimal implementation: inserts an
    /// <see cref="ApplicationUser"/> row directly via the elevated DbContext (no HTTP, no JWT)
    /// so persistence-layer tests have a real, FK-valid owning user. The carried
    /// <see cref="AuthedClient.Client"/> is the unauthenticated <see cref="Client"/> until P2
    /// wires POST /api/v1/auth/register and attaches a real Bearer token.
    /// </summary>
    protected async Task<AuthedClient> RegisterAsync(
        string? email = null,
        string password = "Password123!x"
    )
    {
        // AspNetUsers (Identity) tables are not RLS-protected, so this insert succeeds
        // regardless of app.user_id. The elevated context already targets the system id.
        await using var ctx = NewElevatedDbContext();

        var userId = Guid.NewGuid();
        var resolvedEmail = email ?? $"user-{userId:N}@test.local";
        var user = new ApplicationUser
        {
            Id = userId,
            UserName = resolvedEmail,
            NormalizedUserName = resolvedEmail.ToUpperInvariant(),
            Email = resolvedEmail,
            NormalizedEmail = resolvedEmail.ToUpperInvariant(),
            EmailConfirmed = true,
            SecurityStamp = Guid.NewGuid().ToString("N"),
            // password is not hashed here; P2 replaces this with the real register endpoint.
            PasswordHash = password,
        };
        ctx.Users.Add(user);
        await ctx.SaveChangesAsync();

        return new AuthedClient(Client, userId);
    }

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
