using Microsoft.AspNetCore.Http.HttpResults;
using Microsoft.EntityFrameworkCore;
using Tmap.Api.Common;
using Tmap.Api.Common.Validation;
using Tmap.Api.Infrastructure;
using Tmap.Api.Infrastructure.Entities;

namespace Tmap.Api.Features.DailyPlans;

public static class DailyPlansEndpoints
{
    public static IEndpointRouteBuilder MapDailyPlansEndpoints(this IEndpointRouteBuilder app)
    {
        var group = app.MapGroup("daily-plans").RequireAuthorization();

        group.MapGet("/{date}", Get).WithName("GetDailyPlan");
        group.MapPut("/{date}", Upsert)
            .AddEndpointFilter<ValidationFilter<UpsertDailyPlanRequest>>()
            .WithName("UpsertDailyPlan");

        return app;
    }

    private static async Task<Results<Ok<DailyPlanResponse>, NotFound>> Get(
        DateOnly date,
        AppDbContext db,
        ICurrentUser currentUser,
        CancellationToken ct)
    {
        // Tenant + soft-delete filters apply automatically.
        var plan = await db.Set<DailyPlan>()
            .AsNoTracking()
            .FirstOrDefaultAsync(p => p.Date == date, ct);

        return plan is null ? TypedResults.NotFound() : TypedResults.Ok(ToResponse(plan));
    }

    private static async Task<Ok<DailyPlanResponse>> Upsert(
        DateOnly date,
        UpsertDailyPlanRequest req,
        AppDbContext db,
        ICurrentUser currentUser,
        CancellationToken ct)
    {
        var userId = currentUser.Id;
        // Bypass ONLY the soft-delete filter so a tombstoned row with the same composite PK
        // (UserId, Date) is found and revived in place — keep the Tenant filter for isolation.
        var plan = await db.Set<DailyPlan>()
            .IgnoreQueryFilters([AppDbContext.SoftDeleteFilter])
            .FirstOrDefaultAsync(p => p.Date == date, ct);

        if (plan is null)
        {
            plan = new DailyPlan
            {
                UserId = userId,
                Date = date,
                CommittedAt = DateTimeOffset.UtcNow,
                PlannedTaskIds = req.PlannedTaskIds,
                PlannedMinutes = req.PlannedMinutes,
            };
            db.Add(plan);
        }
        else
        {
            // whole-day last-writer-wins: replace the array, do not merge.
            // Clear DeletedAt to revive the row if it was a tombstone.
            plan.DeletedAt = null;
            plan.CommittedAt = DateTimeOffset.UtcNow;
            plan.PlannedTaskIds = req.PlannedTaskIds;
            plan.PlannedMinutes = req.PlannedMinutes;
        }

        await db.SaveChangesAsync(ct);
        return TypedResults.Ok(ToResponse(plan));
    }

    private static DailyPlanResponse ToResponse(DailyPlan p) =>
        new(p.Date, p.CommittedAt, p.PlannedTaskIds, p.PlannedMinutes, p.CreatedAt, p.UpdatedAt);
}
