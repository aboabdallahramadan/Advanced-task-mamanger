using Microsoft.AspNetCore.Http.HttpResults;
using Tmap.Api.Common;
using Tmap.Api.Infrastructure;

namespace Tmap.Api.Features.Sync;

public static class SyncEndpoints
{
    // Default and maximum page size; clamped in the handler.
    private const int DefaultLimit = 500;
    private const int MaxLimit = 500;

    public static IEndpointRouteBuilder MapSyncEndpoints(this IEndpointRouteBuilder app)
    {
        var group = app.MapGroup("sync").RequireAuthorization();
        group.MapGet("/", Pull).WithName("Sync");
        return app;
    }

    private static async Task<Ok<SyncResponse>> Pull(
        AppDbContext db,
        ICurrentUser user,
        CancellationToken ct,
        long since = 0,
        int? limit = null)
    {
        var pageSize = Math.Clamp(limit ?? DefaultLimit, 1, MaxLimit);
        _ = pageSize; // used in R0-3
        _ = since;
        _ = db;
        _ = user;
        await Task.CompletedTask;

        var changes = new SyncChanges(
            Tasks: [], Subtasks: [], Projects: [], NoteGroups: [], Notes: [],
            RecurrenceRules: [], FocusSessions: [], DailyPlans: [], Settings: []);
        return TypedResults.Ok(new SyncResponse(changes, since, false));
    }
}
