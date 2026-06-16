using System.Threading.RateLimiting;
using Microsoft.AspNetCore.RateLimiting;

namespace Tmap.Api.Common;

public static class RateLimitPolicies
{
    public const string AuthByIpAndEmail = "auth-ip-email";

    // Coarse aggregate ceiling keyed on the (forwarded) client IP only, applied IN ADDITION to
    // AuthByIpAndEmail so one source IP cannot farm a fresh IP|email bucket per distinct email
    // (credential stuffing / registration spam). Pinned production value: 30/min per IP.
    //
    // IMPLEMENTATION NOTE (C7): a named policy applied via .RequireRateLimiting OVERRIDES any
    // earlier policy on the same endpoint (it does NOT compose) — chaining two named policies
    // would silently drop AuthByIpAndEmail. The framework-supported way to run TWO independent
    // windows on the same request is the GlobalLimiter (which runs IN ADDITION to the endpoint's
    // named policy). So this coarse IP cap is wired as a PartitionedRateLimiter.CreateChained
    // GlobalLimiter scoped to the three auth POST paths (GetNoLimiter everywhere else, so normal
    // API traffic is untouched). The const name documents the limit; it is not an AddPolicy key.
    public const string AuthByIp = "auth-ip";

    // Per-IP aggregate ceiling (pinned production value). Coarser than the 10/min per IP|email
    // target limit so legitimate multi-account NAT use is not throttled, while an email-rotating
    // spam loop trips quickly.
    private const int AuthByIpPermitLimit = 30;

    public static IServiceCollection AddTmapRateLimiting(this IServiceCollection services)
    {
        services.AddRateLimiter(options =>
        {
            options.RejectionStatusCode = StatusCodes.Status429TooManyRequests;

            options.AddPolicy(AuthByIpAndEmail, httpContext =>
            {
                var ip = httpContext.Connection.RemoteIpAddress?.ToString() ?? "unknown-ip";
                var email = ResolveEmailKey(httpContext);
                var partitionKey = $"{ip}|{email}";

                return RateLimitPartition.GetSlidingWindowLimiter(partitionKey, _ => new SlidingWindowRateLimiterOptions
                {
                    // Sized so the lockout test (5 fails + 1 verify) is not throttled, while the
                    // dedicated 429 test (30 calls) trips. Tune per environment.
                    PermitLimit = 10,
                    Window = TimeSpan.FromMinutes(1),
                    SegmentsPerWindow = 6,
                    QueueLimit = 0,
                    QueueProcessingOrder = QueueProcessingOrder.OldestFirst,
                });
            });

            // GlobalLimiter runs on EVERY request IN ADDITION to the endpoint's named policy, so
            // an auth request must acquire a lease from BOTH AuthByIpAndEmail (per IP|email) AND
            // this aggregate per-IP window — either rejecting yields the 429. Non-auth paths get
            // GetNoLimiter so the rest of the API is unaffected by this cap.
            options.GlobalLimiter = PartitionedRateLimiter.CreateChained(
                PartitionedRateLimiter.Create<HttpContext, string>(httpContext =>
                {
                    if (!IsAuthRateLimitedPath(httpContext))
                    {
                        return RateLimitPartition.GetNoLimiter("non-auth");
                    }

                    var ip = httpContext.Connection.RemoteIpAddress?.ToString() ?? "unknown-ip";

                    return RateLimitPartition.GetSlidingWindowLimiter(ip, _ => new SlidingWindowRateLimiterOptions
                    {
                        PermitLimit = AuthByIpPermitLimit,
                        Window = TimeSpan.FromMinutes(1),
                        SegmentsPerWindow = 6,
                        QueueLimit = 0,
                        QueueProcessingOrder = QueueProcessingOrder.OldestFirst,
                    });
                }));
        });

        return services;
    }

    // The three auth POST endpoints the aggregate IP cap guards (mirrors the body-peek middleware
    // path check in Program.cs). Coarse path-suffix match so it survives the /api/v1 group prefix.
    private static bool IsAuthRateLimitedPath(HttpContext ctx)
    {
        if (!HttpMethods.IsPost(ctx.Request.Method))
        {
            return false;
        }

        var path = ctx.Request.Path.Value ?? "";
        return path.EndsWith("/auth/login", StringComparison.OrdinalIgnoreCase)
            || path.EndsWith("/auth/register", StringComparison.OrdinalIgnoreCase)
            || path.EndsWith("/auth/refresh", StringComparison.OrdinalIgnoreCase);
    }

    // Key by email when the request body carries one (register/login/refresh-by-body),
    // so attackers can't dodge the limit by rotating only IP or only email.
    private static string ResolveEmailKey(HttpContext ctx)
    {
        if (ctx.Items.TryGetValue("rl_email", out var cached) && cached is string s)
        {
            return s;
        }

        return "no-email";
    }
}
