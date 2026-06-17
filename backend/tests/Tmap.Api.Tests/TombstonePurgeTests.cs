using System.Net.Http.Json;
using FluentAssertions;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Logging.Abstractions;
using Tmap.Api.Features.Sync;
using Tmap.Api.Infrastructure;
using Tmap.Api.Infrastructure.Entities;
using Xunit;

namespace Tmap.Api.Tests;

[Collection(DbCollection.Name)]
public class TombstonePurgeTests(PostgresFixture fixture) : IntegrationTestBase(fixture)
{
    // Build an elevated (SystemCurrentUser) context the same way the production service does:
    // app.user_id pinned to the system id so the purge spans all tenants.
    private AppDbContext NewSystemContext() => NewElevatedDbContext();

    /// <summary>
    /// Reset the shared single-row watermark to 0. sync_purge_state has no tenant isolation, so
    /// sibling suites (e.g. full-resync, which can set it to high-water + 1000) may leave it
    /// advanced; each purge assertion needs a known baseline for the GREATEST(existing, maxPurged)
    /// monotonic update to be deterministic regardless of test order.
    /// </summary>
    private async Task ResetWatermarkAsync()
    {
        await using var ctx = NewElevatedDbContext();
        await ctx.Database.ExecuteSqlAsync(
            $"UPDATE sync_purge_state SET purged_below_change_seq = {0L} WHERE id = 1");
    }

    /// <summary>
    /// Soft-deletes a live task (created via HTTP) by setting deleted_at to a chosen instant.
    /// The change_seq trigger bumps the seq on this UPDATE; deleted_at is set exactly as given
    /// (the trigger touches only change_seq/updated_at). Returns the tombstone's change_seq.
    /// </summary>
    private async Task<long> TombstoneTaskAsync(Guid userId, Guid taskId, DateTimeOffset deletedAt)
    {
        await using var db = NewElevatedDbContext(userId);
        var row = await db.Set<TaskItem>()
            .IgnoreQueryFilters([AppDbContext.SoftDeleteFilter])
            .SingleAsync(t => t.Id == taskId);
        row.DeletedAt = deletedAt;
        await db.SaveChangesAsync();
        // Re-read the trigger-assigned change_seq.
        await using var verify = NewElevatedDbContext(userId);
        var seq = await verify.Set<TaskItem>()
            .IgnoreQueryFilters([AppDbContext.SoftDeleteFilter])
            .Where(t => t.Id == taskId)
            .Select(t => t.ChangeSeq)
            .SingleAsync();
        return seq;
    }

    private static async Task<Guid> CreateTaskAsync(AuthedClient user, string title)
    {
        var resp = await user.Client.PostAsJsonAsync("/api/v1/tasks", new { title, rank = "a0" });
        resp.EnsureSuccessStatusCode();
        var dto = await resp.Content.ReadFromJsonAsync<CreatedTask>();
        return dto!.Id;
    }

    private sealed record CreatedTask(Guid Id);

