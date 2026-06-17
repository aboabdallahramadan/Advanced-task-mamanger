using FluentAssertions;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Options;
using Npgsql;
using Tmap.Api.Common;
using Xunit;

namespace Tmap.Api.Tests;

[Collection(DbCollection.Name)]
public class SyncPurgeStateSchemaTests(PostgresFixture fixture) : IntegrationTestBase(fixture)
{
    [Fact]
    public async Task SyncPurgeState_HasExactlyOneSeededRow_1_0()
    {
        // sync_purge_state is a single, NON-tenant-scoped row shared across the whole "db" test
        // collection (it has no RLS isolation). Sibling suites (purge, full-resync) legitimately
        // advance its watermark, so normalize it back to the seed value before asserting the
        // migration's load-bearing invariant: exactly ONE row, keyed Id = 1, holding a clean 0.
        // (That the literal seed is 0 is self-evident in the migration SQL and exercised by the
        // purge/full-resync suites; the runtime value is mutable shared state, not a stable global
        // invariant to assert directly against a polluted DB.)
        await using (var normalize = NewElevatedDbContext())
        {
            await normalize.Database.ExecuteSqlAsync(
                $"UPDATE sync_purge_state SET purged_below_change_seq = {0L} WHERE id = 1");
        }

        await using var db = NewElevatedDbContext();

        var rows = await db.SyncPurgeState.AsNoTracking().ToListAsync();

        rows.Should().ContainSingle("the migration seeds exactly one watermark row");
        rows[0].Id.Should().Be(1, "the single watermark row is keyed Id = 1");
        rows[0].PurgedBelowChangeSeq.Should().Be(0L, "the normalized seed value round-trips");
    }

    [Fact]
    public async Task SyncPurgeState_HasNoChangeSeqColumn_AndNoUserId_SoItIsNotSynced()
    {
        await using var db = NewElevatedDbContext();
        await db.Database.OpenConnectionAsync();
        var conn = (NpgsqlConnection)db.Database.GetDbConnection();

        var columns = new List<string>();
        await using (var cmd = new NpgsqlCommand(
            @"SELECT column_name FROM information_schema.columns
              WHERE table_schema = 'public' AND table_name = 'sync_purge_state';", conn))
        await using (var reader = await cmd.ExecuteReaderAsync())
        {
            while (await reader.ReadAsync())
            {
                columns.Add(reader.GetString(0));
            }
        }

        columns.Should().Contain("id");
        columns.Should().Contain("purged_below_change_seq");
        columns.Should().NotContain("change_seq",
            "sync_purge_state is a plain table — never on the change_seq trigger/sequence path");
        columns.Should().NotContain("user_id",
            "sync_purge_state is not tenant-scoped, so it carries no user_id");
    }

    [Fact]
    public async Task SyncPurgeState_HasNoRlsPolicy_SoItIsNotTenantScoped()
    {
        await using var db = NewElevatedDbContext();
        await db.Database.OpenConnectionAsync();
        var conn = (NpgsqlConnection)db.Database.GetDbConnection();

        await using var cmd = new NpgsqlCommand(
            @"SELECT count(*) FROM pg_policies
              WHERE schemaname = 'public' AND tablename = 'sync_purge_state';", conn);
        var policyCount = (long)(await cmd.ExecuteScalarAsync())!;

        policyCount.Should().Be(0L,
            "sync_purge_state must have NO RLS policy — it is not in the SyncTriggersAndRls table set");
    }

    [Fact]
    public void PurgeOptions_DefaultsTo90Days_WhenUnconfigured()
    {
        // The base test config sets no Purge section, so the bound options keep the default.
        using var scope = Factory.Services.CreateScope();
        var options = scope.ServiceProvider.GetRequiredService<IOptions<PurgeOptions>>();

        options.Value.RetentionDays.Should().Be(90,
            "the retention horizon defaults to 90 days (> the 60-day refresh window)");
    }

    [Fact]
    public void PurgeOptions_BindsConfiguredRetentionDays()
    {
        using var factory = NewFactoryWithConfig(new Dictionary<string, string?>
        {
            ["Purge:RetentionDays"] = "120",
        });
        using var scope = factory.Services.CreateScope();
        var options = scope.ServiceProvider.GetRequiredService<IOptions<PurgeOptions>>();

        options.Value.RetentionDays.Should().Be(120, "Purge__RetentionDays overrides the default");
    }
}
