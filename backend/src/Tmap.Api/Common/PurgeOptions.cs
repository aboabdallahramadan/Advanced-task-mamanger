namespace Tmap.Api.Common;

/// <summary>
/// Tombstone-purge configuration. Bound from the <c>Purge</c> config section
/// (env <c>Purge__RetentionDays</c>). The default 90-day horizon is safely greater than the
/// 60-day refresh-token window, so a still-authenticated delta client can never have missed a purge.
/// </summary>
public sealed class PurgeOptions
{
    public const string SectionName = "Purge";

    /// <summary>Tombstones older than this many days are hard-deleted. Default 90.</summary>
    public int RetentionDays { get; set; } = 90;
}
