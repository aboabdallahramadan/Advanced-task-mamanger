namespace Tmap.Api.Features.Auth;

public static class RefreshCookie
{
    public const string Name = "tmap_rt";

    public static void Write(HttpResponse res, string rawRefreshToken, DateTimeOffset expiresAt) =>
        res.Cookies.Append(Name, rawRefreshToken, new CookieOptions
        {
            HttpOnly = true,
            Secure = true,
            SameSite = SameSiteMode.Strict,
            Path = "/api/v1/auth/refresh",
            Expires = expiresAt,
        });

    public static void Clear(HttpResponse res) =>
        res.Cookies.Append(Name, "", new CookieOptions
        {
            HttpOnly = true,
            Secure = true,
            SameSite = SameSiteMode.Strict,
            Path = "/api/v1/auth/refresh",
            Expires = DateTimeOffset.UnixEpoch,
        });

    // Native sends in body; web sends via cookie. Body wins when present.
    public static string? Resolve(HttpRequest req, string? bodyToken) =>
        !string.IsNullOrEmpty(bodyToken) ? bodyToken
        : req.Cookies.TryGetValue(Name, out var c) && !string.IsNullOrEmpty(c) ? c
        : null;
}
