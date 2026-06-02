namespace Tmap.Api.Common;

/// <summary>
/// HTTP-scoped implementation of <see cref="ICurrentUser"/> that reads the 'sub' claim
/// from the current request's principal. P2 adds JWT authentication so the claim is
/// actually populated; for now it returns null on every request.
/// </summary>
public class CurrentUser(IHttpContextAccessor httpContextAccessor) : ICurrentUser
{
    public Guid? UserId
    {
        get
        {
            var sub = httpContextAccessor.HttpContext?.User.FindFirst("sub")?.Value;
            return sub is null ? null : Guid.TryParse(sub, out var id) ? id : null;
        }
    }
}
