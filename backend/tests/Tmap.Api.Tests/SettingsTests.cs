using System.Net;
using System.Net.Http.Json;
using FluentAssertions;
using Microsoft.EntityFrameworkCore;
using Tmap.Api.Features.Settings;
using Tmap.Api.Infrastructure.Entities;
using Xunit;

namespace Tmap.Api.Tests;

[Collection("db")]
public class SettingsTests(PostgresFixture fixture) : IntegrationTestBase(fixture)
{
    [Fact]
    public async Task Get_NewUser_ReturnsEmptySettingsAndDefaultTimeZone()
    {
        var user = await RegisterAsync();
        var dto = await user.Client.GetFromJsonAsync<SettingsResponse>("/api/v1/settings");
        dto!.Settings.Should().BeEmpty();
        dto.TimeZoneId.Should().Be("UTC");
    }

    [Fact]
    public async Task Put_PersistsWhitelistedKeys_AndIgnoresOthers()
    {
        var user = await RegisterAsync();
        var req = new SaveSettingsRequest(new Dictionary<string, string>
        {
            ["workStartHour"] = "8",
            ["workEndHour"] = "17",
            ["timeIncrement"] = "15",
            ["sidebarCollapsed"] = "true", // NOT whitelisted -> must be ignored
        });

        var put = await user.Client.PutAsJsonAsync("/api/v1/settings", req);
        put.StatusCode.Should().Be(HttpStatusCode.OK);

        var dto = await user.Client.GetFromJsonAsync<SettingsResponse>("/api/v1/settings");
        dto!.Settings.Should().ContainKey("workStartHour").WhoseValue.Should().Be("8");
        dto.Settings.Should().ContainKey("workEndHour").WhoseValue.Should().Be("17");
        dto.Settings.Should().ContainKey("timeIncrement").WhoseValue.Should().Be("15");
        dto.Settings.Should().NotContainKey("sidebarCollapsed");

        await using var db = NewElevatedDbContext();
        var keys = await db.Set<UserSetting>()
            .Where(s => s.UserId == user.UserId)
            .Select(s => s.Key).ToListAsync();
        keys.Should().BeEquivalentTo(new[] { "workStartHour", "workEndHour", "timeIncrement" });
    }

    [Fact]
    public async Task Put_UpdatesExistingKeyInPlace()
    {
        var user = await RegisterAsync();
        await user.Client.PutAsJsonAsync("/api/v1/settings",
            new SaveSettingsRequest(new Dictionary<string, string> { ["timeIncrement"] = "30" }));
        await user.Client.PutAsJsonAsync("/api/v1/settings",
            new SaveSettingsRequest(new Dictionary<string, string> { ["timeIncrement"] = "5" }));

        var dto = await user.Client.GetFromJsonAsync<SettingsResponse>("/api/v1/settings");
        dto!.Settings["timeIncrement"].Should().Be("5");

        await using var db = NewElevatedDbContext();
        var count = await db.Set<UserSetting>()
            .CountAsync(s => s.UserId == user.UserId && s.Key == "timeIncrement");
        count.Should().Be(1); // upsert, not duplicate
    }

    [Fact]
    public async Task SettingsAreScopedPerUser()
    {
        var a = await RegisterAsync();
        var b = await RegisterAsync();
        await a.Client.PutAsJsonAsync("/api/v1/settings",
            new SaveSettingsRequest(new Dictionary<string, string> { ["workStartHour"] = "6" }));

        var bDto = await b.Client.GetFromJsonAsync<SettingsResponse>("/api/v1/settings");
        bDto!.Settings.Should().BeEmpty();
    }

    [Fact]
    public async Task Get_RequiresAuthentication()
    {
        var resp = await Client.GetAsync("/api/v1/settings");
        resp.StatusCode.Should().Be(HttpStatusCode.Unauthorized);
    }
}
