using Microsoft.EntityFrameworkCore;
using Npgsql;
using Tmap.Api.Common;
using Tmap.Api.Infrastructure;
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

    // The non-superuser, non-table-owner role the application connects as. Postgres bypasses
    // RLS (even FORCE) for superusers and table owners, so the cross-tenant isolation guarantee
    // (RLS — not EF — blocks other tenants' rows) is only real when the app connects as a plain
    // NOSUPERUSER/NOBYPASSRLS login that does NOT own the tables. The container's default 'tmap'
    // role is a superuser, so it is used only to apply migrations and grant privileges below.
    private const string AppRole = "app_user";
    private const string AppRolePassword = "app_user_pw";

    /// <summary>
    /// Runtime connection string used by the application and tests. Authenticates as the
    /// NON-SUPERUSER <see cref="AppRole"/> so RLS <c>FORCE</c> policies actually apply. Npgsql
    /// multiplexing is off so RLS <c>app.user_id</c> stays pinned to the request connection.
    /// </summary>
    public string ConnectionString { get; private set; } = string.Empty;

    public async Task InitializeAsync()
    {
        await _container.StartAsync();

        // The container's default credentials belong to the superuser 'tmap' (table owner).
        var adminConnectionString = _container.GetConnectionString();

        // 1) Apply migrations as the owner/superuser so DDL (sequences, triggers, ENABLE/FORCE RLS,
        //    policies) succeeds. The app role created below intentionally cannot run this DDL.
        await ApplyMigrationsAsync(adminConnectionString);

        // 2) Provision the non-superuser app role and grant it DML access (but not ownership, not
        //    superuser, not BYPASSRLS) so every query it runs is subject to the RLS policies.
        await ProvisionAppRoleAsync(adminConnectionString);

        // 3) Hand out a connection string that authenticates as the non-superuser app role.
        ConnectionString = new NpgsqlConnectionStringBuilder(adminConnectionString)
        {
            Username = AppRole,
            Password = AppRolePassword,
        }.ConnectionString;
    }

    public async Task DisposeAsync()
    {
        await _container.DisposeAsync();
    }

    private static async Task ApplyMigrationsAsync(string adminConnectionString)
    {
        var options = new DbContextOptionsBuilder<AppDbContext>()
            .UseNpgsql(adminConnectionString)
            .UseSnakeCaseNamingConvention()
            .Options;

        await using var ctx = new AppDbContext(options, new SystemCurrentUser());
        await ctx.Database.MigrateAsync();
    }

    private static async Task ProvisionAppRoleAsync(string adminConnectionString)
    {
        await using var connection = new NpgsqlConnection(adminConnectionString);
        await connection.OpenAsync();

        await using var cmd = connection.CreateCommand();
        cmd.CommandText = $@"
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '{AppRole}') THEN
        CREATE ROLE {AppRole} LOGIN PASSWORD '{AppRolePassword}' NOSUPERUSER NOBYPASSRLS;
    END IF;
END
$$;

GRANT CONNECT ON DATABASE tmap_test TO {AppRole};
GRANT USAGE ON SCHEMA public TO {AppRole};
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO {AppRole};
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO {AppRole};
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO {AppRole};
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO {AppRole};";
        await cmd.ExecuteNonQueryAsync();
    }
}
