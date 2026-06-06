namespace Tmap.Api.Features.Reports;

public sealed record CompletedTaskReportItem(Guid Id, string Project, DateOnly Date);

public sealed record SessionReportItem(string Project, int Minutes, DateOnly Date);

public sealed record DailyPlanReportItem(
    DateOnly Date,
    DateTimeOffset CommittedAt,
    List<Guid> PlannedTaskIds,
    int PlannedMinutes);

public sealed record ReportDataResponse(
    List<CompletedTaskReportItem> CompletedTasks,
    List<SessionReportItem> Sessions,
    List<DailyPlanReportItem> DailyPlans);
