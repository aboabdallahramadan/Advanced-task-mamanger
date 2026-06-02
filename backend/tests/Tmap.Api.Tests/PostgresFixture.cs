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
