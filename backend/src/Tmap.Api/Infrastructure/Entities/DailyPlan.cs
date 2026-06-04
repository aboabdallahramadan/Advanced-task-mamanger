using Tmap.Api.Common;

namespace Tmap.Api.Infrastructure.Entities;

public class DailyPlan : IOwnedByUser, ISyncable
{
    public Guid UserId { get; set; }
    public DateOnly Date { get; set; }
    public DateTimeOffset CommittedAt { get; set; }
    public List<Guid> PlannedTaskIds { get; set; } = [];
    public int PlannedMinutes { get; set; }

    // sync columns
    public DateTimeOffset CreatedAt { get; set; }
    public DateTimeOffset UpdatedAt { get; set; }
    public DateTimeOffset? DeletedAt { get; set; }
    public long ChangeSeq { get; set; }
}
