using Tmap.Api.Infrastructure.Entities;
using TaskStatus = Tmap.Api.Infrastructure.Entities.TaskStatus;

namespace Tmap.Api.Features.Sync;

// Each *SyncRow mirrors its *Response field set MINUS nested children, PLUS ChangeSeq + DeletedAt.
// recurrence_exceptions is intentionally NOT synced.

public sealed record TaskSyncRow(
    Guid Id,
    string Title,
    string Notes,
    Guid? ProjectId,
    List<string> Labels,
    string Source,
    TaskStatus Status,
    DateOnly? PlannedDate,
    DateTimeOffset? ScheduledStart,
    DateTimeOffset? ScheduledEnd,
    int DurationMinutes,
    int ActualTimeMinutes,
    int? Priority,
    int? ReminderMinutes,
    string Rank,
    DateOnly? DueDate,
    Guid? RecurrenceRuleId,
    bool IsRecurrenceTemplate,
    bool RecurrenceDetached,
    DateOnly? RecurrenceOriginalDate,
    DateTimeOffset? CompletedAt,
    DateTimeOffset CreatedAt,
    DateTimeOffset UpdatedAt,
    long ChangeSeq,
    DateTimeOffset? DeletedAt);

public sealed record SubtaskSyncRow(
    Guid Id,
    Guid TaskId,
    string Title,
    bool Completed,
    int SortOrder,
    DateTimeOffset CreatedAt,
    DateTimeOffset UpdatedAt,
    long ChangeSeq,
    DateTimeOffset? DeletedAt);

public sealed record ProjectSyncRow(
    Guid Id,
    string Name,
    string Color,
    string Emoji,
    string Rank,
    int ActualTimeMinutes,
    DateTimeOffset CreatedAt,
    DateTimeOffset UpdatedAt,
    long ChangeSeq,
    DateTimeOffset? DeletedAt);

public sealed record NoteGroupSyncRow(
    Guid Id,
    string Name,
    string Emoji,
    Guid? ProjectId,
    string Rank,
    DateTimeOffset CreatedAt,
    DateTimeOffset UpdatedAt,
    long ChangeSeq,
    DateTimeOffset? DeletedAt);

public sealed record NoteSyncRow(
    Guid Id,
    Guid? GroupId,
    Guid? ProjectId,
    string Title,
    string Content,
    string Rank,
    DateTimeOffset CreatedAt,
    DateTimeOffset UpdatedAt,
    long ChangeSeq,
    DateTimeOffset? DeletedAt);

public sealed record RecurrenceRuleSyncRow(
    Guid Id,
    RecurrenceFrequency Frequency,
    int Interval,
    List<int> DaysOfWeek,
    RecurrenceEndType EndType,
    int? EndCount,
    DateOnly? EndDate,
    DateOnly? GeneratedUntil,
    DateTimeOffset CreatedAt,
    DateTimeOffset UpdatedAt,
    long ChangeSeq,
    DateTimeOffset? DeletedAt);

public sealed record FocusSessionSyncRow(
    Guid Id,
    Guid? TaskId,
    string Project,
    DateTimeOffset StartedAt,
    DateTimeOffset EndedAt,
    int Minutes,
    DateOnly Date,
    DateTimeOffset CreatedAt,
    DateTimeOffset UpdatedAt,
    long ChangeSeq,
    DateTimeOffset? DeletedAt);

public sealed record DailyPlanSyncRow(
    DateOnly Date,
    DateTimeOffset CommittedAt,
    List<Guid> PlannedTaskIds,
    int PlannedMinutes,
    long ChangeSeq,
    DateTimeOffset? DeletedAt);

public sealed record SettingSyncRow(
    string Key,
    string Value,
    long ChangeSeq,
    DateTimeOffset? DeletedAt);

public sealed record SyncChanges(
    List<TaskSyncRow> Tasks,
    List<SubtaskSyncRow> Subtasks,
    List<ProjectSyncRow> Projects,
    List<NoteGroupSyncRow> NoteGroups,
    List<NoteSyncRow> Notes,
    List<RecurrenceRuleSyncRow> RecurrenceRules,
    List<FocusSessionSyncRow> FocusSessions,
    List<DailyPlanSyncRow> DailyPlans,
    List<SettingSyncRow> Settings);

public sealed record SyncResponse(SyncChanges Changes, long NextSince, bool HasMore);
