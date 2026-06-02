namespace Tmap.Api.Common;

public interface IOwnedByUser
{
    Guid UserId { get; set; }
}
