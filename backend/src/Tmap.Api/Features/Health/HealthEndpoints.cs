namespace Tmap.Api.Features.Health;

/// <summary>
/// Maps the /health liveness endpoint. Additional readiness probes (DB connectivity)
/// are wired in P0-8 once Testcontainers is in place.
/// </summary>
public static class HealthEndpoints
{
    public static IEndpointRouteBuilder MapHealthEndpoints(this IEndpointRouteBuilder app)
    {
        app.MapGet("/health", () => Results.Ok(new { status = "healthy" }))
           .WithName("GetHealth")
           .AllowAnonymous();

        return app;
    }
}
