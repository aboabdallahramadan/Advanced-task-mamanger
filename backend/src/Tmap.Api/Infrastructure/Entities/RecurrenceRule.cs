using Tmap.Api.Common;

namespace Tmap.Api.Infrastructure.Entities;

public class RecurrenceRule : SyncEntity
{
    public RecurrenceFrequency Frequency { get; set; }
    public int IntervalValue { get; set; } = 1;
    public List<int> DaysOfWeek { get; set; } = [];
    public RecurrenceEndType EndType { get; set; } = RecurrenceEndType.Never;
    public int? EndCount { get; set; }
    public DateOnly? EndDate { get; set; }
    public DateOnly? GeneratedUntil { get; set; }
}
