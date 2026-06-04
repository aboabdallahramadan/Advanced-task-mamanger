using Tmap.Api.Common;

namespace Tmap.Api.Infrastructure.Entities;

public class TaskItem : SyncEntity
{
    public string Title { get; set; } = string.Empty;
    public string Notes { get; set; } = string.Empty;
    public Guid? ProjectId { get; set; }
    public List<string> Labels { get; set; } = [];
    public string Source { get; set; } = "local";
    public TaskStatus Status { get; set; } = TaskStatus.Inbox;
    public DateOnly? PlannedDate { get; set; }
    public DateTimeOffset? ScheduledStart { get; set; }
    public DateTimeOffset? ScheduledEnd { get; set; }
    public int DurationMinutes { get; set; } = 30;
    public int ActualTimeMinutes { get; set; }
    public int? Priority { get; set; }
    public int? ReminderMinutes { get; set; }
    public string Rank { get; set; } = string.Empty;
    public DateOnly? DueDate { get; set; } // legacy, carried for round-trip fidelity
    public Guid? RecurrenceRuleId { get; set; }
    public bool IsRecurrenceTemplate { get; set; }
    public bool RecurrenceDetached { get; set; }
    public DateOnly? RecurrenceOriginalDate { get; set; }
    public DateTimeOffset? CompletedAt { get; set; }

    public Project? Project { get; set; }
    public List<Subtask> Subtasks { get; set; } = [];
}
