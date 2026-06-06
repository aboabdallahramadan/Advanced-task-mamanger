using Microsoft.AspNetCore.OpenApi;
using Microsoft.OpenApi;

namespace Tmap.Api.Common.OpenApi;

/// <summary>Sets stable Info (title/version/description) on the OpenAPI document.</summary>
internal sealed class DocumentInfoTransformer : IOpenApiDocumentTransformer
{
    public Task TransformAsync(
        OpenApiDocument document,
        OpenApiDocumentTransformerContext context,
        CancellationToken cancellationToken)
    {
        document.Info = new OpenApiInfo
        {
            Title = "TMap API",
            Version = "v1",
            Description =
                "TMap backend API. JWT-authenticated daily planning and task management. "
                + "All routes are under /api/v1; errors use RFC 9457 ProblemDetails.",
        };
        return Task.CompletedTask;
    }
}
