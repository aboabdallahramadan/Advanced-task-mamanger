using System.Net;
using FluentAssertions;
using Xunit;

namespace Tmap.Api.Tests;

[Collection(DbCollection.Name)]
public sealed class ProxyHardeningTests : IntegrationTestBase
{
    public ProxyHardeningTests(PostgresFixture fixture) : base(fixture) { }

    [Fact]
    public async Task OpenApi_document_is_served_in_non_production()
    {
        // Default test environment is "Testing" (not Production) -> the OpenAPI guard must allow it.
        var response = await Client.GetAsync("/openapi/v1.json");
        response.StatusCode.Should().Be(HttpStatusCode.OK);
    }
}
