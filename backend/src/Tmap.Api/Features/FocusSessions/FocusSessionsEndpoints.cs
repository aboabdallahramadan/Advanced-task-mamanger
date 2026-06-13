using Microsoft.AspNetCore.Http.HttpResults;
using Tmap.Api.Common;
using Tmap.Api.Common.Validation;
using Tmap.Api.Infrastructure;
using Tmap.Api.Infrastructure.Entities;

namespace Tmap.Api.Features.FocusSessions;

public static class FocusSessionsEndpoints
{
    public static IEndpointRouteBuilder MapFocusSessionsEndpoints(this IEndpointRouteBuilder app)
    {
        var group = app.MapGroup("focus-sessions").RequireAuthorization();

        group.MapPost("/", Add)
            .AddEndpointFilter<ValidationFilter<CreateFocusSessionRequest>>()
            .WithName("AddFocusSession");

        return app;
    }

    private static async Task<Results<Created<FocusSessionResponse>, Ok<FocusSessionResponse>>> Add(
        CreateFocusSessionRequest req,
        AppDbContext db,
        ICurrentUser currentUser,
        CancellationToken ct)
    {
        // Idempotent replay: a focus session with this client id already exists → 200 + its DTO.
        // (Without this, the offline op queue replaying a create would silently duplicate the session.)
        if (req.Id is { } reqId && reqId != Guid.Empty)
        {
            var existing = await CreateConflict.FindExistingByIdAsync(
                db.FocusSessions, f => f.Id == reqId, ct);
            if (existing is not null)
            {
                return TypedResults.Ok(ToResponse(existing));
            }
        }

        var entity = new FocusSession
        {
            Id = req.Id is { } id && id != Guid.Empty ? id : Guid.CreateVersion7(),
            UserId = currentUser.Id, // interceptor also stamps; explicit for clarity
            TaskId = req.TaskId,
            Project = req.Project,
            StartedAt = req.StartedAt,
            EndedAt = req.EndedAt,
            Minutes = req.Minutes,
            Date = req.Date,
        };
        db.Add(entity);
        await db.SaveChangesAsync(ct);

        var dto = ToResponse(entity);
        return TypedResults.Created($"/api/v1/focus-sessions/{entity.Id}", dto);
    }

    private static FocusSessionResponse ToResponse(FocusSession e) =>
        new(e.Id, e.TaskId, e.Project, e.StartedAt, e.EndedAt, e.Minutes, e.Date, e.CreatedAt, e.UpdatedAt);
}
