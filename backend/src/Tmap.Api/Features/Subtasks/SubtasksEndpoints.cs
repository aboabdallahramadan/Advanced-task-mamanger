using Microsoft.AspNetCore.Http.HttpResults;
using Microsoft.EntityFrameworkCore;
using Tmap.Api.Common;
using Tmap.Api.Common.Validation;
using Tmap.Api.Features.Tasks;
using Tmap.Api.Infrastructure;
using Tmap.Api.Infrastructure.Entities;

namespace Tmap.Api.Features.Subtasks;

public static class SubtasksEndpoints
{
    public static IEndpointRouteBuilder MapSubtasks(this IEndpointRouteBuilder app)
    {
        var nested = app.MapGroup("/api/v1/tasks/{taskId:guid}/subtasks").RequireAuthorization();
        nested.MapPost("/", CreateAsync)
            .AddEndpointFilter<ValidationFilter<CreateSubtaskRequest>>();

        var flat = app.MapGroup("/api/v1/subtasks").RequireAuthorization();
        flat.MapPatch("/{id:guid}", UpdateAsync)
            .AddEndpointFilter<ValidationFilter<UpdateSubtaskRequest>>();
        flat.MapDelete("/{id:guid}", DeleteAsync);

        return app;
    }

    internal static async Task<Results<Created<SubtaskResponse>, NotFound, ValidationProblem>> CreateAsync(
        Guid taskId,
        CreateSubtaskRequest req,
        AppDbContext db,
        ICurrentUser user,
        CancellationToken ct)
    {
        // parent must be visible to the caller (tenant filter) else 404
        var parentExists = await db.Tasks.AnyAsync(t => t.Id == taskId, ct);
        if (!parentExists)
        {
            return TypedResults.NotFound();
        }

        var maxOrder = await db.Subtasks
            .Where(s => s.TaskId == taskId)
            .Select(s => (int?)s.SortOrder)
            .MaxAsync(ct) ?? -1;

        var entity = new Subtask
        {
            Id = Guid.CreateVersion7(),
            UserId = user.Id,
            TaskId = taskId,
            Title = req.Title,
            Completed = false,
            SortOrder = maxOrder + 1,
        };

        db.Subtasks.Add(entity);
        await db.SaveChangesAsync(ct);

        return TypedResults.Created(
            $"/api/v1/subtasks/{entity.Id}",
            TasksEndpoints.ToSubtaskResponse(entity));
    }

    internal static async Task<Results<Ok<SubtaskResponse>, NotFound, ValidationProblem>> UpdateAsync(
        Guid id,
        UpdateSubtaskRequest req,
        AppDbContext db,
        ICurrentUser user,
        CancellationToken ct)
    {
        var sub = await db.Subtasks.FirstOrDefaultAsync(s => s.Id == id, ct);
        if (sub is null)
        {
            return TypedResults.NotFound();
        }

        if (req.Title is not null)
        {
            sub.Title = req.Title;
        }

        if (req.Completed is { } c)
        {
            sub.Completed = c;
        }

        if (req.SortOrder is { } order)
        {
            sub.SortOrder = order;
        }

        await db.SaveChangesAsync(ct);
        return TypedResults.Ok(TasksEndpoints.ToSubtaskResponse(sub));
    }

    internal static async Task<Results<NoContent, NotFound>> DeleteAsync(
        Guid id,
        AppDbContext db,
        ICurrentUser user,
        CancellationToken ct)
    {
        var sub = await db.Subtasks.FirstOrDefaultAsync(s => s.Id == id, ct);
        if (sub is null)
        {
            return TypedResults.NotFound();
        }

        sub.DeletedAt = DateTimeOffset.UtcNow;   // soft delete only
        await db.SaveChangesAsync(ct);
        return TypedResults.NoContent();
    }
}
