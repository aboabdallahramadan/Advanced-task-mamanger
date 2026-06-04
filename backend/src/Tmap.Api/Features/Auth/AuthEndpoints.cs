using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Identity;
using Microsoft.AspNetCore.Routing;
using Tmap.Api.Infrastructure.Entities;

namespace Tmap.Api.Features.Auth;

public static class AuthEndpoints
{
    public static IEndpointRouteBuilder MapAuthEndpoints(this IEndpointRouteBuilder app)
    {
        var group = app.MapGroup("/api/v1/auth");

        // Registration is anonymous: it is how a user gets their first credentials.
        group
            .MapPost("/register", RegisterAsync)
            .WithName("Register")
            .AllowAnonymous();

        return app;
    }

    private static async Task<IResult> RegisterAsync(
        RegisterRequest request,
        UserManager<ApplicationUser> userManager
    )
    {
        var email = request.Email?.Trim() ?? string.Empty;

        var user = new ApplicationUser
        {
            Id = Guid.NewGuid(),
            UserName = email,
            Email = email,
        };

        // UserManager enforces the SP1 password policy (min length 10) and email uniqueness.
        // On failure it returns a non-throwing IdentityResult whose errors we surface as 400.
        var result = await userManager.CreateAsync(user, request.Password ?? string.Empty);
        if (!result.Succeeded)
        {
            var errors = result.Errors.ToDictionary(
                e => e.Code,
                e => new[] { e.Description }
            );
            return TypedResults.ValidationProblem(errors);
        }

        return TypedResults.Created($"/api/v1/users/{user.Id}", new RegisterResponse(user.Id));
    }
}

public sealed record RegisterRequest(string? Email, string? Password);

public sealed record RegisterResponse(Guid UserId);
