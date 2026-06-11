using System.Reflection;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Tmap.Api.Common;

// A presence-tracking wrapper for request DTO fields, so a PATCH handler can tell
// "property was absent from the JSON body" (leave unchanged) apart from "property was
// present with an explicit null" (clear the value).
//
// HasValue is true only when the JSON property was present in the request body (the value
// itself may still be null). When the property is absent the field keeps default(Optional<T>)
// — i.e. HasValue == false.
//
// The OpenAPI surface is kept identical to a plain nullable property via
// OptionalSchemaTransformer, so the generated TypeScript client schema is unchanged.
//
// NOTE: no XML doc <summary> here on purpose — the OpenAPI XML-comment source generator keys its
// cache by the open generic type's doc-id, which would collide across multiple closed Optional<T>.
public readonly record struct Optional<T>(bool HasValue, T Value);

/// <summary>
/// Produces an <see cref="OptionalJsonConverter{T}"/> for any closed <see cref="Optional{T}"/> type.
/// Registered in the HTTP JSON options so request-body deserialization sets
/// <see cref="Optional{T}.HasValue"/> = true whenever the JSON property is present (value may be null),
/// and leaves <c>default</c> (HasValue = false) when the property is absent.
/// </summary>
public sealed class OptionalJsonConverterFactory : JsonConverterFactory
{
    public override bool CanConvert(Type typeToConvert) =>
        typeToConvert.IsGenericType &&
        typeToConvert.GetGenericTypeDefinition() == typeof(Optional<>);

    public override JsonConverter CreateConverter(Type typeToConvert, JsonSerializerOptions options)
    {
        var valueType = typeToConvert.GetGenericArguments()[0];
        var converterType = typeof(OptionalJsonConverter<>).MakeGenericType(valueType);
        return (JsonConverter)Activator.CreateInstance(converterType)!;
    }
}

// Serializes/deserializes a single Optional<T> as if it were the inner value: reading sets
// HasValue = true and reads Value (which may be null); writing emits the inner value directly.
// (No XML <summary> — see the note on Optional<T> about the XML-comment cache key collision.)
public sealed class OptionalJsonConverter<T> : JsonConverter<Optional<T>>
{
    public override Optional<T> Read(
        ref Utf8JsonReader reader,
        Type typeToConvert,
        JsonSerializerOptions options)
    {
        // Reached only when the property is PRESENT in the JSON (absent properties keep default).
        var value = JsonSerializer.Deserialize<T>(ref reader, options);
        return new Optional<T>(true, value!);
    }

    public override void Write(
        Utf8JsonWriter writer,
        Optional<T> value,
        JsonSerializerOptions options)
    {
        JsonSerializer.Serialize(writer, value.Value, options);
    }
}

/// <summary>Helpers for reflecting over <see cref="Optional{T}"/> types.</summary>
internal static class OptionalType
{
    /// <summary>
    /// When <paramref name="type"/> is a closed <see cref="Optional{T}"/>, returns its inner type
    /// argument; otherwise null.
    /// </summary>
    public static Type? GetInnerType(Type type) =>
        type.IsGenericType && type.GetGenericTypeDefinition() == typeof(Optional<>)
            ? type.GetGenericArguments()[0]
            : null;
}
