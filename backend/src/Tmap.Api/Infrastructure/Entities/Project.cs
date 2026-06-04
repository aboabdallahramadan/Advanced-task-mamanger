using Tmap.Api.Common;

namespace Tmap.Api.Infrastructure.Entities;

public class Project : SyncEntity
{
    public string Name { get; set; } = string.Empty;
    public string Color { get; set; } = "#6366f1";
    public string Emoji { get; set; } = "📁";
    public string Rank { get; set; } = string.Empty;
    public int ActualTimeMinutes { get; set; }
}
