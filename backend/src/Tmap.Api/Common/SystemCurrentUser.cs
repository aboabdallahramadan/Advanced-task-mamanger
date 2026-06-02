namespace Tmap.Api.Common;

/// <summary>
/// Non-HTTP <see cref="ICurrentUser"/> for migrations, seeding, sync, and test arrange/assert.
/// Constructed with an explicit user id (or <see cref="Guid.Empty"/> for system-only work).
/// </summary>
public sealed class SystemCurrentUser(Guid userId) : ICurrentUser
{
    public bool IsAuthenticated => userId != Guid.Empty;

    public Guid Id =>
        userId != Guid.Empty
            ? userId
            : throw new InvalidOperationException("SystemCurrentUser has no user id set.");
}
