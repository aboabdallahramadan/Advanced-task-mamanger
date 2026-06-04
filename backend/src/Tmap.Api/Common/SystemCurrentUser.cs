namespace Tmap.Api.Common;

public sealed class SystemCurrentUser : ICurrentUser
{
    // Fixed, well-known identity for system/background/test contexts.
    public static readonly Guid SystemUserId = new("00000000-0000-0000-0000-000000000001");

    public bool IsAuthenticated => true;
    public Guid Id => SystemUserId;
}
