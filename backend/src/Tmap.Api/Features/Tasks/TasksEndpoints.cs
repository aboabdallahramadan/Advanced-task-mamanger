using Microsoft.AspNetCore.Http.HttpResults;
using Microsoft.EntityFrameworkCore;
using Tmap.Api.Common;
using Tmap.Api.Common.Validation;
using Tmap.Api.Features.Subtasks;
using Tmap.Api.Infrastructure;
using Tmap.Api.Infrastructure.Entities;
using TaskStatus = Tmap.Api.Infrastructure.Entities.TaskStatus;

namespace Tmap.Api.Features.Tasks;

public static class TasksEndpoints
{
    public static RouteGroupBuilder MapTasks(this IEndpointRouteBuilder app)
    {
        var group = app.MapGroup("tasks").RequireAuthorization();

        group.MapPost("/", CreateAsync)
            .AddEndpointFilter<ValidationFilter<CreateTaskRequest>>();

        group.MapGet("/", GetAsync);

        group.MapPatch("/reorder", ReorderAsync)
            .AddEndpointFilter<ValidationFilter<IReadOnlyList<ReorderItem>>>();

        group.MapPatch("/{id:guid}", UpdateAsync)
            .AddEndpointFilter<ValidationFilter<UpdateTaskRequest>>();

        group.MapDelete("/{id:guid}", DeleteAsync);

        return group;
    }

    internal static async Task<Results<Created<TaskResponse>, Ok<TaskResponse>, ValidationProblem>> CreateAsync(
        CreateTaskRequest req,
        AppDbContext db,
        ICurrentUser user,
        CancellationToken ct)
    {
        // Idempotent replay: an existing owned row with this id (live or tombstoned) → 200 + its DTO.
        if (req.Id is { } reqId && reqId != Guid.Empty)
        {
            var existing = await CreateConflict.FindExistingByIdAsync(
                db.Tasks.Include(t => t.Subtasks), t => t.Id == reqId, ct);
            if (existing is not null)
            {
                var existingSubtasks = existing.Subtasks
                    .Where(st => st.DeletedAt == null)
                    .OrderBy(st => st.SortOrder)
                    .Select(ToSubtaskResponse)
                    .ToList();
                return TypedResults.Ok(ToResponse(existing, existingSubtasks));
            }
        }

        // WRITE-side ownership: tenant-filtered Projects means a non-match = not the caller's.
        if (req.ProjectId is { } pid && !await db.Projects.AnyAsync(p => p.Id == pid, ct))
        {
            return ProjectNotOwned();
        }

        var entity = new TaskItem
        {
            Id = req.Id is { } id && id != Guid.Empty ? id : Guid.CreateVersion7(),
            // UserId is stamped by the write-time ownership interceptor (P1); set here too for clarity.
            UserId = user.Id,
            Title = req.Title,
            Notes = req.Notes ?? string.Empty,
            ProjectId = req.ProjectId,
            Labels = req.Labels ?? [],
            Source = req.Source ?? "local",
            Status = req.Status ?? TaskStatus.Inbox,
            PlannedDate = req.PlannedDate,
            ScheduledStart = req.ScheduledStart,
            ScheduledEnd = req.ScheduledEnd,
            DurationMinutes = req.DurationMinutes ?? 0,
            ActualTimeMinutes = 0,
            Priority = req.Priority,
            ReminderMinutes = req.ReminderMinutes,
            Rank = !string.IsNullOrEmpty(req.Rank)
                ? req.Rank
                : await NextRankAsync(db, user.Id, ct),
            DueDate = req.DueDate,
            IsRecurrenceTemplate = false,
            RecurrenceDetached = false,
        };

        db.Tasks.Add(entity);
        await db.SaveChangesAsync(ct);

        var response = ToResponse(entity, []);
        return TypedResults.Created($"/api/v1/tasks/{entity.Id}", response);
    }

    internal static async Task<Ok<List<TaskResponse>>> GetAsync(
        AppDbContext db,
        ICurrentUser user,
        CancellationToken ct,
        string? status = null,
        DateOnly? date = null,
        string? q = null)
    {
        var query = db.Tasks.Include(t => t.Subtasks).AsQueryable();

        if (!string.IsNullOrWhiteSpace(status) && Enum.TryParse<TaskStatus>(status, ignoreCase: true, out var parsed))
        {
            query = query.Where(t => t.Status == parsed);
        }

        if (date is { } d)
        {
            query = query.Where(t => t.PlannedDate == d);
        }

        if (!string.IsNullOrWhiteSpace(q))
        {
            var pattern = $"%{q.Trim()}%";
            query = query.Where(t =>
                EF.Functions.ILike(t.Title, pattern) ||
                EF.Functions.ILike(t.Notes, pattern));
        }

        var rows = await query
            .OrderBy(t => EF.Functions.Collate(t.Rank, "C"))
            .ThenBy(t => t.Id)
            .ToListAsync(ct);

        var result = rows
            .Select(t => ToResponse(
                t,
                t.Subtasks
                    .Where(s => s.DeletedAt == null)
                    .OrderBy(s => s.SortOrder)
                    .Select(ToSubtaskResponse)
                    .ToList()))
            .ToList();

        return TypedResults.Ok(result);
    }

