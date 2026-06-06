using Microsoft.AspNetCore.Http.HttpResults;
using Microsoft.EntityFrameworkCore;
using Tmap.Api.Common;
using Tmap.Api.Common.Validation;
using Tmap.Api.Infrastructure;
using Tmap.Api.Infrastructure.Entities;

namespace Tmap.Api.Features.Recurrence;

public static class RecurrenceEndpoints
{
    public static IEndpointRouteBuilder MapRecurrenceEndpoints(this IEndpointRouteBuilder app)
    {
        var group = app.MapGroup("/api/v1/recurrence").RequireAuthorization();

        group.MapGet("/rules/{ruleId:guid}", GetRule).WithName("GetRecurrenceRule");
        group.MapPatch("/rules/{ruleId:guid}", UpdateRule)
            .AddEndpointFilter<ValidationFilter<UpdateRuleRequest>>()
            .WithName("UpdateRecurrenceRule");

        // (create, updateSeries, deleteSeries, deleteSeriesFuture, detachInstance,
        //  ensure-instances are mapped in P5-7 / P5-8 — added to THIS group.)
        MapSeriesOperations(group);
        MapEnsureInstances(group);

        return app;
    }

    private static async Task<Results<Ok<RecurrenceRuleResponse>, NotFound>> GetRule(
        Guid ruleId,
        AppDbContext db,
        ICurrentUser currentUser,
        CancellationToken ct)
    {
        var rule = await db.Set<RecurrenceRule>()
            .AsNoTracking()
            .FirstOrDefaultAsync(r => r.Id == ruleId, ct);

        return rule is null ? TypedResults.NotFound() : TypedResults.Ok(ToRuleResponse(rule));
    }

    private static async Task<Results<Ok<RecurrenceRuleResponse>, NotFound>> UpdateRule(
        Guid ruleId,
        UpdateRuleRequest req,
        AppDbContext db,
        ICurrentUser currentUser,
        CancellationToken ct)
    {
        var rule = await db.Set<RecurrenceRule>().FirstOrDefaultAsync(r => r.Id == ruleId, ct);
        if (rule is null) return TypedResults.NotFound();

        rule.Frequency = req.Frequency;
        rule.IntervalValue = req.Interval;
        rule.DaysOfWeek = req.DaysOfWeek;
        rule.EndType = req.EndType;
        rule.EndCount = req.EndType == RecurrenceEndType.Count ? req.EndCount : null;
        rule.EndDate = req.EndType == RecurrenceEndType.Date ? req.EndDate : null;
        // Force regeneration on the next ensure-instances (legacy behavior).
        rule.GeneratedUntil = null;

        // Tombstone future, non-detached, not-done instances so they regenerate.
        var today = DateOnly.FromDateTime(DateTime.UtcNow);
        var now = DateTimeOffset.UtcNow;
        await db.Set<TaskItem>()
            .Where(t => t.RecurrenceRuleId == ruleId
                        && !t.IsRecurrenceTemplate
                        && !t.RecurrenceDetached
                        && t.Status != Infrastructure.Entities.TaskStatus.Done
                        && t.PlannedDate >= today)
            .ExecuteUpdateAsync(s => s.SetProperty(t => t.DeletedAt, now), ct);

        await db.SaveChangesAsync(ct);
        return TypedResults.Ok(ToRuleResponse(rule));
    }

    internal static RecurrenceRuleResponse ToRuleResponse(RecurrenceRule r) =>
        new(r.Id, r.Frequency, r.IntervalValue, r.DaysOfWeek, r.EndType, r.EndCount, r.EndDate,
            r.GeneratedUntil, r.CreatedAt, r.UpdatedAt);

    // TEMPORARY STUBS — replaced in P5-7 / P5-8 (kept at the bottom of the class):
    private static void MapSeriesOperations(RouteGroupBuilder group) { }
    private static void MapEnsureInstances(RouteGroupBuilder group) { }
}
