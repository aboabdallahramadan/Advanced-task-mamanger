using System.Net.Http;
using FluentAssertions;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
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
        // UseForwardedHeaders (run before UseHsts) must rewrite the scheme so HSTS is emitted.
        using var factory = Factory.WithWebHostBuilder(b => b.UseEnvironment(Environments.Production));
        using var client = factory.CreateClient(new WebApplicationFactoryClientOptions
        {
            BaseAddress = new Uri("http://localhost"),
        });

        using var request = new HttpRequestMessage(HttpMethod.Get, "/openapi/v1.json");
        request.Headers.Add("X-Forwarded-Proto", "https");
        request.Headers.Add("X-Forwarded-For", "1.2.3.4");

        var response = await client.SendAsync(request);

        response.Headers.Contains("Strict-Transport-Security").Should().BeTrue(
            "UseForwardedHeaders should honor X-Forwarded-Proto=https so HSTS is emitted over a proxied http request");
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
