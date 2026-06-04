using System.Threading.RateLimiting;
using Microsoft.AspNetCore.RateLimiting;

namespace Tmap.Api.Common;

public static class RateLimitPolicies
{
    public const string AuthByIpAndEmail = "auth-ip-email";

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
        });

        return services;
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
