using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Npgsql;
using Tmap.Api.Common;
using Tmap.Api.Infrastructure;

namespace Tmap.Api.Infrastructure.Persistence;

/// <summary>
/// Self-bootstrapping startup step (contract C5). Active ONLY when
/// <c>ConnectionStrings:Migrator</c> is set (prod). When set: applies every EF migration as the
/// privileged owner role, then provisions the locked-down runtime <c>app_user</c>
/// (NOSUPERUSER NOBYPASSRLS) so RLS FORCE policies actually apply to runtime traffic. When unset
/// (local dev, tests) it is a no-op — dev migrations stay manual (<c>dotnet ef database update</c>).
/// Idempotent: safe to re-run on every redeploy.
/// </summary>
public static class DbBootstrapper
{
    /// <summary>The locked-down runtime role provisioned for RLS-enforced traffic.</summary>
    public const string AppRole = "app_user";

    public static async Task RunAsync(IConfiguration configuration)
    {
        var migratorConnectionString = configuration.GetConnectionString("Migrator");
        if (string.IsNullOrWhiteSpace(migratorConnectionString))
        {
            // No Migrator role configured (local dev / tests): do nothing, migrations stay manual.
            return;
        }

        var appUserPassword = configuration["Db:AppUserPassword"];
        if (string.IsNullOrWhiteSpace(appUserPassword))
        {
            throw new InvalidOperationException(
                "ConnectionStrings:Migrator is set but Db:AppUserPassword is missing. Both are required "
                + "to provision the runtime app_user role (it must match ConnectionStrings:Postgres).");
        }

        // The database name is read from the Migrator connection (not hardcoded) so the same code
        // works against any Coolify-managed database name.
        var databaseName = new NpgsqlConnectionStringBuilder(migratorConnectionString).Database
            ?? throw new InvalidOperationException(
                "ConnectionStrings:Migrator must include a Database (e.g. Host=…;Database=…;Username=…;Password=…).");

        // 1) Migrate as the owner/superuser so DDL (sequences, triggers, ENABLE/FORCE RLS, policies)
        //    succeeds. The app_user provisioned below intentionally cannot run this DDL. A short-lived
        //    context built directly on the Migrator connection; disposed before the host serves.
        var options = new DbContextOptionsBuilder<AppDbContext>()
            .UseNpgsql(migratorConnectionString)
            .UseSnakeCaseNamingConvention()
            .Options;

        await using (var migratorContext = new AppDbContext(options, new SystemCurrentUser()))
        {
            await migratorContext.Database.MigrateAsync();
        }

        // 2) Provision the runtime app_user role + grants (ported from PostgresFixture.ProvisionAppRoleAsync).
        //    NOSUPERUSER NOBYPASSRLS, no ownership. Password interpolated into a CREATE/ALTER ROLE
        //    literal (Postgres forbids a bind parameter there) and escaped via quote_literal to stay
        //    injection-safe. USAGE, SELECT on sequences only (nextval needs no UPDATE).
        await using var connection = new NpgsqlConnection(migratorConnectionString);
        await connection.OpenAsync();

        await using var cmd = connection.CreateCommand();
        cmd.CommandText = $@"
DO $do$
DECLARE
    pw text := quote_literal({QuoteLiteral(appUserPassword)});
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '{AppRole}') THEN
        EXECUTE 'CREATE ROLE {AppRole} LOGIN PASSWORD ' || pw || ' NOSUPERUSER NOBYPASSRLS';
    ELSE
        EXECUTE 'ALTER ROLE {AppRole} LOGIN PASSWORD ' || pw || ' NOSUPERUSER NOBYPASSRLS';
    END IF;
END
$do$;

GRANT CONNECT ON DATABASE {QuoteIdentifier(databaseName)} TO {AppRole};
GRANT USAGE ON SCHEMA public TO {AppRole};
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO {AppRole};
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO {AppRole};
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO {AppRole};
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO {AppRole};";
        await cmd.ExecuteNonQueryAsync();
    }

    // Postgres single-quote string literal: double any embedded single quotes, wrap in quotes.
    // The result is a quoted SQL literal; passed to SQL's quote_literal() it is re-escaped so the
    // value reaches CREATE/ALTER ROLE … PASSWORD '…' injection-safe regardless of charset.
    private static string QuoteLiteral(string value) => "'" + value.Replace("'", "''") + "'";

    // Postgres double-quoted identifier (database name): double any embedded double quotes.
    private static string QuoteIdentifier(string identifier) =>
        "\"" + identifier.Replace("\"", "\"\"") + "\"";
}
