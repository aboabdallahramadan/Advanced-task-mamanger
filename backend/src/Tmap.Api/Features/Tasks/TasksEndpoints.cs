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
        var group = app.MapGroup("/api/v1/tasks").RequireAuthorization();

        group.MapPost("/", CreateAsync)
            .AddEndpointFilter<ValidationFilter<CreateTaskRequest>>();

        group.MapGet("/", GetAsync);

        return group;
    }

    internal static async Task<Results<Created<TaskResponse>, ValidationProblem>> CreateAsync(
        CreateTaskRequest req,
        AppDbContext db,
        ICurrentUser user,
        CancellationToken ct)
    {
        var entity = new TaskItem
        {
            Id = req.Id is { } id && id != Guid.Empty ? id : Guid.CreateVersion7(),
            // UserId is stamped by the write-time ownership interceptor (P1); set here too for clarity.
            UserId = user.Id,
            Title = req.Title,
            Notes = req.Notes ?? string.Empty,
            ProjectId = req.ProjectId,
            Labels = req.Labels ?? [],
            Source = req.Source ?? string.Empty,
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
            query = query.Where(t => t.Status == parsed);

        if (date is { } d)
            query = query.Where(t => t.PlannedDate == d);

        if (!string.IsNullOrWhiteSpace(q))
        {
            var pattern = $"%{q.Trim()}%";
            query = query.Where(t =>
                EF.Functions.ILike(t.Title, pattern) ||
                EF.Functions.ILike(t.Notes, pattern));
        }

        var rows = await query.OrderBy(t => t.Rank).ToListAsync(ct);

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

    // --- helpers ---

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
