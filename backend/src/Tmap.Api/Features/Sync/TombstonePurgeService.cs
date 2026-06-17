using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using Tmap.Api.Common;
using Tmap.Api.Infrastructure;
using Tmap.Api.Infrastructure.Persistence;

namespace Tmap.Api.Features.Sync;

/// <summary>
/// Per-table count of hard-deleted tombstones for one purge pass, plus the resulting watermark.
/// </summary>
public sealed record PurgeResult(IReadOnlyDictionary<string, int> DeletedByTable, long Watermark)
{
    public int TotalDeleted => DeletedByTable.Values.Sum();
}

/// <summary>
/// Hard-deletes tombstones older than <c>Purge:RetentionDays</c> across the 10 trigger-bearing
/// synced tables and advances the <c>sync_purge_state</c> watermark monotonically. Runs ~daily.
///
/// ELEVATION (load-bearing, spec §5.1): the purge context is built with <see cref="SystemCurrentUser"/>
/// so the registered <see cref="UserIdConnectionInterceptor"/> pins <c>app.user_id</c> to the system
/// id (00000000-0000-0000-0000-000000000001) on the very connection ExecuteDelete opens — the RLS
/// policy's system-id escape hatch then lets a plain <c>app_user</c> (NOSUPERUSER NOBYPASSRLS) see and
/// delete across ALL tenants, and the EF Tenant filter no-ops. Without this elevation the interceptor
/// would CLEAR the GUC (no HttpContext → unauthenticated) and RLS would return zero rows: a silent no-op.
/// </summary>
public sealed class TombstonePurgeService(
    IServiceProvider services,
    IConfiguration configuration,
    IOptions<PurgeOptions> options,
    ILogger<TombstonePurgeService> logger) : BackgroundService
{
    private static readonly TimeSpan Period = TimeSpan.FromHours(24);
    private static readonly TimeSpan FirstRunDelay = TimeSpan.FromSeconds(10);

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        // First run a few seconds after startup (let the host settle / readiness pass), then ~daily.
        try
        {
            await Task.Delay(FirstRunDelay, stoppingToken);
        }
        catch (OperationCanceledException)
        {
            return;
        }

        using var timer = new PeriodicTimer(Period);
        do
        {
            try
            {
                await using var db = BuildElevatedContext();
                var result = await PurgeOnceAsync(db, options.Value.RetentionDays, logger, stoppingToken);
                logger.LogInformation(
                    "Tombstone purge complete: {Total} rows deleted; watermark now {Watermark}; per-table {@DeletedByTable}",
                    result.TotalDeleted, result.Watermark, result.DeletedByTable);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                return;
            }
            catch (Exception ex)
            {
                // Never let one failed pass kill the loop; the next tick retries.
                logger.LogError(ex, "Tombstone purge pass failed; will retry on the next tick.");
            }
        } while (await SafeWaitAsync(timer, stoppingToken));
    }

    private static async Task<bool> SafeWaitAsync(PeriodicTimer timer, CancellationToken token)
    {
        try
        {
            return await timer.WaitForNextTickAsync(token);
        }
        catch (OperationCanceledException)
        {
            return false;
        }
    }

    /// <summary>
    /// Builds a short-lived <see cref="AppDbContext"/> on the runtime connection
    /// (<c>ConnectionStrings:Postgres</c> = app_user) but with <see cref="SystemCurrentUser"/>, so the
    /// connection interceptor pins <c>app.user_id</c> to the system id and RLS sees every tenant.
    /// Mirrors PostgresFixture/NewElevatedDbContext. Multiplexing is off (default) so the GUC stays pinned.
    /// </summary>
    private AppDbContext BuildElevatedContext()
    {
        var connectionString = configuration.GetConnectionString("Postgres") ?? string.Empty;
        var dbOptions = new DbContextOptionsBuilder<AppDbContext>()
            .UseNpgsql(connectionString)
            .UseSnakeCaseNamingConvention()
            .AddInterceptors(new UserIdConnectionInterceptor(new SystemCurrentUser()))
            .Options;
        return new AppDbContext(dbOptions, new SystemCurrentUser());
    }

    /// <summary>
    /// Runs ONE purge pass against the given (elevated) context — the unit the timer drives and the
    /// test invokes directly. For each of the 10 trigger-bearing synced tables, computes the max
    /// change_seq among tombstones older than the horizon, hard-deletes them (SoftDelete filter OFF so
    /// tombstones are visible), then advances the watermark to GREATEST(existing, max purged seq).
    /// The DbContext MUST be elevated to SystemCurrentUser (see <see cref="BuildElevatedContext"/>).
    /// </summary>
    public static async Task<PurgeResult> PurgeOnceAsync(
        AppDbContext db, int retentionDays, ILogger logger, CancellationToken ct)
    {
        var cutoff = DateTimeOffset.UtcNow.AddDays(-retentionDays);
        var deletedByTable = new Dictionary<string, int>();
        long maxPurgedSeq = 0;

        async Task PurgeTableAsync<TEntity>(string table, IQueryable<TEntity> tombstones)
            where TEntity : class, ISyncable
        {
            // Visible tombstones older than the horizon (SoftDelete filter OFF; Tenant filter no-ops
            // under the system id so this spans all tenants).
            var expired = tombstones
                .IgnoreQueryFilters([AppDbContext.SoftDeleteFilter])
                .Where(e => e.DeletedAt != null && e.DeletedAt < cutoff);

            var batchMax = await expired.Select(e => (long?)e.ChangeSeq).MaxAsync(ct) ?? 0;
            var deleted = await expired.ExecuteDeleteAsync(ct);

            deletedByTable[table] = deleted;
            if (batchMax > maxPurgedSeq)
            {
                maxPurgedSeq = batchMax;
            }
        }

        // The 10 trigger-bearing synced tables (same set as SyncTriggersAndRls/ChangeSeqIndexes).
        await PurgeTableAsync("tasks", db.Tasks);
        await PurgeTableAsync("subtasks", db.Subtasks);
        await PurgeTableAsync("projects", db.Projects);
        await PurgeTableAsync("note_groups", db.NoteGroups);
        await PurgeTableAsync("notes", db.Notes);
        await PurgeTableAsync("recurrence_rules", db.RecurrenceRules);
        await PurgeTableAsync("recurrence_exceptions", db.RecurrenceExceptions);
        await PurgeTableAsync("focus_sessions", db.FocusSessions);
        await PurgeTableAsync("daily_plans", db.DailyPlans);
        await PurgeTableAsync("user_settings", db.UserSettings);

        // Advance the single-row watermark monotonically (only ever increases).
        var state = await db.SyncPurgeState.SingleAsync(s => s.Id == 1, ct);
        if (maxPurgedSeq > state.PurgedBelowChangeSeq)
        {
            state.PurgedBelowChangeSeq = maxPurgedSeq;
            await db.SaveChangesAsync(ct);
        }

        logger.LogInformation(
            "Tombstone purge pass: cutoff {Cutoff:o}; deleted {Total} rows; watermark {Watermark}",
            cutoff, deletedByTable.Values.Sum(), state.PurgedBelowChangeSeq);

        return new PurgeResult(deletedByTable, state.PurgedBelowChangeSeq);
    }
}
