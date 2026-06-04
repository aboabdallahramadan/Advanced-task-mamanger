using Tmap.Api.Common;

namespace Tmap.Api.Infrastructure.Entities;

// insert/tombstone-only
public class RecurrenceException : SyncEntity
{
    public Guid RecurrenceRuleId { get; set; }
    public DateOnly ExceptionDate { get; set; }
}
