namespace Tmap.Api.Common;

public interface ICurrentUser
{
    bool IsAuthenticated { get; }

    /// <summary>The authenticated user's id. THROWS if !IsAuthenticated (fail-closed).</summary>
    Guid Id { get; }
}
