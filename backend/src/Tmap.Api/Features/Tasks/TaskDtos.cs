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
    Guid? ProjectId,
    List<string>? Labels,
    string? Source,
    TaskStatus? Status,
    DateOnly? PlannedDate,
    DateTimeOffset? ScheduledStart,
    DateTimeOffset? ScheduledEnd,
    int? DurationMinutes,
    int? ActualTimeMinutes,
    int? Priority,
    int? ReminderMinutes,
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
