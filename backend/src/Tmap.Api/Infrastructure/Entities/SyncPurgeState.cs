namespace Tmap.Api.Infrastructure.Entities;

/// <summary>
/// Single-row watermark recording the high-water <c>change_seq</c> below which purged tombstones
/// may be missing. NOT a synced/trigger-bearing table: no <c>change_seq</c> column, no RLS policy,
/// not tenant-scoped, not returned by <c>/sync</c>. The single row always has <c>Id = 1</c>.
/// </summary>
public sealed class SyncPurgeState
{
    public int Id { get; set; }                    // PK; always the single row Id = 1
    public long PurgedBelowChangeSeq { get; set; } // monotonic high-water of purged tombstones
}
