using FluentAssertions;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Npgsql;
using Testcontainers.PostgreSql;
using Tmap.Api.Common;
using Tmap.Api.Infrastructure;
using Tmap.Api.Infrastructure.Persistence;
using Xunit;

namespace Tmap.Api.Tests;

/// <summary>
/// Verifies the C5 startup bootstrap (DbBootstrapper.RunAsync). Uses its OWN standalone
/// postgres:16 container where app_user is NOT pre-provisioned (unlike PostgresFixture, which
/// provisions it in InitializeAsync) so RunAsync actually creates it. Not in the "db" collection:
/// it must own a pristine container the bootstrapper bootstraps from scratch.
/// </summary>
public sealed class DbBootstrapperTests : IAsyncLifetime
{
    private const string AppUserPassword = "boot_app_user_pw_url-safe.123";

    private readonly PostgreSqlContainer _container = new PostgreSqlBuilder()
        .WithImage("postgres:16")
        .WithDatabase("tmap_boot_test")
        .WithUsername("tmap_owner")
        .WithPassword("tmap_owner_pw")
        .Build();

    // The owner/privileged connection string Coolify's default DB role uses (= Migrator role).
    private string MigratorConnectionString => _container.GetConnectionString();

    // The runtime connection string authenticating as the app_user RunAsync provisions.
    private string AppUserConnectionString =>
        new NpgsqlConnectionStringBuilder(MigratorConnectionString)
        {
            Username = DbBootstrapper.AppRole,
            Password = AppUserPassword,
        }.ConnectionString;

    public Task InitializeAsync() => _container.StartAsync();

    public Task DisposeAsync() => _container.DisposeAsync().AsTask();

    private IConfiguration BootstrapConfig() =>
        new ConfigurationBuilder()
            .AddInMemoryCollection(new Dictionary<string, string?>
            {
                ["ConnectionStrings:Migrator"] = MigratorConnectionString,
                ["Db:AppUserPassword"] = AppUserPassword,
            })
            .Build();

    [Fact]
    public async Task Unset_Migrator_Is_A_NoOp()
    {
        // No ConnectionStrings:Migrator -> bootstrap does nothing (no migrations, no role).
        var config = new ConfigurationBuilder()
            .AddInMemoryCollection(new Dictionary<string, string?>())
            .Build();

        var act = async () => await DbBootstrapper.RunAsync(config);
        await act.Should().NotThrowAsync("an unset Migrator key must be a clean no-op");

        // The owner connection still has no app_user and no migrated tables.
        await using var connection = new NpgsqlConnection(MigratorConnectionString);
        await connection.OpenAsync();

        var roleExists = await ScalarBoolAsync(connection,
            $"SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '{DbBootstrapper.AppRole}')");
        roleExists.Should().BeFalse("RunAsync must not provision app_user when Migrator is unset");

        var tasksExists = await ScalarBoolAsync(connection,
            "SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'tasks')");
        tasksExists.Should().BeFalse("RunAsync must not migrate when Migrator is unset");
    }

    [Fact]
    public async Task Migrations_And_AppUser_Are_Provisioned_With_Locked_Down_Attributes()
    {
        await DbBootstrapper.RunAsync(BootstrapConfig());

        await using var connection = new NpgsqlConnection(MigratorConnectionString);
        await connection.OpenAsync();

        // (a) Migrations ran as owner: a core table exists.
        var tasksExists = await ScalarBoolAsync(connection,
            "SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'tasks')");
        tasksExists.Should().BeTrue("RunAsync migrates the schema as the Migrator role");

        // (b) app_user exists and is NOSUPERUSER NOBYPASSRLS (the load-bearing RLS guarantee).
        await using var cmd = connection.CreateCommand();
        cmd.CommandText =
            $"SELECT rolsuper, rolbypassrls, rolcanlogin FROM pg_roles WHERE rolname = '{DbBootstrapper.AppRole}'";
        await using var reader = await cmd.ExecuteReaderAsync();
        (await reader.ReadAsync()).Should().BeTrue("app_user role must exist after RunAsync");
        reader.GetBoolean(0).Should().BeFalse("app_user must be NOSUPERUSER");
        reader.GetBoolean(1).Should().BeFalse("app_user must be NOBYPASSRLS");
        reader.GetBoolean(2).Should().BeTrue("app_user must be able to LOGIN");
    }

