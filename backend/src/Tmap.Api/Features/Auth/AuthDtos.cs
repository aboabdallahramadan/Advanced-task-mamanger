namespace Tmap.Api.Features.Auth;

public sealed record RegisterRequest(string Email, string Password);
public sealed record LoginRequest(string Email, string Password);
// Native clients send the refresh token in the body; web omits it (cookie). Nullable for that reason.
public sealed record RefreshRequest(string? RefreshToken);
public sealed record LogoutRequest(string? RefreshToken);

/// <summary>Public OpenAPI-visible response for register / login / refresh.</summary>
/// <param name="AccessToken">The access token.</param>
/// <param name="RefreshToken">Refresh token — absent on web (httpOnly cookie); present for native clients.</param>
/// <param name="ExpiresIn">Seconds until the access token expires.</param>
/// <param name="User">The authenticated user's public profile.</param>
public sealed record AuthTokenResponse(
    string AccessToken,
    string RefreshToken,
    int ExpiresIn,
    AuthTokenUser User);

public sealed record AuthTokenUser(Guid Id, string Email, string TimeZoneId);

// Internal: full issuer result; converted to AuthTokenResponse before returning.
internal sealed record TokenPairResponse(string AccessToken, string RefreshToken, DateTimeOffset AccessTokenExpiresAt);
public sealed record MeResponse(Guid Id, string Email, string TimeZoneId);
