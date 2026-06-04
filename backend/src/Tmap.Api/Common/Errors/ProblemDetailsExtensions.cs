using Microsoft.AspNetCore.Http.Features;

namespace Tmap.Api.Common.Errors;

public static class ProblemDetailsExtensions
{
    /// <summary>
    /// Registers RFC 9457 ProblemDetails generation with consistent extension members
    /// (traceId, instance) used by both the validation filter and the global exception handler.
    /// </summary>
    public static IServiceCollection AddTmapProblemDetails(this IServiceCollection services)
    {
        services.AddProblemDetails(options =>
        {
            options.CustomizeProblemDetails = context =>
            {
                context.ProblemDetails.Instance ??= context.HttpContext.Request.Path;
                context.ProblemDetails.Extensions["traceId"] =
                    context.HttpContext.Features.Get<IHttpActivityFeature>()?.Activity.Id
                    ?? context.HttpContext.TraceIdentifier;
            };
        });

        return services;
    }
}
