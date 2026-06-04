using System.Security.Claims;
using Microsoft.AspNetCore.Http;

namespace Tmap.Api.Common;

public sealed class CurrentUser(IHttpContextAccessor accessor) : ICurrentUser
{
    private Guid? TryResolve()
    {
        var principal = accessor.HttpContext?.User;
        if (principal?.Identity?.IsAuthenticated != true)
        {
            return null;
        }

        var raw = principal.FindFirstValue("sub")
                  ?? principal.FindFirstValue(ClaimTypes.NameIdentifier);

        return Guid.TryParse(raw, out var id) ? id : null;
    }

    public bool IsAuthenticated => TryResolve() is not null;

    public Guid Id =>
        TryResolve()
        ?? throw new InvalidOperationException(
            "No authenticated user is available. ICurrentUser is fail-closed for data operations.");
}
