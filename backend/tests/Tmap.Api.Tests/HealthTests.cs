using System.Net;
using System.Net.Http.Json;
using FluentAssertions;
using Xunit;

namespace Tmap.Api.Tests;

[Collection(DbCollection.Name)]
public sealed class HealthTests(PostgresFixture fixture) : IntegrationTestBase(fixture)
{
    [Fact]
    public async Task Get_Health_Returns200_WithOkStatus()
    {
        var response = await Client.GetAsync("/health");

        response.StatusCode.Should().Be(HttpStatusCode.OK);

        var body = await response.Content.ReadFromJsonAsync<HealthResponseDto>();
        body.Should().NotBeNull();
        body!.Status.Should().Be("ok");
    }

    private sealed record HealthResponseDto(string Status);
}
