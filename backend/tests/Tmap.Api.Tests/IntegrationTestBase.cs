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

        return new AppDbContext(options, new SystemCurrentUser());
    }
}

/// <summary>
/// An authenticated client bundle: an <see cref="HttpClient"/> with the Bearer access token
/// already attached, plus the registered user's id. CONTRACT TYPE — shape frozen in P0.
/// </summary>
public sealed record AuthedClient(HttpClient Client, Guid UserId);
