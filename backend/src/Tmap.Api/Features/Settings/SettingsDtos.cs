namespace Tmap.Api.Features.Settings;

public sealed record SaveSettingsRequest(Dictionary<string, string> Settings, string? TimeZoneId = null);

public sealed record SettingsResponse(
    Dictionary<string, string> Settings,
    string TimeZoneId);
