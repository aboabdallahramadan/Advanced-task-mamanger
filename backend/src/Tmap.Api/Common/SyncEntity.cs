namespace Tmap.Api.Common;

public abstract class SyncEntity : IOwnedByUser, ISyncable
{
    public Guid Id { get; set; }
    public Guid UserId { get; set; }
    public DateTimeOffset CreatedAt { get; set; }
    public DateTimeOffset UpdatedAt { get; set; }
    public DateTimeOffset? DeletedAt { get; set; }
    public long ChangeSeq { get; set; }
}
