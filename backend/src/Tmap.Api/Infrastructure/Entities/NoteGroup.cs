using Tmap.Api.Common;

namespace Tmap.Api.Infrastructure.Entities;

public class NoteGroup : SyncEntity
{
    public string Name { get; set; } = string.Empty;
    public string Emoji { get; set; } = "📝";
    public Guid? ProjectId { get; set; }
    public string Rank { get; set; } = string.Empty;
}
