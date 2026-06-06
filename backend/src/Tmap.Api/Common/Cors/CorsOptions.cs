namespace Tmap.Api.Common.Cors;

/// <summary>
/// Bound from the "Cors" configuration section. AllowedOrigins is an explicit
/// per-environment allowlist; it must be non-empty in any environment that serves
/// browser clients. Desktop clients send no Origin and are unaffected by CORS.
/// </summary>
public sealed class CorsOptions
{
    public const string SectionName = "Cors";

    /// <summary>Exact origins (scheme+host+port) permitted to send credentialed requests.</summary>
    public string[] AllowedOrigins { get; init; } = [];
}
