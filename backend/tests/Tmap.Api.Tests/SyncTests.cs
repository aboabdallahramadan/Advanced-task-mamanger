using System.Net;
using System.Net.Http.Json;
using FluentAssertions;
using Xunit;

namespace Tmap.Api.Tests;

[Collection("db")]
public class SyncTests(PostgresFixture fixture) : IntegrationTestBase(fixture)
{
    // Local DTOs mirroring the C6 sync wire shape. Kept private to the test file.
    private sealed record SyncResponseDto(SyncChangesDto Changes, long NextSince, bool HasMore);

    private sealed record SyncChangesDto(
        List<TaskSyncRowDto> Tasks,
        List<SubtaskSyncRowDto> Subtasks,
        List<ProjectSyncRowDto> Projects,
        List<NoteGroupSyncRowDto> NoteGroups,
        List<NoteSyncRowDto> Notes,
        List<RecurrenceRuleSyncRowDto> RecurrenceRules,
        List<FocusSessionSyncRowDto> FocusSessions,
        List<DailyPlanSyncRowDto> DailyPlans,
        List<SettingSyncRowDto> Settings);

    private sealed record TaskSyncRowDto(Guid Id, string Title, string Rank, long ChangeSeq, DateTimeOffset? DeletedAt);
    private sealed record SubtaskSyncRowDto(Guid Id, Guid TaskId, long ChangeSeq, DateTimeOffset? DeletedAt);
    private sealed record ProjectSyncRowDto(Guid Id, string Name, string Rank, long ChangeSeq, DateTimeOffset? DeletedAt);
    private sealed record NoteGroupSyncRowDto(Guid Id, string Name, string Rank, long ChangeSeq, DateTimeOffset? DeletedAt);
    private sealed record NoteSyncRowDto(Guid Id, string Rank, long ChangeSeq, DateTimeOffset? DeletedAt);
    private sealed record RecurrenceRuleSyncRowDto(Guid Id, long ChangeSeq, DateTimeOffset? DeletedAt);
    private sealed record FocusSessionSyncRowDto(Guid Id, long ChangeSeq, DateTimeOffset? DeletedAt);
    private sealed record DailyPlanSyncRowDto(DateOnly Date, int PlannedMinutes, long ChangeSeq, DateTimeOffset? DeletedAt);
    private sealed record SettingSyncRowDto(string Key, string Value, long ChangeSeq, DateTimeOffset? DeletedAt);

    [Fact]
    public async Task Sync_RequiresAuthentication()
    {
        var resp = await Client.GetAsync("/api/v1/sync?since=0");
        resp.StatusCode.Should().Be(HttpStatusCode.Unauthorized);
    }

    [Fact]
    public async Task Sync_EmptyDb_ReturnsEmptyArrays_NextSinceEqualsSince_HasMoreFalse()
    {
        var user = await RegisterAsync();

        var resp = await user.Client.GetAsync("/api/v1/sync?since=0");
        resp.StatusCode.Should().Be(HttpStatusCode.OK);
        var body = await resp.Content.ReadFromJsonAsync<SyncResponseDto>();

        body.Should().NotBeNull();
        body!.Changes.Tasks.Should().BeEmpty();
        body.Changes.Subtasks.Should().BeEmpty();
        body.Changes.Projects.Should().BeEmpty();
        body.Changes.NoteGroups.Should().BeEmpty();
        body.Changes.Notes.Should().BeEmpty();
        body.Changes.RecurrenceRules.Should().BeEmpty();
        body.Changes.FocusSessions.Should().BeEmpty();
        body.Changes.DailyPlans.Should().BeEmpty();
        body.Changes.Settings.Should().BeEmpty();
        body.NextSince.Should().Be(0, "empty pull leaves the cursor where it was");
        body.HasMore.Should().BeFalse();
    }

