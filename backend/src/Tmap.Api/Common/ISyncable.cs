namespace Tmap.Api.Common;

public interface ISyncable
{
    DateTimeOffset CreatedAt { get; set; }
    DateTimeOffset UpdatedAt { get; set; }
    DateTimeOffset? DeletedAt { get; set; }
    long ChangeSeq { get; set; }
}
