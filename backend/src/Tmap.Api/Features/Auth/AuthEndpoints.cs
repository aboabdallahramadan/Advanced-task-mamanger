using Microsoft.AspNetCore.Identity;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using Serilog;
using Tmap.Api.Common;
using Tmap.Api.Common.Validation;
using Tmap.Api.Infrastructure;
using Tmap.Api.Infrastructure.Entities;
using Tmap.Api.Infrastructure.Jwt;

namespace Tmap.Api.Features.Auth;

public static class AuthEndpoints
{
    public static RouteGroupBuilder MapAuthEndpoints(this IEndpointRouteBuilder app)
    {
        var group = app.MapGroup("auth").WithTags("Auth");

        group.MapPost("/register", Register)
            .AddEndpointFilter<ValidationFilter<RegisterRequest>>()
            .RequireRateLimiting(RateLimitPolicies.AuthByIpAndEmail)
            .AllowAnonymous();

        group.MapPost("/login", Login)
            .AddEndpointFilter<ValidationFilter<LoginRequest>>()
            .RequireRateLimiting(RateLimitPolicies.AuthByIpAndEmail)
            .AllowAnonymous();

        group.MapPost("/refresh", Refresh)
            .RequireRateLimiting(RateLimitPolicies.AuthByIpAndEmail)
            .AllowAnonymous();

        group.MapGet("/me", Me).RequireAuthorization();

        group.MapPost("/logout", Logout).AllowAnonymous();          // refresh token in body/cookie; no access token required
        group.MapPost("/logout-all", LogoutAll).RequireAuthorization();

        return group;
    }

    private static async Task<IResult> Register(
        RegisterRequest req,
        UserManager<ApplicationUser> users,
        AppDbContext db,
        IJwtService jwt,
        IOptions<JwtOptions> jwtOptions,
        HttpContext http)
    {
        var existing = await users.FindByEmailAsync(req.Email);
        if (existing is not null)
        {
            // Register enumeration is an accepted v1 trade-off (spec §3.5), compensated by rate limiting.
            return Results.Problem(statusCode: StatusCodes.Status409Conflict, title: "Email already registered.");
        }

        var user = new ApplicationUser { Id = Guid.CreateVersion7(), UserName = req.Email, Email = req.Email };
        var result = await users.CreateAsync(user, req.Password);
        if (!result.Succeeded)
        {
            return Results.ValidationProblem(result.Errors.GroupBy(e => e.Code)
                .ToDictionary(g => g.Key, g => g.Select(e => e.Description).ToArray()));
        }

        var pair = await AuthTokenIssuer.IssueAsync(user.Id, db, jwt, jwtOptions.Value, http);
        Log.ForContext("UserId", user.Id).ForContext("Ip", http.Connection.RemoteIpAddress?.ToString())
            .Information("auth.register.success");
        return Results.Ok(AuthTokenIssuer.ToPublic(pair, jwtOptions.Value, user));
    }

    private static async Task<IResult> Login(
        LoginRequest req,
        UserManager<ApplicationUser> users,
        AppDbContext db,
        IJwtService jwt,
        IOptions<JwtOptions> jwtOptions,
        HttpContext http)
    {
        var sourceIp = http.Connection.RemoteIpAddress?.ToString();
        var user = await users.FindByEmailAsync(req.Email);

        if (user is null)
        {
            // Constant-time path: spend the same work hashing a dummy password so timing
            // does not reveal whether the email exists (spec §3.5).
            users.PasswordHasher.VerifyHashedPassword(
                new ApplicationUser(),
                DummyPasswordHash,
                req.Password);
            Log.ForContext("Ip", sourceIp).Information("auth.login.failure {Reason}", "unknown_email");
            return GenericUnauthorized();
        }

        if (await users.IsLockedOutAsync(user))
        {
            Log.ForContext("UserId", user.Id).ForContext("Ip", sourceIp)
                .Warning("auth.login.lockout");
            return GenericUnauthorized();
        }

        var ok = await users.CheckPasswordAsync(user, req.Password);
        if (!ok)
        {
            await users.AccessFailedAsync(user); // increments lockout counter
            if (await users.IsLockedOutAsync(user))
            {
                Log.ForContext("UserId", user.Id).ForContext("Ip", sourceIp).Warning("auth.login.lockout");
            }
            else
            {
                Log.ForContext("UserId", user.Id).ForContext("Ip", sourceIp)
                    .Information("auth.login.failure {Reason}", "wrong_password");
            }

            return GenericUnauthorized();
        }

        await users.ResetAccessFailedCountAsync(user);
        var pair = await AuthTokenIssuer.IssueAsync(user.Id, db, jwt, jwtOptions.Value, http);
        Log.ForContext("UserId", user.Id).ForContext("Ip", sourceIp).Information("auth.login.success");
        return Results.Ok(AuthTokenIssuer.ToPublic(pair, jwtOptions.Value, user));
    }