    [Fact]
    public async Task AppUser_Connection_Enforces_Cross_Tenant_Rls_Isolation()
    {
        await DbBootstrapper.RunAsync(BootstrapConfig());

        var userA = Guid.NewGuid();
        var userB = Guid.NewGuid();
        var userBProjectId = Guid.NewGuid();

        // Arrange userB's project via the elevated/system id (allowed by the policy OR-clause).
        await using (var elevated = NewAppUserContext(SystemCurrentUser.SystemUserId))
        {
            elevated.Projects.Add(new Tmap.Api.Infrastructure.Entities.Project
            {
                Id = userBProjectId,
                UserId = userB,
                Name = "B-secret-" + Guid.NewGuid().ToString("N"),
                Rank = "a0",
                CreatedAt = DateTimeOffset.UtcNow,
            });
            await elevated.SaveChangesAsync();
        }

        // Probe as userA over the SAME app_user login: RLS (not EF) must hide userB's row even
        // with all query filters bypassed and via raw SQL. This only holds because app_user is
        // NOSUPERUSER and NOT the table owner — i.e. it proves RunAsync's role is locked down.
        await using var asUserA = NewAppUserContext(userA);

        var viaEf = await asUserA.Projects
            .IgnoreQueryFilters([AppDbContext.TenantFilter, AppDbContext.SoftDeleteFilter])
            .Where(p => p.Id == userBProjectId)
            .ToListAsync();
        viaEf.Should().BeEmpty("RLS must block userB's row even with EF filters off");

        var rawCount = await asUserA.Database
            .SqlQuery<int>($"SELECT COUNT(*)::int AS \"Value\" FROM projects WHERE id = {userBProjectId}")
            .SingleAsync();
        rawCount.Should().Be(0, "RLS must block userB's row even for raw SQL as app_user");
    }

    [Fact]
    public async Task RunAsync_Is_Idempotent_Across_Repeated_Deploys()
    {
        // Two runs (simulating two deploys) must succeed with no error and leave exactly one role.
        await DbBootstrapper.RunAsync(BootstrapConfig());
        var act = async () => await DbBootstrapper.RunAsync(BootstrapConfig());
        await act.Should().NotThrowAsync("re-running the bootstrap on redeploy must be a clean no-op");

        await using var connection = new NpgsqlConnection(MigratorConnectionString);
        await connection.OpenAsync();
        var roleCount = await ScalarIntAsync(connection,
            $"SELECT COUNT(*)::int FROM pg_roles WHERE rolname = '{DbBootstrapper.AppRole}'");
        roleCount.Should().Be(1, "app_user must exist exactly once after repeated bootstraps");

        // The synced-in password still authenticates after the ALTER ROLE on the second run.
        await using var appConn = new NpgsqlConnection(AppUserConnectionString);
        var openAsApp = async () => await appConn.OpenAsync();
        await openAsApp.Should().NotThrowAsync("app_user password must remain valid after re-provisioning");
    }

    // An AppDbContext on the app_user (runtime) connection whose app.user_id GUC is pinned to the
    // given id (mirrors IntegrationTestBase.NewElevatedDbContext but over the provisioned role).
    private AppDbContext NewAppUserContext(Guid userId)
    {
        var options = new DbContextOptionsBuilder<AppDbContext>()
            .UseNpgsql(AppUserConnectionString)
            .UseSnakeCaseNamingConvention()
            .Options;

        ICurrentUser currentUser = userId == SystemCurrentUser.SystemUserId
            ? new SystemCurrentUser()
            : new FixedCurrentUser(userId);
        var ctx = new AppDbContext(options, currentUser);

        ctx.Database.OpenConnection();
        ctx.Database.ExecuteSql($"SELECT set_config('app.user_id', {userId.ToString()}, false);");
        return ctx;
    }

    private static async Task<bool> ScalarBoolAsync(NpgsqlConnection connection, string sql)
    {
        await using var cmd = connection.CreateCommand();
        cmd.CommandText = sql;
        return (bool)(await cmd.ExecuteScalarAsync())!;
    }

    private static async Task<int> ScalarIntAsync(NpgsqlConnection connection, string sql)
    {
        await using var cmd = connection.CreateCommand();
        cmd.CommandText = sql;
        return (int)(await cmd.ExecuteScalarAsync())!;
    }
}
