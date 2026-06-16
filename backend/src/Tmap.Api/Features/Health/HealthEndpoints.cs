using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Diagnostics.HealthChecks;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;

namespace Tmap.Api.Features.Health;

public static class HealthEndpoints
{
    public static IEndpointRouteBuilder MapHealthEndpoints(this IEndpointRouteBuilder app)
    {
        // Liveness probe. Anonymous on purpose. Not under /api/v1 — it is an ops endpoint.
        // Excluded from the OpenAPI document so All_paths_are_under_api_v1 stays green.
        app.MapGet("/health", () => TypedResults.Ok(new HealthResponse("ok")))
            .WithName("Health")
            .AllowAnonymous()
            .ExcludeFromDescription();

        // Readiness probe. Runs the registered health checks (Postgres SELECT 1). The
        // Dockerfile HEALTHCHECK (C10) curls this from inside the container so a deploy only
        // goes healthy once Postgres is reachable and migrations have run. Anonymous + hidden.
        app.MapHealthChecks("/health/ready", new HealthCheckOptions())
            .WithName("HealthReady")
            .AllowAnonymous()
            .ExcludeFromDescription();

        return app;
    }
}

public sealed record HealthResponse(string Status);
