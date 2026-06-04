using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.Hosting;
using Serilog;

namespace Tmap.Api.Tests;

public sealed class TmapApiFactory(string connectionString) : WebApplicationFactory<Program>
{
    protected override IHost CreateHost(IHostBuilder builder)
    {
        // Override Serilog to use the in-memory delegating sink so integration tests can
        // assert security events are emitted and no secrets (tokens, passwords) are logged.
        builder.UseSerilog((_, lc) => lc
            .MinimumLevel.Information()
            .WriteTo.Sink(new DelegatingSink(evt =>
            {
                var sw = new System.IO.StringWriter();
                evt.RenderMessage(sw);
                // Include property values so token-leak assertions are meaningful.
                var props = string.Join(" ", evt.Properties.Select(p => $"{p.Key}={p.Value}"));
                TestLogSink.Add($"{sw} {props}");
            })));

        return base.CreateHost(builder);
    }

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
                        ["Jwt:Issuer"] = "tmap-test",
                        ["Jwt:Audience"] = "tmap-clients",
                        ["Jwt:AccessTokenMinutes"] = "15",
                        ["Jwt:RefreshTokenDays"] = "60",
                        ["Jwt:ActiveKeyId"] = "k1",
                        ["Jwt:SigningKeys:k1"] = "0123456789ABCDEF0123456789ABCDEF",
                        ["Jwt:SigningKeys:k2"] = "FEDCBA9876543210FEDCBA9876543210",
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
