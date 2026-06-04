namespace Tmap.Api.Features.Auth;

public sealed record RegisterRequest(string Email, string Password);
public sealed record LoginRequest(string Email, string Password);
// Native clients send the refresh token in the body; web omits it (cookie). Nullable for that reason.
public sealed record RefreshRequest(string? RefreshToken);
public sealed record LogoutRequest(string? RefreshToken);

public sealed record TokenPairResponse(string AccessToken, string RefreshToken, DateTimeOffset AccessTokenExpiresAt);
public sealed record MeResponse(Guid Id, string Email, string TimeZoneId);
