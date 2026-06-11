using Microsoft.AspNetCore.OpenApi;
using Microsoft.OpenApi;
using Tmap.Api.Common;

namespace Tmap.Api.Common.OpenApi;

/// <summary>
/// Documents every <see cref="Optional{T}"/> property as its INNER type instead of the wrapper's
/// structural object shape (<c>{ hasValue, value }</c>). This keeps the generated OpenAPI document —
/// and therefore the generated TypeScript client schema — byte-identical to what a plain nullable
/// property would have produced, even though the backend DTO now uses the presence-tracking wrapper.
/// </summary>
internal sealed class OptionalSchemaTransformer : IOpenApiSchemaTransformer
{
    public async Task TransformAsync(
        OpenApiSchema schema,
        OpenApiSchemaTransformerContext context,
        CancellationToken cancellationToken)
    {
        var innerType = OptionalType.GetInnerType(context.JsonTypeInfo.Type);
        if (innerType is null)
        {
            return;
        }

        // Generate the schema the inner (nullable) type would have produced and overwrite the
        // Optional<T> object schema with it, in place.
        var inner = await context.GetOrCreateSchemaAsync(innerType, cancellationToken: cancellationToken);
        CopyFrom(schema, inner);
    }

    /// <summary>Overwrites <paramref name="target"/>'s JSON-schema content with <paramref name="source"/>'s.</summary>
    private static void CopyFrom(OpenApiSchema target, IOpenApiSchema source)
    {
        // Wrapper-derived structural keywords that must not survive the rewrite.
        target.Properties = source.Properties is null ? null : new Dictionary<string, IOpenApiSchema>(source.Properties);
        target.Required = source.Required is null ? null : new HashSet<string>(source.Required);
        target.AdditionalPropertiesAllowed = source.AdditionalPropertiesAllowed;
        target.AdditionalProperties = source.AdditionalProperties;

        // Core type/format/validation keywords carried by the inner nullable primitive schemas.
        target.Type = source.Type;
        target.Format = source.Format;
        target.Pattern = source.Pattern;
        target.Default = source.Default;
        target.Enum = source.Enum is null ? null : new List<System.Text.Json.Nodes.JsonNode>(source.Enum);

        // Numeric / string / array constraints (kept for fidelity even though current inner types
        // only set Type/Format/Pattern).
        target.Maximum = source.Maximum;
        target.Minimum = source.Minimum;
        target.MaxLength = source.MaxLength;
        target.MinLength = source.MinLength;
        target.MultipleOf = source.MultipleOf;
        target.MaxItems = source.MaxItems;
        target.MinItems = source.MinItems;
        target.UniqueItems = source.UniqueItems;
        target.Items = source.Items;

        // Composition keywords (e.g. a nullable enum ref renders as oneOf[null, $ref]).
        target.OneOf = source.OneOf is null ? null : new List<IOpenApiSchema>(source.OneOf);
        target.AnyOf = source.AnyOf is null ? null : new List<IOpenApiSchema>(source.AnyOf);
        target.AllOf = source.AllOf is null ? null : new List<IOpenApiSchema>(source.AllOf);
    }
}
