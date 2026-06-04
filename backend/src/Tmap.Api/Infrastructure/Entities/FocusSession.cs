using Tmap.Api.Common;

namespace Tmap.Api.Infrastructure.Entities;

// append-only
public class FocusSession : SyncEntity
{
    public Guid? TaskId { get; set; }
    public string Project { get; set; } = string.Empty; // NAME snapshot, not an FK
    public DateTimeOffset StartedAt { get; set; }
    public DateTimeOffset EndedAt { get; set; }
    public int Minutes { get; set; }
    public DateOnly Date { get; set; }
}
