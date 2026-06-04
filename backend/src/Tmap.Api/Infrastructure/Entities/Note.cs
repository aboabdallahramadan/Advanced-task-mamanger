using Tmap.Api.Common;

namespace Tmap.Api.Infrastructure.Entities;

public class Note : SyncEntity
{
    public Guid? GroupId { get; set; }
    public Guid? ProjectId { get; set; }
    public string Title { get; set; } = "Untitled";
    public string Content { get; set; } = string.Empty;
    public string Rank { get; set; } = string.Empty;
}
