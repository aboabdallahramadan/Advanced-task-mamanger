using System.Net;
using System.Net.Http.Json;
using FluentAssertions;
using Microsoft.AspNetCore.Http;
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

    [Fact]
    public async Task Many_distinct_emails_from_one_ip_are_bounded_by_the_ip_aggregate_cap()
    {
        // A fresh factory => clean rate-limiter partition state (partitions are process-global and
        // persist across tests in the shared in-process server). The TestServer reports one stable
        // IP key for every request, so distinct emails dodge the IP|email bucket but accumulate
        // under the shared IP key -> the coarse AuthByIp policy (30/min) must trip a 429.
        using var factory = Factory.WithWebHostBuilder(_ => { });
        using var client = factory.CreateClient();

        var statuses = new List<int>();
        for (var i = 0; i < 40; i++)
        {
            var email = $"ipcap-{System.Guid.NewGuid():N}@x.io";
            var res = await client.PostAsJsonAsync(
                "/api/v1/auth/register",
                new { email, password = "Password123!x" });
            statuses.Add((int)res.StatusCode);
        }

        statuses.Should().Contain(
            StatusCodes.Status429TooManyRequests,
            "an aggregate per-IP cap must bound register spam across distinct emails from one IP");
    }
}
