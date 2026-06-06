using System.Net.Http;
using FluentAssertions;
using Xunit;

namespace Tmap.Api.Tests;

[Collection("db")]
public sealed class CorsTests : IntegrationTestBase
{
    public CorsTests(PostgresFixture fixture) : base(fixture) { }

    [Fact]
    public async Task Preflight_from_allowed_origin_is_reflected_with_credentials()
    {
        var request = new HttpRequestMessage(HttpMethod.Options, "/api/v1/auth/login");
        request.Headers.Add("Origin", "https://app.tmap.test");
        request.Headers.Add("Access-Control-Request-Method", "POST");
        request.Headers.Add("Access-Control-Request-Headers", "content-type,authorization");

        var response = await Client.SendAsync(request);

        response.Headers.GetValues("Access-Control-Allow-Origin")
            .Should().ContainSingle().Which.Should().Be("https://app.tmap.test");
        response.Headers.GetValues("Access-Control-Allow-Credentials")
            .Should().ContainSingle().Which.Should().Be("true");
    }

    [Fact]
    public async Task Preflight_from_disallowed_origin_has_no_allow_origin_header()
    {
        var request = new HttpRequestMessage(HttpMethod.Options, "/api/v1/auth/login");
        request.Headers.Add("Origin", "https://evil.example.com");
        request.Headers.Add("Access-Control-Request-Method", "POST");

        var response = await Client.SendAsync(request);

        response.Headers.Contains("Access-Control-Allow-Origin").Should().BeFalse();
    }

    [Fact]
    public async Task Empty_allowlist_grants_no_cross_origin_access()
    {
        using var factory = NewFactoryWithConfig(new Dictionary<string, string?>
        {
            // Wipe the allowlist seeded by IntegrationTestBase.
            ["Cors:AllowedOrigins:0"] = null,
        });
        using var client = factory.CreateClient();

        var request = new HttpRequestMessage(HttpMethod.Options, "/api/v1/auth/login");
        request.Headers.Add("Origin", "https://app.tmap.test");
        request.Headers.Add("Access-Control-Request-Method", "POST");

        var response = await client.SendAsync(request);

        response.Headers.Contains("Access-Control-Allow-Origin").Should().BeFalse();
    }
}
