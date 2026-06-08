using Tmap.Api.Infrastructure.Entities;

namespace Tmap.Api.Features.Recurrence;

// ---- create(task + rule) ----
public sealed record RecurringTaskInput(
    string Title,
    string Notes,
    Guid? ProjectId,
    List<string> Labels,
    string Source,
    DateOnly? PlannedDate,
    int DurationMinutes,
    int? Priority,
    int? ReminderMinutes,
    Guid? Id = null);

public sealed record RecurrenceRuleInput(
    RecurrenceFrequency Frequency,
    int Interval,
    List<int> DaysOfWeek,
    RecurrenceEndType EndType,
    int? EndCount,
    DateOnly? EndDate);

public sealed record CreateRecurringTaskRequest(
    RecurringTaskInput Task,
    RecurrenceRuleInput Rule);


// ---- rule read ----
public sealed record RecurrenceRuleResponse(
    Guid Id,
    RecurrenceFrequency Frequency,
    int Interval,
    List<int> DaysOfWeek,
    RecurrenceEndType EndType,
    int? EndCount,
    DateOnly? EndDate,
    DateOnly? GeneratedUntil,
    DateTimeOffset CreatedAt,
    DateTimeOffset UpdatedAt);

// ---- updateRule (whole-rule replace of mutable fields) ----
public sealed record UpdateRuleRequest(
    RecurrenceFrequency Frequency,
    int Interval,
    List<int> DaysOfWeek,
    RecurrenceEndType EndType,
    int? EndCount,
    DateOnly? EndDate);

// ---- updateSeries(ruleId, updates): field-level edits propagated to template + future live instances ----
public sealed record UpdateSeriesRequest(
    string? Title,
    string? Notes,
    Guid? ProjectId,
    List<string>? Labels,
    int? DurationMinutes,
    int? Priority,
    int? ReminderMinutes);

// ---- deleteSeriesFuture(ruleId, fromDate) ----
public sealed record DeleteSeriesFutureRequest(DateOnly FromDate);

