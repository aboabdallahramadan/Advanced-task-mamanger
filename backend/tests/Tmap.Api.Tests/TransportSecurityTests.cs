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
}
