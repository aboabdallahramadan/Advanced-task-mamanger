using System.Security.Claims;
using Microsoft.AspNetCore.Http;

namespace Tmap.Api.Common;

public sealed class CurrentUser(IHttpContextAccessor httpContextAccessor) : ICurrentUser
{
    private ClaimsPrincipal? Principal => httpContextAccessor.HttpContext?.User;

    public bool IsAuthenticated => TryGetId(out _);

    public Guid Id =>
        TryGetId(out var id)
            ? id
            : throw new InvalidOperationException("No authenticated user on the current request.");

    private bool TryGetId(out Guid id)
    {
        id = Guid.Empty;
        var sub =
            Principal?.FindFirstValue(ClaimTypes.NameIdentifier)
            ?? Principal?.FindFirstValue("sub");
        return sub is not null && Guid.TryParse(sub, out id);
    }
}
