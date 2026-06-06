using System.Net;
using System.Text.Json;
using FluentAssertions;
using Xunit;

namespace Tmap.Api.Tests;

[Collection("db")]
public sealed class OpenApiDocumentTests : IntegrationTestBase
{
    public OpenApiDocumentTests(PostgresFixture fixture) : base(fixture) { }

    private async Task<JsonDocument> GetOpenApiAsync()
    {
        var response = await Client.GetAsync("/openapi/v1.json");
        response.StatusCode.Should().Be(HttpStatusCode.OK);
        var stream = await response.Content.ReadAsStreamAsync();
        return await JsonDocument.ParseAsync(stream);
    }

    [Fact]
    public async Task Document_is_openapi_3_1()
    {
        using var doc = await GetOpenApiAsync();
        doc.RootElement.GetProperty("openapi").GetString()
            .Should().StartWith("3.1");
    }

    [Fact]
    public async Task Document_has_title_and_version()
    {
        using var doc = await GetOpenApiAsync();
        var info = doc.RootElement.GetProperty("info");
        info.GetProperty("title").GetString().Should().Be("TMap API");
        info.GetProperty("version").GetString().Should().Be("v1");
    }

    [Theory]
    [InlineData("/api/v1/auth/register")]
    [InlineData("/api/v1/auth/login")]
    [InlineData("/api/v1/auth/refresh")]
    [InlineData("/api/v1/auth/me")]
    [InlineData("/api/v1/tasks")]
    public async Task Document_contains_path(string path)
    {
        using var doc = await GetOpenApiAsync();
        doc.RootElement.GetProperty("paths")
            .TryGetProperty(path, out _)
            .Should().BeTrue($"the OpenAPI document should expose {path}");
    }

    [Fact]
    public async Task Document_defines_bearer_security_scheme()
    {
        using var doc = await GetOpenApiAsync();
        var scheme = doc.RootElement
            .GetProperty("components")
            .GetProperty("securitySchemes")
            .GetProperty("Bearer");
        scheme.GetProperty("type").GetString().Should().Be("http");
        scheme.GetProperty("scheme").GetString().Should().Be("bearer");
        scheme.GetProperty("bearerFormat").GetString().Should().Be("JWT");
    }

    [Fact]
    public async Task Protected_endpoint_carries_security_requirement()
    {
        using var doc = await GetOpenApiAsync();
        var get = doc.RootElement
            .GetProperty("paths")
            .GetProperty("/api/v1/tasks")
            .GetProperty("get");
        get.TryGetProperty("security", out var security).Should().BeTrue();
        security.GetArrayLength().Should().BeGreaterThan(0);
        security[0].TryGetProperty("Bearer", out _).Should().BeTrue();
    }
}
