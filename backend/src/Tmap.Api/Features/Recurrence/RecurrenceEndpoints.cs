using Microsoft.AspNetCore.Http.HttpResults;
using Microsoft.EntityFrameworkCore;
using Tmap.Api.Common;
using Tmap.Api.Common.Validation;
using Tmap.Api.Infrastructure;
using Tmap.Api.Infrastructure.Entities;
using TaskStatus = Tmap.Api.Infrastructure.Entities.TaskStatus;

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

    private static void MapSeriesOperations(RouteGroupBuilder group)
    {
        group.MapPost("/", Create)
            .AddEndpointFilter<ValidationFilter<CreateRecurringTaskRequest>>()
            .WithName("CreateRecurringTask");

        group.MapPatch("/rules/{ruleId:guid}/series", UpdateSeries)
            .AddEndpointFilter<ValidationFilter<UpdateSeriesRequest>>()
            .WithName("UpdateRecurrenceSeries");

        group.MapDelete("/rules/{ruleId:guid}", DeleteSeries).WithName("DeleteRecurrenceSeries");

        group.MapPost("/rules/{ruleId:guid}/delete-future", DeleteSeriesFuture)
            .AddEndpointFilter<ValidationFilter<DeleteSeriesFutureRequest>>()
            .WithName("DeleteRecurrenceSeriesFuture");

        group.MapPatch("/instances/{taskId:guid}/detach", DetachInstance)
            .WithName("DetachRecurrenceInstance");
    }

    private static async Task<Created<RecurringTaskResponse>> Create(
        CreateRecurringTaskRequest req,
        AppDbContext db,
        ICurrentUser currentUser,
        CancellationToken ct)
    {
        var userId = currentUser.Id;
        var startDate = req.Task.PlannedDate ?? DateOnly.FromDateTime(DateTime.UtcNow);

        var rule = new RecurrenceRule
        {
            Id = Guid.CreateVersion7(),
            UserId = userId,
            Frequency = req.Rule.Frequency,
            IntervalValue = req.Rule.Interval < 1 ? 1 : req.Rule.Interval,
            DaysOfWeek = req.Rule.DaysOfWeek,
            EndType = req.Rule.EndType,
            EndCount = req.Rule.EndType == RecurrenceEndType.Count ? req.Rule.EndCount : null,
            EndDate = req.Rule.EndType == RecurrenceEndType.Date ? req.Rule.EndDate : null,
            GeneratedUntil = null,
        };
        db.Add(rule);

        var template = new TaskItem
        {
            Id = req.Task.Id ?? Guid.CreateVersion7(),
            UserId = userId,
            Title = req.Task.Title,
            Notes = req.Task.Notes,
            ProjectId = req.Task.ProjectId,
            Labels = req.Task.Labels,
            Source = req.Task.Source,
            Status = TaskStatus.Planned,
            PlannedDate = startDate,
            DurationMinutes = req.Task.DurationMinutes <= 0 ? 30 : req.Task.DurationMinutes,
            ActualTimeMinutes = 0,
            Priority = req.Task.Priority,
            ReminderMinutes = req.Task.ReminderMinutes ?? 0,
            Rank = "a0",
            RecurrenceRuleId = rule.Id,
            IsRecurrenceTemplate = true,
            RecurrenceDetached = false,
            RecurrenceOriginalDate = startDate,
        };
        db.Add(template);

        await db.SaveChangesAsync(ct);

        var dto = new RecurringTaskResponse(template.Id, template.Title, rule.Id, true, template.PlannedDate);
        return TypedResults.Created($"/api/v1/tasks/{template.Id}", dto);
    }

    private static async Task<Results<NoContent, NotFound>> UpdateSeries(
        Guid ruleId,
        UpdateSeriesRequest req,
        AppDbContext db,
        ICurrentUser currentUser,
        CancellationToken ct)
    {
        var ruleExists = await db.Set<RecurrenceRule>().AnyAsync(r => r.Id == ruleId, ct);
        if (!ruleExists) return TypedResults.NotFound();

        var today = DateOnly.FromDateTime(DateTime.UtcNow);

        // Propagate to the template (always) + future, non-detached, not-done live instances.
        var targets = await db.Set<TaskItem>()
            .Where(t => t.RecurrenceRuleId == ruleId
                        && (t.IsRecurrenceTemplate
                            || (!t.RecurrenceDetached && t.Status != TaskStatus.Done && t.PlannedDate >= today)))
            .ToListAsync(ct);

        foreach (var t in targets)
        {
            if (req.Title is not null) t.Title = req.Title;
            if (req.Notes is not null) t.Notes = req.Notes;
            if (req.ProjectId is not null) t.ProjectId = req.ProjectId;
            if (req.Labels is not null) t.Labels = req.Labels;
            if (req.DurationMinutes is not null) t.DurationMinutes = req.DurationMinutes.Value;
            if (req.Priority is not null) t.Priority = req.Priority;
            if (req.ReminderMinutes is not null) t.ReminderMinutes = req.ReminderMinutes;
        }

        await db.SaveChangesAsync(ct);
        return TypedResults.NoContent();
    }

    private static async Task<Results<NoContent, NotFound>> DeleteSeries(
        Guid ruleId,
        AppDbContext db,
        ICurrentUser currentUser,
        CancellationToken ct)
    {
        var rule = await db.Set<RecurrenceRule>().FirstOrDefaultAsync(r => r.Id == ruleId, ct);
        if (rule is null) return TypedResults.NotFound();

        var now = DateTimeOffset.UtcNow;

        // SOFT delete: tombstone the rule, all its tasks (template + instances),
        // and all its exceptions. ExecuteUpdate is allowed because the change_seq /
        // updated_at TRIGGER (not an interceptor) advances on bulk writes too.
        await db.Set<TaskItem>()
            .Where(t => t.RecurrenceRuleId == ruleId)
            .ExecuteUpdateAsync(s => s.SetProperty(t => t.DeletedAt, now), ct);

        await db.Set<RecurrenceException>()
            .Where(e => e.RecurrenceRuleId == ruleId)
            .ExecuteUpdateAsync(s => s.SetProperty(e => e.DeletedAt, now), ct);

        rule.DeletedAt = now;
        await db.SaveChangesAsync(ct);

        return TypedResults.NoContent();
    }

    private static async Task<Results<NoContent, NotFound>> DeleteSeriesFuture(
        Guid ruleId,
        DeleteSeriesFutureRequest req,
        AppDbContext db,
        ICurrentUser currentUser,
        CancellationToken ct)
    {
        var rule = await db.Set<RecurrenceRule>().FirstOrDefaultAsync(r => r.Id == ruleId, ct);
        if (rule is null) return TypedResults.NotFound();

        var now = DateTimeOffset.UtcNow;

        // SOFT delete future non-template, not-done instances on/after fromDate.
        await db.Set<TaskItem>()
            .Where(t => t.RecurrenceRuleId == ruleId
                        && !t.IsRecurrenceTemplate
                        && t.Status != TaskStatus.Done
                        && t.PlannedDate >= req.FromDate)
            .ExecuteUpdateAsync(s => s.SetProperty(t => t.DeletedAt, now), ct);

        // Cap the rule so future generation stops: EndType=Date, EndDate = fromDate - 1 day.
        rule.EndType = RecurrenceEndType.Date;
        rule.EndDate = req.FromDate.AddDays(-1);
        rule.EndCount = null;
        await db.SaveChangesAsync(ct);

        return TypedResults.NoContent();
    }

    private static async Task<Results<NoContent, NotFound>> DetachInstance(
        Guid taskId,
        AppDbContext db,
        ICurrentUser currentUser,
        CancellationToken ct)
    {
        var task = await db.Set<TaskItem>()
            .FirstOrDefaultAsync(t => t.Id == taskId && !t.IsRecurrenceTemplate, ct);
        if (task is null) return TypedResults.NotFound();

        task.RecurrenceDetached = true;
        await db.SaveChangesAsync(ct);
        return TypedResults.NoContent();
    }

    // TEMPORARY STUB — replaced in P5-8 (kept at the bottom of the class):
    private static void MapEnsureInstances(RouteGroupBuilder group) { }
}
