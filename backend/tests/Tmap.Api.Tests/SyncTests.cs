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
}
