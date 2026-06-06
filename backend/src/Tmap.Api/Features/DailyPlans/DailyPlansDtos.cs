namespace Tmap.Api.Features.DailyPlans;

public sealed record UpsertDailyPlanRequest(
    List<Guid> PlannedTaskIds,
    int PlannedMinutes);

public sealed record DailyPlanResponse(
    DateOnly Date,
    DateTimeOffset CommittedAt,
    List<Guid> PlannedTaskIds,
    int PlannedMinutes,
    DateTimeOffset CreatedAt,
    DateTimeOffset UpdatedAt);
