namespace Tmap.Api.Features.Settings;

/// <summary>
/// Server-side allowlist of settings keys that may be synced. Any key not in
/// this set is ignored on write and never returned. Purely-local UI prefs
/// (sidebarCollapsed, notesCollapsed, projectsCollapsed) intentionally stay
/// on-device and are absent here.
/// </summary>
public static class SyncedSettingKeys
{
    public static readonly IReadOnlySet<string> Allowed = new HashSet<string>(StringComparer.Ordinal)
    {
        "workStartHour",
        "workEndHour",
        "timeIncrement",
    };
}
