using Microsoft.AspNetCore.Http.HttpResults;
using Microsoft.EntityFrameworkCore;
using Tmap.Api.Common;
using Tmap.Api.Common.Validation;
using Tmap.Api.Features.Subtasks;
using Tmap.Api.Features.Tasks;
using Tmap.Api.Infrastructure;
using Tmap.Api.Infrastructure.Entities;
using TaskStatus = Tmap.Api.Infrastructure.Entities.TaskStatus;

namespace Tmap.Api.Features.Recurrence;

public static class RecurrenceEndpoints
{
    public static IEndpointRouteBuilder MapRecurrenceEndpoints(this IEndpointRouteBuilder app)
    {
        var group = app.MapGroup("recurrence").RequireAuthorization();

        group.MapGet("/rules/{ruleId:guid}", GetRule).WithName("GetRecurrenceRule");
        group.MapPatch("/rules/{ruleId:guid}", UpdateRule)
            .AddEndpointFilter<ValidationFilter<UpdateRuleRequest>>()
            .WithName("UpdateRecurrenceRule");

        // (create, updateSeries, deleteSeries, deleteSeriesFuture, detachInstance,
        //  ensure-instances are mapped in P5-7 / P5-8 — added to THIS group.)
        MapSeriesOperations(group);
        MapEnsureInstancesImpl(group);

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
        if (rule is null)
        {
            return TypedResults.NotFound();
        }

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

    private static async Task<Results<Created<List<TaskResponse>>, ValidationProblem>> Create(
        CreateRecurringTaskRequest req,
        AppDbContext db,
        ICurrentUser currentUser,
        CancellationToken ct)
    {
        var userId = currentUser.Id;

        // WRITE-side ownership: the template task's ProjectId must belong to the caller
        // (tenant-filtered Projects means a non-match = not the caller's).
        if (req.Task.ProjectId is { } pid && !await db.Projects.AnyAsync(p => p.Id == pid, ct))
        {
            return TypedResults.ValidationProblem(new Dictionary<string, string[]>
            {
                ["projectId"] = ["projectId does not reference one of your projects."],
            });
        }

        var startDate = req.Task.PlannedDate ?? DateOnly.FromDateTime(DateTime.UtcNow);

        var rule = new RecurrenceRule
        {
            Id = req.Rule.Id is { } rid && rid != Guid.Empty ? rid : Guid.CreateVersion7(),
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

        var taskResponse = TasksEndpoints.ToResponse(template, new List<SubtaskResponse>());
        return TypedResults.Created($"/api/v1/tasks/{template.Id}", new List<TaskResponse> { taskResponse });
    }

    private static async Task<Results<NoContent, NotFound>> UpdateSeries(
        Guid ruleId,
        UpdateSeriesRequest req,
        AppDbContext db,
        ICurrentUser currentUser,
        CancellationToken ct)
    {
        var ruleExists = await db.Set<RecurrenceRule>().AnyAsync(r => r.Id == ruleId, ct);
        if (!ruleExists)
        {
            return TypedResults.NotFound();
        }

        var today = DateOnly.FromDateTime(DateTime.UtcNow);

        // Propagate to the template (always) + future, non-detached, not-done live instances.
        var targets = await db.Set<TaskItem>()
            .Where(t => t.RecurrenceRuleId == ruleId
                        && (t.IsRecurrenceTemplate
                            || (!t.RecurrenceDetached && t.Status != TaskStatus.Done && t.PlannedDate >= today)))
            .ToListAsync(ct);

        foreach (var t in targets)
        {
            if (req.Title is not null)
            {
                t.Title = req.Title;
            }

            if (req.Notes is not null)
            {
                t.Notes = req.Notes;
            }

            if (req.ProjectId is not null)
            {
                t.ProjectId = req.ProjectId;
            }

            if (req.Labels is not null)
            {
                t.Labels = req.Labels;
            }

            if (req.DurationMinutes is not null)
            {
                t.DurationMinutes = req.DurationMinutes.Value;
            }

            if (req.Priority is not null)
            {
                t.Priority = req.Priority;
            }

            if (req.ReminderMinutes is not null)
            {
                t.ReminderMinutes = req.ReminderMinutes;
            }
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
        if (rule is null)
        {
            return TypedResults.NotFound();
        }

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
        if (rule is null)
        {
            return TypedResults.NotFound();
        }

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
        if (task is null)
        {
            return TypedResults.NotFound();
        }

        task.RecurrenceDetached = true;
        await db.SaveChangesAsync(ct);
        return TypedResults.NoContent();
    }

    private static void MapEnsureInstancesImpl(RouteGroupBuilder group)
    {
        group.MapPost("/ensure-instances", EnsureInstances).WithName("EnsureRecurrenceInstances");
    }

    private static async Task<Results<Ok<List<TaskResponse>>, ValidationProblem>> EnsureInstances(
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
        var created = new List<TaskItem>();

        // One transaction for the whole call; advisory locks are xact-scoped.
        await using var tx = await db.Database.BeginTransactionAsync(ct);

        // Rules needing generation extended (live only; tenant filter scopes to user).
        var ruleIds = await db.Set<RecurrenceRule>()
            .Where(r => r.GeneratedUntil == null || r.GeneratedUntil < end)
            .Select(r => r.Id)
            .ToListAsync(ct);

        foreach (var ruleId in ruleIds)
        {
            // Per-rule advisory lock: two concurrent calls serialize here, so the
            // "read existing dates -> insert missing" sequence is never interleaved.
            // Key the lock on the low 63 bits of the rule's GUID for a stable bigint.
            var lockKey = AdvisoryLockKey(ruleId);
            await db.Database.ExecuteSqlInterpolatedAsync(
                $"SELECT pg_advisory_xact_lock({lockKey})", ct);

            var rule = await db.Set<RecurrenceRule>().FirstOrDefaultAsync(r => r.Id == ruleId, ct);
            if (rule is null)
            {
                continue; // tombstoned between the list query and the lock
            }

            var template = await db.Set<TaskItem>()
                .FirstOrDefaultAsync(t => t.RecurrenceRuleId == ruleId && t.IsRecurrenceTemplate, ct);
            if (template is null)
            {
                continue;
            }

            // Live instance dates already present (ignore tombstones so a deleted
            // occurrence is NOT regenerated — it is treated as "exists").
            var existingDates = await db.Set<TaskItem>()
                .IgnoreQueryFilters(["SoftDelete"])
                .Where(t => t.RecurrenceRuleId == ruleId
                            && !t.IsRecurrenceTemplate
                            && t.RecurrenceOriginalDate != null)
                .Select(t => t.RecurrenceOriginalDate!.Value)
                .ToListAsync(ct);

            var exceptionDates = await db.Set<RecurrenceException>()
                .Where(e => e.RecurrenceRuleId == ruleId)
                .Select(e => e.ExceptionDate)
                .ToListAsync(ct);

            var ruleData = new OccurrenceRuleData(
                rule.Frequency, rule.IntervalValue, rule.DaysOfWeek,
                rule.EndType, rule.EndCount, rule.EndDate);

            var templateStart = template.RecurrenceOriginalDate ?? template.PlannedDate ?? start;

            var dates = OccurrenceGenerator.Generate(
                ruleData,
                templateStart: templateStart,
                rangeStart: start,
                rangeEnd: end,
                existingDates: new HashSet<DateOnly>(existingDates),
                exceptionDates: new HashSet<DateOnly>(exceptionDates));

            foreach (var date in dates)
            {
                var instance = new TaskItem
                {
                    Id = Guid.CreateVersion7(),
                    UserId = userId,
                    Title = template.Title,
                    Notes = template.Notes,
                    ProjectId = template.ProjectId,
                    Labels = new List<string>(template.Labels),
                    Source = template.Source,
                    Status = TaskStatus.Planned,
                    PlannedDate = date,
                    ScheduledStart = template.ScheduledStart,
                    ScheduledEnd = template.ScheduledEnd,
                    DurationMinutes = template.DurationMinutes,
                    ActualTimeMinutes = 0,
                    Priority = template.Priority,
                    ReminderMinutes = template.ReminderMinutes,
                    Rank = template.Rank,
                    RecurrenceRuleId = ruleId,
                    IsRecurrenceTemplate = false,
                    RecurrenceDetached = false,
                    RecurrenceOriginalDate = date,
                };
                db.Add(instance);
                created.Add(instance);
            }

            // Advance the generation cursor idempotently.
            if (rule.GeneratedUntil == null || rule.GeneratedUntil < end)
            {
                rule.GeneratedUntil = end;
            }

            await db.SaveChangesAsync(ct);
        }

        await tx.CommitAsync(ct);

        // Load subtasks for all created instances in one query.
        var createdIds = created.Select(t => t.Id).ToList();
        var subtasksMap = await db.Set<Subtask>()
            .Where(s => createdIds.Contains(s.TaskId))
            .ToListAsync(ct);
        var subByTask = subtasksMap.GroupBy(s => s.TaskId)
            .ToDictionary(g => g.Key, g => g.ToList());

        return TypedResults.Ok(created.Select(t =>
            TasksEndpoints.ToResponse(t, subByTask.GetValueOrDefault(t.Id, new())
                .Select(TasksEndpoints.ToSubtaskResponse).ToList())).ToList());
    }

    /// <summary>
    /// Stable signed 64-bit key for pg_advisory_xact_lock derived from a GUID.
    /// Uses the first 8 bytes; collisions only cost extra serialization, never
    /// correctness (idempotency is also guaranteed by the existing-date skip).
    /// </summary>
    private static long AdvisoryLockKey(Guid id)
    {
        Span<byte> bytes = stackalloc byte[16];
        id.TryWriteBytes(bytes);
        return BitConverter.ToInt64(bytes[..8]);
    }
}