    internal static async Task<Results<Ok<TaskResponse>, NotFound, ValidationProblem>> UpdateAsync(
        Guid id,
        UpdateTaskRequest req,
        AppDbContext db,
        ICurrentUser user,
        CancellationToken ct)
    {
        var task = await db.Tasks
            .Include(t => t.Subtasks)
            .FirstOrDefaultAsync(t => t.Id == id, ct);
        if (task is null)
        {
            return TypedResults.NotFound();
        }

        // WRITE-side ownership: validate the (re)assigned project belongs to the caller.
        // Only when a non-null ProjectId is explicitly supplied (an explicit null CLEARS it).
        if (req.ProjectId is { HasValue: true, Value: { } pid }
            && !await db.Projects.AnyAsync(p => p.Id == pid, ct))
        {
            return ProjectNotOwned();
        }

        if (req.Title is not null)
        {
            task.Title = req.Title;
        }

        if (req.Notes is not null)
        {
            task.Notes = req.Notes;
        }

        // Optional<T?>: present (even explicit null) overwrites; absent leaves unchanged.
        if (req.ProjectId.HasValue)
        {
            task.ProjectId = req.ProjectId.Value;
        }

        if (req.Labels is not null)
        {
            task.Labels = req.Labels;
        }

        if (req.Source is not null)
        {
            task.Source = req.Source;
        }

        if (req.Status is { } s)
        {
            task.Status = s;
        }

        if (req.PlannedDate.HasValue)
        {
            task.PlannedDate = req.PlannedDate.Value;
        }

        if (req.ScheduledStart.HasValue)
        {
            task.ScheduledStart = req.ScheduledStart.Value;
        }

        if (req.ScheduledEnd.HasValue)
        {
            task.ScheduledEnd = req.ScheduledEnd.Value;
        }

        if (req.DurationMinutes is { } dm)
        {
            task.DurationMinutes = dm;
        }

        if (req.ActualTimeMinutes is { } am)
        {
            task.ActualTimeMinutes = am;
        }

        if (req.Priority.HasValue)
        {
            task.Priority = req.Priority.Value;
        }

        if (req.ReminderMinutes.HasValue)
        {
            task.ReminderMinutes = req.ReminderMinutes.Value;
        }

        if (!string.IsNullOrEmpty(req.Rank))
        {
            task.Rank = req.Rank;
        }

        if (req.DueDate is not null)
        {
            task.DueDate = req.DueDate;
        }

        if (req.CompletedAt is not null)
        {
            task.CompletedAt = req.CompletedAt;
        }

        await db.SaveChangesAsync(ct);

        var subtasks = task.Subtasks
            .Where(st => st.DeletedAt == null)
            .OrderBy(st => st.SortOrder)
            .Select(ToSubtaskResponse)
            .ToList();
        return TypedResults.Ok(ToResponse(task, subtasks));
    }

    internal static async Task<Results<NoContent, NotFound>> DeleteAsync(
        Guid id,
        AppDbContext db,
        ICurrentUser user,
        CancellationToken ct)
    {
        var task = await db.Tasks
            .Include(t => t.Subtasks)
            .FirstOrDefaultAsync(t => t.Id == id, ct);
        if (task is null)
        {
            return TypedResults.NotFound();
        }

        var now = DateTimeOffset.UtcNow;
        task.DeletedAt = now;
        foreach (var st in task.Subtasks.Where(s => s.DeletedAt == null))
        {
            st.DeletedAt = now;
        }

        await db.SaveChangesAsync(ct);
        return TypedResults.NoContent();
    }

    internal static async Task<Results<NoContent, ValidationProblem>> ReorderAsync(
        IReadOnlyList<ReorderItem> items,
        AppDbContext db,
        ICurrentUser user,
        CancellationToken ct)
    {
        var ids = items.Select(i => i.Id).ToList();
        var tasks = await db.Tasks.Where(t => ids.Contains(t.Id)).ToListAsync(ct);

        var byId = tasks.ToDictionary(t => t.Id);
        foreach (var item in items)
        {
            if (byId.TryGetValue(item.Id, out var task))
            {
                task.Rank = item.Rank;
            }
        }

        await db.SaveChangesAsync(ct);
        return TypedResults.NoContent();
    }

    // --- helpers ---

    /// <summary>RFC 9457 400 for a ProjectId that is not owned by the caller (tenant-filtered absence).</summary>
    private static ValidationProblem ProjectNotOwned() =>
        TypedResults.ValidationProblem(new Dictionary<string, string[]>
        {
            ["projectId"] = ["projectId does not reference one of your projects."],
        });

    /// <summary>Append a new rank after the user's current max task rank (simple end-append scheme).</summary>
    private static async Task<string> NextRankAsync(AppDbContext db, Guid userId, CancellationToken ct)
    {
        var maxRank = await db.Tasks
            .Where(t => t.UserId == userId)
            .OrderByDescending(t => t.Rank)
            .Select(t => t.Rank)
            .FirstOrDefaultAsync(ct);
        return Ranking.RankAfter(maxRank);
    }

    internal static TaskResponse ToResponse(TaskItem t, IReadOnlyList<SubtaskResponse> subtasks) => new(
        t.Id,
        t.Title,
        t.Notes,
        t.ProjectId,
        t.Labels,
        t.Source,
        t.Status,
        t.PlannedDate,
        t.ScheduledStart,
        t.ScheduledEnd,
        t.DurationMinutes,
        t.ActualTimeMinutes,
        t.Priority,
        t.ReminderMinutes,
        t.Rank,
        t.DueDate,
        t.RecurrenceRuleId,
        t.IsRecurrenceTemplate,
        t.RecurrenceDetached,
        t.RecurrenceOriginalDate,
        t.CompletedAt,
        t.CreatedAt,
        t.UpdatedAt,
        t.ChangeSeq,
        subtasks);

    internal static SubtaskResponse ToSubtaskResponse(Subtask s) => new(
        s.Id, s.TaskId, s.Title, s.Completed, s.SortOrder, s.CreatedAt, s.UpdatedAt);
}
