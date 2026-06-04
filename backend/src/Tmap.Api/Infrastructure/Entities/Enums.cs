namespace Tmap.Api.Infrastructure.Entities;

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
