using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;

namespace Tmap.Api.Features.Health;

public static class HealthEndpoints
{
    public static IEndpointRouteBuilder MapHealthEndpoints(this IEndpointRouteBuilder app)
    {
        // Liveness probe. Anonymous on purpose. Not under /api/v1 — it is an ops endpoint.
        app.MapGet("/health", () => TypedResults.Ok(new HealthResponse("ok")))
            .WithName("Health")
            .AllowAnonymous();

        return app;
    }
}

public sealed record HealthResponse(string Status);