    private static async Task<IResult> Refresh(
        RefreshRequest body,
        HttpContext http,
        AppDbContext db,
        IJwtService jwt,
        IOptions<JwtOptions> jwtOptions,
        UserManager<ApplicationUser> users)
    {
        var raw = RefreshCookie.Resolve(http.Request, body.RefreshToken);
        if (string.IsNullOrEmpty(raw))
        {
            return GenericUnauthorized();
        }

        var hash = jwt.HashRefreshToken(raw);
        var token = await db.RefreshTokens.SingleOrDefaultAsync(t => t.TokenHash == hash);
        if (token is null)
        {
            return GenericUnauthorized();
        }

        var now = DateTimeOffset.UtcNow;

        // Reuse-detection: a token presented after it was already revoked/rotated means the
        // chain is compromised -> revoke the entire family (all of the user's live tokens).
        if (token.RevokedAt is not null || token.ExpiresAt <= now)
        {
            var live = await db.RefreshTokens
                .Where(t => t.UserId == token.UserId && t.RevokedAt == null)
                .ToListAsync();
            foreach (var t in live)
            {
                t.RevokedAt = now;
            }

            await db.SaveChangesAsync();
            Log.ForContext("UserId", token.UserId).ForContext("Ip", http.Connection.RemoteIpAddress?.ToString())
                .Warning("auth.refresh.reuse_detected family_revoked count={Count}", live.Count);
            RefreshCookie.Clear(http.Response);
            return GenericUnauthorized();
        }

        // Rotate: issue a fresh pair, mark this token revoked + replaced.
        var (newRaw, newHash) = jwt.CreateRefreshToken();
        var (access, accessExp) = jwt.CreateAccessToken(token.UserId);

        token.RevokedAt = now;
        token.ReplacedByTokenHash = newHash;
        db.RefreshTokens.Add(new RefreshToken
        {
            Id = Guid.CreateVersion7(),
            UserId = token.UserId,
            TokenHash = newHash,
            CreatedAt = now,
            ExpiresAt = now.AddDays(jwtOptions.Value.RefreshTokenDays),
            DeviceInfo = token.DeviceInfo,
        });
        await db.SaveChangesAsync();

        RefreshCookie.Write(http.Response, newRaw, now.AddDays(jwtOptions.Value.RefreshTokenDays));
        Log.ForContext("UserId", token.UserId).Information("auth.refresh.success");
        var refreshUser = await users.FindByIdAsync(token.UserId.ToString());
        return Results.Ok(AuthTokenIssuer.ToPublic(new TokenPairResponse(access, newRaw, accessExp), jwtOptions.Value, refreshUser!));
    }

    private static async Task<IResult> Me(ICurrentUser currentUser, UserManager<ApplicationUser> users)
    {
        var user = await users.FindByIdAsync(currentUser.Id.ToString());
        if (user is null)
        {
            return Results.Unauthorized();
        }

        return Results.Ok(new MeResponse(user.Id, user.Email ?? "", user.TimeZoneId));
    }

