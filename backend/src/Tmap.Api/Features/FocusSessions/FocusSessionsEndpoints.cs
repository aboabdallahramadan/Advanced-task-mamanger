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
        var group = app.MapGroup("/api/v1/focus-sessions").RequireAuthorization();

        group.MapPost("/", Add)
            .AddEndpointFilter<ValidationFilter<CreateFocusSessionRequest>>()
            .WithName("AddFocusSession");

        return app;
    }

    private static async Task<Created<FocusSessionResponse>> Add(
        CreateFocusSessionRequest req,
        AppDbContext db,
        ICurrentUser currentUser,
        CancellationToken ct)
    {
        var entity = new FocusSession
        {
            Id = Guid.CreateVersion7(),
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
