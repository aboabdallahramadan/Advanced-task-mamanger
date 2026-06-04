using Tmap.Api.Common;

namespace Tmap.Api.Infrastructure.Entities;

public class UserSetting : IOwnedByUser, ISyncable
{
    public Guid UserId { get; set; }
    public string Key { get; set; } = string.Empty;
    public string Value { get; set; } = string.Empty; // jsonb text

    // sync columns
    public DateTimeOffset CreatedAt { get; set; }
    public DateTimeOffset UpdatedAt { get; set; }
    public DateTimeOffset? DeletedAt { get; set; }
    public long ChangeSeq { get; set; }
}
