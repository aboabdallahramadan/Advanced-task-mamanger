using System.Text.Json.Serialization;

namespace Tmap.Api.Infrastructure.Entities;

[JsonConverter(typeof(JsonStringEnumConverter))]
public enum TaskStatus
{
    Inbox,
    Backlog,
    Planned,
    Scheduled,
    Done,
    Archived
}

public enum RecurrenceFrequency
{
    Daily,
    Weekly
}

public enum RecurrenceEndType
{
    Never,
    Count,
    Date
}
