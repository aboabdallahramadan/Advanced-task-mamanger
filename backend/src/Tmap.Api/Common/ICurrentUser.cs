namespace Tmap.Api.Common;

/// <summary>
/// Provides the identity of the currently authenticated user within the HTTP scope.
/// P2 populates UserId from the JWT 'sub' claim; P1 hardens fail-closed behavior.
/// </summary>
public interface ICurrentUser
{
    /// <summary>Gets the user ID from the 'sub' claim, or null when the request is unauthenticated.</summary>
    Guid? UserId { get; }
}
