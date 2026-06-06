using Microsoft.AspNetCore.Http.HttpResults;
using Microsoft.EntityFrameworkCore;
using Tmap.Api.Common;
using Tmap.Api.Infrastructure;
using Tmap.Api.Infrastructure.Entities;

namespace Tmap.Api.Features.Reports;

public static class ReportsEndpoints
{
    public static IEndpointRouteBuilder MapReportsEndpoints(this IEndpointRouteBuilder app)
    {
        var group = app.MapGroup("reports").RequireAuthorization();
        group.MapGet("/", GetData).WithName("GetReportData");
        return app;
    }

    private static async Task<Results<Ok<ReportDataResponse>, ValidationProblem>> GetData(
        DateOnly start,
        DateOnly end,
        AppDbContext db,
        ICurrentUser currentUser,
        CancellationToken ct)
    {
        if (end < start)
        {
            return TypedResults.ValidationProblem(new Dictionary<string, string[]>
            {
                ["end"] = ["'end' must be on or after 'start'."],
            });
        }

        var userId = currentUser.Id;

        // Resolve the user's IANA timezone for completed-task day bucketing.
        var tzId = await db.Users
            .Where(u => u.Id == userId)
            .Select(u => u.TimeZoneId)
            .SingleAsync(ct);
        var tz = ResolveTimeZone(tzId);

        // Completed tasks: bucket CompletedAt into the user's LOCAL day in-memory.
        // (Pull the minimal columns; tenant + soft-delete filters apply.)
        var rawTasks = await db.Set<TaskItem>()
            .AsNoTracking()
            .Where(t => t.CompletedAt != null)
            .Select(t => new
            {
                t.Id,
                t.CompletedAt,
                ProjectName = t.ProjectId == null
                    ? null
                    : db.Set<Project>().Where(p => p.Id == t.ProjectId).Select(p => p.Name).FirstOrDefault(),
            })
            .ToListAsync(ct);

        var completedTasks = rawTasks
            .Select(t => new
            {
                t.Id,
                Project = t.ProjectName ?? "",
                Date = DateOnly.FromDateTime(
                    TimeZoneInfo.ConvertTime(t.CompletedAt!.Value, tz).DateTime),
            })
            .Where(t => t.Date >= start && t.Date <= end)
            .Select(t => new CompletedTaskReportItem(t.Id, t.Project, t.Date))
            .ToList();

        // Focus sessions: Date is already the local day at write time.
        var sessions = await db.Set<FocusSession>()
            .AsNoTracking()
            .Where(f => f.Date >= start && f.Date <= end)
            .OrderBy(f => f.Date)
            .Select(f => new SessionReportItem(f.Project ?? "", f.Minutes, f.Date))
            .ToListAsync(ct);

        // Daily plans: Date is the keyed local day.
        var dailyPlans = await db.Set<DailyPlan>()
            .AsNoTracking()
            .Where(p => p.Date >= start && p.Date <= end)
            .OrderBy(p => p.Date)
            .Select(p => new DailyPlanReportItem(p.Date, p.CommittedAt, p.PlannedTaskIds, p.PlannedMinutes))
            .ToListAsync(ct);

        return TypedResults.Ok(new ReportDataResponse(completedTasks, sessions, dailyPlans));
    }

    private static TimeZoneInfo ResolveTimeZone(string? tzId)
    {
        if (string.IsNullOrWhiteSpace(tzId)) return TimeZoneInfo.Utc;
        // .NET on both Linux and Windows resolves IANA ids via ICU; fall back to UTC.
        try { return TimeZoneInfo.FindSystemTimeZoneById(tzId); }
        catch (TimeZoneNotFoundException) { return TimeZoneInfo.Utc; }
        catch (InvalidTimeZoneException) { return TimeZoneInfo.Utc; }
    }
}
