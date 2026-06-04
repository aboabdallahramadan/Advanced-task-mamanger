using Tmap.Api.Common;

namespace Tmap.Api.Infrastructure.Entities;

public class Subtask : SyncEntity
{
    public Guid TaskId { get; set; }
    public string Title { get; set; } = string.Empty;
    public bool Completed { get; set; }
    public int SortOrder { get; set; }

    public TaskItem? Task { get; set; }
}
