namespace Tmap.Api.Common;

public interface ICurrentUser
{
    bool IsAuthenticated { get; }
    Guid Id { get; } // THROWS InvalidOperationException if !IsAuthenticated (fail-closed)
}
