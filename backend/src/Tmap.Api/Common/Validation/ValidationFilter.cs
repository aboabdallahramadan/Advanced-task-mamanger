using FluentValidation;

namespace Tmap.Api.Common.Validation;

public sealed class ValidationFilter<T>(IValidator<T> validator) : IEndpointFilter
{
    public async ValueTask<object?> InvokeAsync(EndpointFilterInvocationContext ctx, EndpointFilterDelegate next)
    {
        var arg = ctx.Arguments.OfType<T>().First();
        var r = await validator.ValidateAsync(arg);
        if (!r.IsValid) return TypedResults.ValidationProblem(r.ToDictionary());
        return await next(ctx);
    }
}
