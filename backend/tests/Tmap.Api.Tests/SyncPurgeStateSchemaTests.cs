using FluentAssertions;
using Microsoft.EntityFrameworkCore;
using Npgsql;
using Xunit;

namespace Tmap.Api.Tests;

[Collection(DbCollection.Name)]
public class SyncPurgeStateSchemaTests(PostgresFixture fixture) : IntegrationTestBase(fixture)
{
    [Fact]
    public async Task SyncPurgeState_HasExactlyOneSeededRow_1_0()
    {
        await using var db = NewElevatedDbContext();

        var rows = await db.SyncPurgeState.AsNoTracking().ToListAsync();

        rows.Should().ContainSingle("the watermark table is a single-row table seeded by the migration");
        rows[0].Id.Should().Be(1);
        rows[0].PurgedBelowChangeSeq.Should().Be(0L, "the seed starts the watermark at zero");
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
}
