using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Extensions.Configuration;

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

        // Migrations are applied once by PostgresFixture as the superuser/table owner (DDL,
        // ENABLE/FORCE RLS, policies). The app connects as a NON-SUPERUSER role that has no
        // CREATE privilege on schema public, so it must NOT attempt Migrate() here (EF's
        // CREATE TABLE IF NOT EXISTS "__EFMigrationsHistory" would fail with permission denied).
    }
}
