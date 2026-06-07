using Microsoft.AspNetCore.Http.HttpResults;
using Microsoft.EntityFrameworkCore;
using Tmap.Api.Common;
using Tmap.Api.Common.Validation;
using Tmap.Api.Infrastructure;
using Tmap.Api.Infrastructure.Entities;

namespace Tmap.Api.Features.Settings;

public static class SettingsEndpoints
{
    public static IEndpointRouteBuilder MapSettingsEndpoints(this IEndpointRouteBuilder app)
    {
        var group = app.MapGroup("settings").RequireAuthorization();

        group.MapGet("/", Get).WithName("GetSettings");
        group.MapPut("/", Save)
            .AddEndpointFilter<ValidationFilter<SaveSettingsRequest>>()
            .WithName("SaveSettings");

        return app;
    }

    // Captured as a concrete list so EF Core can translate it to a SQL IN (...) clause.
    private static readonly List<string> _allowedKeys = SyncedSettingKeys.Allowed.ToList();

    private static async Task<Ok<SettingsResponse>> Get(
        AppDbContext db,
        ICurrentUser currentUser,
        CancellationToken ct)
    {
        var userId = currentUser.Id;

        var rows = await db.Set<UserSetting>()
            .AsNoTracking()
            .Where(s => _allowedKeys.Contains(s.Key))
            .Select(s => new { s.Key, s.Value })
            .ToListAsync(ct);

        var settings = rows.ToDictionary(r => r.Key, r => r.Value, StringComparer.Ordinal);

        // TimeZoneId lives on ApplicationUser (AspNetUsers); read directly to avoid
        // depending on UserManager here. Default 'UTC' if the column is null.
        var tz = await db.Users
            .Where(u => u.Id == userId)
            .Select(u => u.TimeZoneId)
            .SingleAsync(ct);

        return TypedResults.Ok(new SettingsResponse(settings, string.IsNullOrEmpty(tz) ? "UTC" : tz));
    }

    private static async Task<Ok<SettingsResponse>> Save(
        SaveSettingsRequest req,
        AppDbContext db,
        ICurrentUser currentUser,
        CancellationToken ct)
    {
        var userId = currentUser.Id;

        var incoming = req.Settings
            .Where(kv => SyncedSettingKeys.Allowed.Contains(kv.Key))
            .ToList();

        if (incoming.Count > 0)
        {
            var keys = incoming.Select(kv => kv.Key).ToList();
            // Bypass ONLY the soft-delete filter so a tombstoned row with the same composite PK
            // (UserId, Key) is found and revived in place — keep the Tenant filter for isolation.
            var existing = await db.Set<UserSetting>()
                .IgnoreQueryFilters([AppDbContext.SoftDeleteFilter])
                .Where(s => keys.Contains(s.Key))
                .ToDictionaryAsync(s => s.Key, ct);

            foreach (var (key, value) in incoming)
            {
                if (existing.TryGetValue(key, out var row))
                {
                    // Clear DeletedAt to revive the row if it was a tombstone.
                    row.DeletedAt = null;
                    row.Value = value;
                }
                else
                {
                    db.Add(new UserSetting
                    {
                        UserId = userId,
                        Key = key,
                        Value = value,
                    });
                }
            }

            await db.SaveChangesAsync(ct);
        }

        // Persist TimeZoneId to ApplicationUser when the caller provides it.
        // Validation (FindSystemTimeZoneById) has already run in the ValidationFilter.
        if (!string.IsNullOrEmpty(req.TimeZoneId))
        {
            var user = await db.Users.SingleAsync(u => u.Id == userId, ct);
            user.TimeZoneId = req.TimeZoneId;
            await db.SaveChangesAsync(ct);
        }

        // Return the full current synced view (mirrors settings.get).
        return await Get(db, currentUser, ct);
    }
}
