using System.Net;
using System.Net.Http;
using FluentAssertions;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.Hosting;
using Xunit;

namespace Tmap.Api.Tests;

[Collection("db")]
public sealed class TransportSecurityTests : IntegrationTestBase
{
    public TransportSecurityTests(PostgresFixture fixture) : base(fixture) { }

    [Fact]
    public async Task Production_responses_include_hsts_header()
    {
        using var factory = Factory.WithWebHostBuilder(b => b.UseEnvironment(Environments.Production));
        using var client = factory.CreateClient(new WebApplicationFactoryClientOptions
        {
            BaseAddress = new Uri("https://localhost"),
        });

        var response = await client.GetAsync("/openapi/v1.json");

        response.Headers.Contains("Strict-Transport-Security").Should().BeTrue();
    }

    [Fact]
    public async Task Development_responses_omit_hsts_header()
    {
        // Default test environment is not Production -> HSTS middleware not added.
        var response = await Client.GetAsync("/openapi/v1.json");
        response.Headers.Contains("Strict-Transport-Security").Should().BeFalse();
    }

    [Fact]
    public async Task Forwarded_proto_https_over_proxied_http_emits_hsts()
    {
        // Behind Traefik the in-container request arrives over http with X-Forwarded-Proto=https.
        // UseForwardedHeaders (run before UseHsts) rewrites the scheme so HSTS is emitted — but ONLY
        // when the connecting peer is trusted. The hardened ForwardedHeadersOptions trusts loopback +
        // the Docker bridge range, so we drive the request through the TestServer with an explicit
        // loopback RemoteIpAddress (the in-process TestServer otherwise leaves it null, which the
        // hardened known-peer check rejects). An untrusted peer's X-Forwarded-Proto would be ignored.
        using var factory = Factory.WithWebHostBuilder(b => b.UseEnvironment(Environments.Production));

        var context = await factory.Server.SendAsync(ctx =>
        {
            ctx.Request.Method = HttpMethods.Get;
            ctx.Request.Scheme = "http";
            ctx.Request.Host = new HostString("localhost");
            ctx.Request.Path = "/openapi/v1.json";
            ctx.Connection.RemoteIpAddress = IPAddress.Loopback; // trusted hop: loopback is in KnownNetworks
            ctx.Request.Headers["X-Forwarded-Proto"] = "https";
            ctx.Request.Headers["X-Forwarded-For"] = "1.2.3.4";
        });

        context.Response.Headers.ContainsKey("Strict-Transport-Security").Should().BeTrue(
            "UseForwardedHeaders should honor X-Forwarded-Proto=https from the trusted loopback hop so HSTS is emitted");
    }

    [Fact]
    public async Task Health_ready_returns_200_when_database_reachable()
    {
        // Readiness probe runs Npgsql SELECT 1 against ConnectionStrings:Postgres (the
        // Testcontainers app_user connection in tests) -> 200 Healthy.
        var response = await Client.GetAsync("/health/ready");
        response.StatusCode.Should().Be(System.Net.HttpStatusCode.OK);
    }

    [Fact]
    public async Task Health_liveness_returns_200_without_db_dependency()
    {
        var response = await Client.GetAsync("/health");
        response.StatusCode.Should().Be(System.Net.HttpStatusCode.OK);
    }
}
