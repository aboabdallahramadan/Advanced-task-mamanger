using Microsoft.AspNetCore.Builder;
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

        return app;
    }
}

public sealed record HealthResponse(string Status);
