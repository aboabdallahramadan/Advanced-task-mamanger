using System.Net;
using System.Net.Http.Json;
using FluentAssertions;
using Microsoft.EntityFrameworkCore;
using Xunit;

namespace Tmap.Api.Tests;

[Collection("db")]
public class SyncFullResyncTests(PostgresFixture fixture) : IntegrationTestBase(fixture)
{
    // Mirror of the C1/C8 wire shape — note the new FullResyncRequired field.
    private sealed record SyncResponseDto(
        SyncChangesDto Changes, long NextSince, bool HasMore, bool FullResyncRequired);

    private sealed record SyncChangesDto(
        List<TaskSyncRowDto> Tasks,
        List<object> Subtasks,
        List<ProjectSyncRowDto> Projects,
        List<object> NoteGroups,
        List<object> Notes,
        List<object> RecurrenceRules,
        List<object> FocusSessions,
        List<object> DailyPlans,
        List<object> Settings);

    private sealed record TaskSyncRowDto(Guid Id, long ChangeSeq, DateTimeOffset? DeletedAt);
    private sealed record ProjectSyncRowDto(Guid Id, string Name, long ChangeSeq, DateTimeOffset? DeletedAt);

    /// <summary>
    /// Force the purge watermark to <paramref name="value"/> directly via the elevated
    /// DbContext (the system id passes RLS; sync_purge_state is the single seeded row Id=1).
    /// Simulates TombstonePurgeService having advanced the watermark, without running the timer.
    /// </summary>
    private async Task SetWatermarkAsync(long value)
    {
        await using var ctx = NewElevatedDbContext();
        await ctx.Database.ExecuteSqlAsync(
            $"UPDATE sync_purge_state SET purged_below_change_seq = {value} WHERE id = 1");
    }

    [Fact]
    public async Task Sync_SinceBelowWatermark_ReturnsFullResyncRequired_WithEmptyChanges()
    {
        var user = await RegisterAsync();

        // Two writes → the tenant has live rows and a global high-water change_seq.
        await user.Client.PostAsJsonAsync("/api/v1/projects",
            new { name = "Alpha", color = "#111111", emoji = "📁", rank = "a0" });
        var afterFirst = await user.Client.GetFromJsonAsync<SyncResponseDto>("/api/v1/sync?since=0");
        var highWater = afterFirst!.NextSince;
        highWater.Should().BeGreaterThan(0);

        // Advance the watermark ABOVE the client's stale cursor (= 1, below highWater).
        await SetWatermarkAsync(highWater);

        var body = await user.Client.GetFromJsonAsync<SyncResponseDto>("/api/v1/sync?since=1");

        body!.FullResyncRequired.Should().BeTrue("since=1 < watermark, so a delta would miss purged tombstones");
        body.HasMore.Should().BeFalse();
        body.NextSince.Should().Be(highWater, "the directive echoes the current global high-water seq");
        body.Changes.Tasks.Should().BeEmpty();
        body.Changes.Projects.Should().BeEmpty();
        body.Changes.Subtasks.Should().BeEmpty();
        body.Changes.NoteGroups.Should().BeEmpty();
        body.Changes.Notes.Should().BeEmpty();
        body.Changes.RecurrenceRules.Should().BeEmpty();
        body.Changes.FocusSessions.Should().BeEmpty();
        body.Changes.DailyPlans.Should().BeEmpty();
        body.Changes.Settings.Should().BeEmpty();
    }

    [Fact]
    public async Task Sync_Since0_NeverTripsFullResync_EvenWithWatermarkAdvanced()
    {
        var user = await RegisterAsync();

        var created = await (await user.Client.PostAsJsonAsync("/api/v1/projects",
            new { name = "Beta", color = "#222222", emoji = "📁", rank = "a0" }))
            .Content.ReadFromJsonAsync<ProjectSyncRowDto>();
        var afterFirst = await user.Client.GetFromJsonAsync<SyncResponseDto>("/api/v1/sync?since=0");

        // Watermark above everything — but since=0 is a complete sync and must still serve the page.
        await SetWatermarkAsync(afterFirst!.NextSince + 1000);

        var body = await user.Client.GetFromJsonAsync<SyncResponseDto>("/api/v1/sync?since=0");

        body!.FullResyncRequired.Should().BeFalse("since=0 is always a full sync and never trips the directive");
        body.Changes.Projects.Should().ContainSingle(p => p.Id == created!.Id);
    }

    [Fact]
    public async Task Sync_SinceAtOrAboveWatermark_ReturnsNormalDeltas()
    {
        var user = await RegisterAsync();

        await user.Client.PostAsJsonAsync("/api/v1/projects",
            new { name = "Gamma", color = "#333333", emoji = "📁", rank = "a0" });
        var cut = await user.Client.GetFromJsonAsync<SyncResponseDto>("/api/v1/sync?since=0");
        var cursor = cut!.NextSince;

        // Watermark at or below the cursor → the client's delta is still complete.
        await SetWatermarkAsync(cursor);

        // A second write after the cursor.
        var second = await (await user.Client.PostAsJsonAsync("/api/v1/projects",
            new { name = "Delta", color = "#444444", emoji = "📁", rank = "a1" }))
            .Content.ReadFromJsonAsync<ProjectSyncRowDto>();

        var body = await user.Client.GetFromJsonAsync<SyncResponseDto>($"/api/v1/sync?since={cursor}");

        body!.FullResyncRequired.Should().BeFalse("since == watermark is not strictly below it");
        body.Changes.Projects.Should().ContainSingle(p => p.Id == second!.Id);
        body.NextSince.Should().BeGreaterThan(cursor);
    }
}
