using Tmap.Api.Common;
using Tmap.Api.Features.Subtasks;
using Tmap.Api.Infrastructure.Entities;
using TaskStatus = Tmap.Api.Infrastructure.Entities.TaskStatus;

namespace Tmap.Api.Features.Tasks;

public sealed record CreateTaskRequest(
    Guid? Id,
    string Title,
    string? Notes,
    Guid? ProjectId,
    List<string>? Labels,
    string? Source,
    TaskStatus? Status,
    DateOnly? PlannedDate,
    DateTimeOffset? ScheduledStart,
    DateTimeOffset? ScheduledEnd,
    int? DurationMinutes,
    int? Priority,
    int? ReminderMinutes,
    string? Rank,
    DateOnly? DueDate);

public sealed record UpdateTaskRequest(
    string? Title,
    string? Notes,
    // CLEARABLE: Optional<T?> distinguishes "absent" (leave unchanged) from explicit null (clear),
    // so unschedule / move-to-backlog / rollover persist instead of silently no-op'ing.
    Optional<Guid?> ProjectId,
    List<string>? Labels,
    string? Source,
    TaskStatus? Status,
    Optional<DateOnly?> PlannedDate,
    Optional<DateTimeOffset?> ScheduledStart,
    Optional<DateTimeOffset?> ScheduledEnd,
    int? DurationMinutes,
    int? ActualTimeMinutes,
    Optional<int?> Priority,
    Optional<int?> ReminderMinutes,
    string? Rank,
    DateOnly? DueDate,
    DateTimeOffset? CompletedAt);

public sealed record ReorderItem(Guid Id, string Rank);

public sealed record TaskResponse(
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
    IReadOnlyList<SubtaskResponse> Subtasks);