    [Fact]
    public async Task Purge_DeletesOverHorizonTombstones_ForBothUsers_KeepsRecentAndLive_AdvancesWatermark()
    {
        await ResetWatermarkAsync(); // known baseline (shared, non-tenant-scoped watermark)

        var horizonDays = 90;
        var oldDeletedAt = DateTimeOffset.UtcNow.AddDays(-(horizonDays + 10)); // 100 days → purge
        var recentDeletedAt = DateTimeOffset.UtcNow.AddDays(-1);                // 1 day → keep

        var alice = await RegisterAsync();
        var bob = await RegisterAsync();

        // Alice: one OLD tombstone, one RECENT tombstone, one LIVE task.
        var aliceOld = await CreateTaskAsync(alice, "alice-old");
        var aliceRecent = await CreateTaskAsync(alice, "alice-recent");
        var aliceLive = await CreateTaskAsync(alice, "alice-live");
        var aliceOldSeq = await TombstoneTaskAsync(alice.UserId, aliceOld, oldDeletedAt);
        await TombstoneTaskAsync(alice.UserId, aliceRecent, recentDeletedAt);

        // Bob: one OLD tombstone (the cross-tenant proof), one LIVE task.
        var bobOld = await CreateTaskAsync(bob, "bob-old");
        var bobLive = await CreateTaskAsync(bob, "bob-live");
        var bobOldSeq = await TombstoneTaskAsync(bob.UserId, bobOld, oldDeletedAt);

        var expectedWatermark = Math.Max(aliceOldSeq, bobOldSeq);

        // ACT: one purge pass directly (invoke the method, not the timer), with an ELEVATED context.
        PurgeResult result;
        await using (var system = NewSystemContext())
        {
            result = await TombstonePurgeService.PurgeOnceAsync(
                system, horizonDays, NullLogger.Instance, CancellationToken.None);
        }

        // The system (elevated) context no-ops the Tenant filter (SystemCurrentUser → _isTenantFilterActive
        // false), so a single elevated read spans BOTH tenants; only the SoftDelete filter must be turned
        // off to see (or confirm the absence of) tombstones.
        // ---- Cross-tenant deletion: BOTH users' OLD tombstones are gone (proves elevation). ----
        await using (var verify = NewSystemContext())
        {
            var aliceOldExists = await verify.Set<TaskItem>()
                .IgnoreQueryFilters([AppDbContext.SoftDeleteFilter])
                .AnyAsync(t => t.Id == aliceOld);
            var bobOldExists = await verify.Set<TaskItem>()
                .IgnoreQueryFilters([AppDbContext.SoftDeleteFilter])
                .AnyAsync(t => t.Id == bobOld);

            aliceOldExists.Should().BeFalse("Alice's over-horizon tombstone is hard-deleted");
            bobOldExists.Should().BeFalse(
                "Bob's over-horizon tombstone is hard-deleted too — the system-id elevation purges across ALL tenants, not just the caller's");

            // ---- Recent tombstone kept; live rows of BOTH users untouched. ----
            var aliceRecentExists = await verify.Set<TaskItem>()
                .IgnoreQueryFilters([AppDbContext.SoftDeleteFilter])
                .AnyAsync(t => t.Id == aliceRecent);
            aliceRecentExists.Should().BeTrue("a 1-day-old tombstone is within the 90-day horizon");

            // Live rows are visible without ignoring SoftDelete (DeletedAt == null), across both tenants.
            var aliceLiveExists = await verify.Set<TaskItem>()
                .AnyAsync(t => t.Id == aliceLive && t.DeletedAt == null);
            var bobLiveExists = await verify.Set<TaskItem>()
                .AnyAsync(t => t.Id == bobLive && t.DeletedAt == null);
            aliceLiveExists.Should().BeTrue("live (non-tombstone) rows are never purged");
            bobLiveExists.Should().BeTrue("live (non-tombstone) rows are never purged");

            // ---- Watermark advanced to the max purged change_seq. ----
            var watermark = await verify.SyncPurgeState
                .Where(s => s.Id == 1).Select(s => s.PurgedBelowChangeSeq).SingleAsync();
            watermark.Should().Be(expectedWatermark,
                "the watermark is the max change_seq among the purged tombstones (across all tenants)");
        }

        // The returned result reflects the two deleted task tombstones and the new watermark.
        result.DeletedByTable["tasks"].Should().Be(2, "two over-horizon task tombstones were deleted (Alice + Bob)");
        result.Watermark.Should().Be(expectedWatermark);
    }

    [Fact]
    public async Task Purge_WatermarkIsMonotonic_DoesNotRegressWhenNothingToPurge()
    {
        await ResetWatermarkAsync(); // known baseline (shared, non-tenant-scoped watermark)

        var horizonDays = 90;
        var oldDeletedAt = DateTimeOffset.UtcNow.AddDays(-100);

        var user = await RegisterAsync();
        var taskId = await CreateTaskAsync(user, "to-purge");
        var oldSeq = await TombstoneTaskAsync(user.UserId, taskId, oldDeletedAt);

        // First pass purges the tombstone and sets the watermark to oldSeq.
        await using (var system = NewSystemContext())
        {
            var first = await TombstonePurgeService.PurgeOnceAsync(
                system, horizonDays, NullLogger.Instance, CancellationToken.None);
            first.Watermark.Should().Be(oldSeq);
        }

        // Second pass: nothing left to purge → watermark must NOT regress (stays at oldSeq).
        await using (var system = NewSystemContext())
        {
            var second = await TombstonePurgeService.PurgeOnceAsync(
                system, horizonDays, NullLogger.Instance, CancellationToken.None);
            second.TotalDeleted.Should().Be(0, "no over-horizon tombstones remain");
            second.Watermark.Should().Be(oldSeq, "the watermark only ever increases (monotonic)");
        }
    }
}