    [Fact]
    public async Task Sync_Since0_IncludesLiveRows_And_Tombstones_WithDeletedAtSet()
    {
        var user = await RegisterAsync();

        // Create a project + task via HTTP, then soft-delete the task.
        var proj = await (await user.Client.PostAsJsonAsync("/api/v1/projects",
            new { name = "P", color = "#111111", emoji = "📁", rank = "a0" }))
            .Content.ReadFromJsonAsync<ProjectSyncRowDto>();
        var task = await (await user.Client.PostAsJsonAsync("/api/v1/tasks",
            new { title = "T", rank = "a0" }))
            .Content.ReadFromJsonAsync<TaskSyncRowDto>();
        var delResp = await user.Client.DeleteAsync($"/api/v1/tasks/{task!.Id}");
        delResp.StatusCode.Should().Be(HttpStatusCode.NoContent);

        var body = await user.Client.GetFromJsonAsync<SyncResponseDto>("/api/v1/sync?since=0");

        body!.Changes.Projects.Should().ContainSingle(p => p.Id == proj!.Id && p.DeletedAt == null);
        var pulledTask = body.Changes.Tasks.Should().ContainSingle(t => t.Id == task.Id).Subject;
        pulledTask.DeletedAt.Should().NotBeNull("a soft-deleted task is delivered as a tombstone");
        body.NextSince.Should().BeGreaterThan(0);
        body.HasMore.Should().BeFalse();
    }

    [Fact]
    public async Task Sync_SinceCursor_ReturnsOnlyNewerRows()
    {
        var user = await RegisterAsync();

        await user.Client.PostAsJsonAsync("/api/v1/projects",
            new { name = "First", color = "#111111", emoji = "📁", rank = "a0" });
        var cut = await user.Client.GetFromJsonAsync<SyncResponseDto>("/api/v1/sync?since=0");
        var cursor = cut!.NextSince;

        // A second write after the cursor.
        var second = await (await user.Client.PostAsJsonAsync("/api/v1/projects",
            new { name = "Second", color = "#222222", emoji = "📁", rank = "a1" }))
            .Content.ReadFromJsonAsync<ProjectSyncRowDto>();

        var body = await user.Client.GetFromJsonAsync<SyncResponseDto>($"/api/v1/sync?since={cursor}");
        body!.Changes.Projects.Should().ContainSingle(p => p.Id == second!.Id);
        body.Changes.Projects.Should().NotContain(p => p.Name == "First");
        body.NextSince.Should().BeGreaterThan(cursor);
    }

    [Fact]
    public async Task Sync_Paginates_HasMoreTrue_Then_ChainsToCompletion()
    {
        var user = await RegisterAsync();

        // 7 projects; pull with limit=3 chains 3 + 3 + 1.
        for (var i = 0; i < 7; i++)
        {
            await user.Client.PostAsJsonAsync("/api/v1/projects",
                new { name = $"P{i}", color = "#111111", emoji = "📁", rank = $"a{i}" });
        }

        var seen = new HashSet<Guid>();
        long since = 0;
        var pages = 0;
        bool hasMore;
        do
        {
            var page = await user.Client.GetFromJsonAsync<SyncResponseDto>($"/api/v1/sync?since={since}&limit=3");
            page!.Changes.Projects.Count.Should().BeLessThanOrEqualTo(3);
            foreach (var p in page.Changes.Projects)
            {
                seen.Add(p.Id);
            }
            page.NextSince.Should().BeGreaterThanOrEqualTo(since);
            since = page.NextSince;
            hasMore = page.HasMore;
            pages++;
            pages.Should().BeLessThan(10, "pagination must terminate");
        } while (hasMore);

        seen.Should().HaveCount(7, "every project is delivered exactly once across pages");
    }

    [Fact]
    public async Task Sync_OversizedLimit_IsClampedTo500()
    {
        var user = await RegisterAsync();
        await user.Client.PostAsJsonAsync("/api/v1/projects",
            new { name = "P", color = "#111111", emoji = "📁", rank = "a0" });

        // limit=99999 must not error; it is clamped. With one row HasMore is false.
        var resp = await user.Client.GetAsync("/api/v1/sync?since=0&limit=99999");
        resp.StatusCode.Should().Be(HttpStatusCode.OK);
        var body = await resp.Content.ReadFromJsonAsync<SyncResponseDto>();
        body!.Changes.Projects.Should().ContainSingle();
        body.HasMore.Should().BeFalse();
    }
}