    private static async Task<IResult> Logout(LogoutRequest body, HttpContext http, AppDbContext db, IJwtService jwt)
    {
        var raw = RefreshCookie.Resolve(http.Request, body.RefreshToken);
        if (!string.IsNullOrEmpty(raw))
        {
            var hash = jwt.HashRefreshToken(raw);
            var token = await db.RefreshTokens.SingleOrDefaultAsync(t => t.TokenHash == hash && t.RevokedAt == null);
            if (token is not null)
            {
                token.RevokedAt = DateTimeOffset.UtcNow;
                await db.SaveChangesAsync();
                Log.ForContext("UserId", token.UserId).Information("auth.logout");
            }
        }
        RefreshCookie.Clear(http.Response);
        return Results.NoContent(); // idempotent: always 204, never reveals validity
    }

    private static async Task<IResult> LogoutAll(ICurrentUser currentUser, HttpContext http, AppDbContext db)
    {
        var userId = currentUser.Id;
        var now = DateTimeOffset.UtcNow;
        var live = await db.RefreshTokens.Where(t => t.UserId == userId && t.RevokedAt == null).ToListAsync();
        foreach (var t in live)
        {
            t.RevokedAt = now;
        }

        await db.SaveChangesAsync();
        Log.ForContext("UserId", userId).Information("auth.logout_all count={Count}", live.Count);
        RefreshCookie.Clear(http.Response);
        return Results.NoContent();
    }

    // Identical body for unknown-email and wrong-password — no enumeration.
    // Results.Problem injects a per-request traceId extension, which would make the two
    // failure bodies differ; use a fixed payload so unknown-email and wrong-password match byte-for-byte.
    private static IResult GenericUnauthorized() =>
        Results.Json(
            new ProblemDetails
            {
                Status = StatusCodes.Status401Unauthorized,
                Title = "Invalid credentials.",
            },
            statusCode: StatusCodes.Status401Unauthorized);

    // Precomputed PBKDF2 hash of a fixed string under the default Identity hasher; used only
    // for the constant-time no-such-user path. Not a real credential.
    private static readonly string DummyPasswordHash =
        new Microsoft.AspNetCore.Identity.PasswordHasher<ApplicationUser>()
            .HashPassword(new ApplicationUser(), "constant-time-dummy-password");
}

internal static class AuthTokenIssuer
{
    public static AuthTokenResponse ToPublic(
        TokenPairResponse pair,
        JwtOptions opts,
        ApplicationUser user)
    {
        var expiresIn = (int)(pair.AccessTokenExpiresAt - DateTimeOffset.UtcNow).TotalSeconds;
        return new AuthTokenResponse(
            pair.AccessToken,
            pair.RefreshToken,
            expiresIn > 0 ? expiresIn : 0,
            new AuthTokenUser(user.Id, user.Email ?? "", user.TimeZoneId));
    }

    public static async Task<TokenPairResponse> IssueAsync(
        Guid userId, AppDbContext db, IJwtService jwt, JwtOptions jwtOptions, HttpContext http)
    {
        var (access, accessExp) = jwt.CreateAccessToken(userId);
        var (rawRefresh, refreshHash) = jwt.CreateRefreshToken();
        var now = DateTimeOffset.UtcNow;

        db.RefreshTokens.Add(new RefreshToken
        {
            Id = Guid.CreateVersion7(),
            UserId = userId,
            TokenHash = refreshHash,
            CreatedAt = now,
            ExpiresAt = now.AddDays(jwtOptions.RefreshTokenDays),
            DeviceInfo = http.Request.Headers.UserAgent.ToString() is { Length: > 0 } ua ? ua[..Math.Min(ua.Length, 256)] : null,
        });
        await db.SaveChangesAsync();

        RefreshCookie.Write(http.Response, rawRefresh, now.AddDays(jwtOptions.RefreshTokenDays));
        return new TokenPairResponse(access, rawRefresh, accessExp);
    }
}
