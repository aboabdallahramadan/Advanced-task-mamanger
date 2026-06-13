using Microsoft.AspNetCore.Http.HttpResults;
using Microsoft.EntityFrameworkCore;
using Tmap.Api.Common;
using Tmap.Api.Infrastructure;

namespace Tmap.Api.Features.Sync;

public static class SyncEndpoints
{
    // Default and maximum page size; clamped in the handler.
    private const int DefaultLimit = 500;
    private const int MaxLimit = 500;

    public static IEndpointRouteBuilder MapSyncEndpoints(this IEndpointRouteBuilder app)
    {
        var group = app.MapGroup("sync").RequireAuthorization();
        group.MapGet("/", Pull).WithName("Sync");
        return app;
    }

    private static async Task<Ok<SyncResponse>> Pull(
        AppDbContext db,
        ICurrentUser user,
        CancellationToken ct,
        long since = 0,
        int? limit = null)
    {
        var pageSize = Math.Clamp(limit ?? DefaultLimit, 1, MaxLimit);

        // One REPEATABLE READ snapshot for all per-table reads so the page is internally
        // consistent (no torn view of a multi-row bulk write committing mid-read).
        await using var tx = await db.Database.BeginTransactionAsync(
            System.Data.IsolationLevel.RepeatableRead, ct);

        // Each table read: change_seq > since, SoftDelete filter OFF (tombstones included),
        // Tenant filter ON (RLS + EF tenant scope keep cross-user rows out). Take pageSize+1
        // PER TABLE bounds memory; the global merge + cut decides what is actually returned.
        var taskRows = await db.Tasks
            .IgnoreQueryFilters([AppDbContext.SoftDeleteFilter])
            .Where(t => t.ChangeSeq > since)
            .OrderBy(t => t.ChangeSeq)
            .Take(pageSize + 1)
            .Select(t => new TaskSyncRow(
                t.Id, t.Title, t.Notes, t.ProjectId, t.Labels, t.Source, t.Status,
                t.PlannedDate, t.ScheduledStart, t.ScheduledEnd, t.DurationMinutes,
                t.ActualTimeMinutes, t.Priority, t.ReminderMinutes, t.Rank, t.DueDate,
                t.RecurrenceRuleId, t.IsRecurrenceTemplate, t.RecurrenceDetached,
                t.RecurrenceOriginalDate, t.CompletedAt, t.CreatedAt, t.UpdatedAt,
                t.ChangeSeq, t.DeletedAt))
            .ToListAsync(ct);

        var subtaskRows = await db.Subtasks
            .IgnoreQueryFilters([AppDbContext.SoftDeleteFilter])
            .Where(s => s.ChangeSeq > since)
            .OrderBy(s => s.ChangeSeq)
            .Take(pageSize + 1)
            .Select(s => new SubtaskSyncRow(
                s.Id, s.TaskId, s.Title, s.Completed, s.SortOrder,
                s.CreatedAt, s.UpdatedAt, s.ChangeSeq, s.DeletedAt))
            .ToListAsync(ct);

        var projectRows = await db.Projects
            .IgnoreQueryFilters([AppDbContext.SoftDeleteFilter])
            .Where(p => p.ChangeSeq > since)
            .OrderBy(p => p.ChangeSeq)
            .Take(pageSize + 1)
            .Select(p => new ProjectSyncRow(
                p.Id, p.Name, p.Color, p.Emoji, p.Rank, p.ActualTimeMinutes,
                p.CreatedAt, p.UpdatedAt, p.ChangeSeq, p.DeletedAt))
            .ToListAsync(ct);

        var noteGroupRows = await db.NoteGroups
            .IgnoreQueryFilters([AppDbContext.SoftDeleteFilter])
            .Where(g => g.ChangeSeq > since)
            .OrderBy(g => g.ChangeSeq)
            .Take(pageSize + 1)
            .Select(g => new NoteGroupSyncRow(
                g.Id, g.Name, g.Emoji, g.ProjectId, g.Rank,
                g.CreatedAt, g.UpdatedAt, g.ChangeSeq, g.DeletedAt))
            .ToListAsync(ct);

        var noteRows = await db.Notes
            .IgnoreQueryFilters([AppDbContext.SoftDeleteFilter])
            .Where(n => n.ChangeSeq > since)
            .OrderBy(n => n.ChangeSeq)
            .Take(pageSize + 1)
            .Select(n => new NoteSyncRow(
                n.Id, n.GroupId, n.ProjectId, n.Title, n.Content, n.Rank,
                n.CreatedAt, n.UpdatedAt, n.ChangeSeq, n.DeletedAt))
            .ToListAsync(ct);

        var ruleRows = await db.RecurrenceRules
            .IgnoreQueryFilters([AppDbContext.SoftDeleteFilter])
            .Where(r => r.ChangeSeq > since)
            .OrderBy(r => r.ChangeSeq)
            .Take(pageSize + 1)
            .Select(r => new RecurrenceRuleSyncRow(
                r.Id, r.Frequency, r.IntervalValue, r.DaysOfWeek, r.EndType,
                r.EndCount, r.EndDate, r.GeneratedUntil, r.CreatedAt, r.UpdatedAt,
                r.ChangeSeq, r.DeletedAt))
            .ToListAsync(ct);

        var focusRows = await db.FocusSessions
            .IgnoreQueryFilters([AppDbContext.SoftDeleteFilter])
            .Where(f => f.ChangeSeq > since)
            .OrderBy(f => f.ChangeSeq)
            .Take(pageSize + 1)
            .Select(f => new FocusSessionSyncRow(
                f.Id, f.TaskId, f.Project, f.StartedAt, f.EndedAt, f.Minutes, f.Date,
                f.CreatedAt, f.UpdatedAt, f.ChangeSeq, f.DeletedAt))
            .ToListAsync(ct);

        var planRows = await db.DailyPlans
            .IgnoreQueryFilters([AppDbContext.SoftDeleteFilter])
            .Where(p => p.ChangeSeq > since)
            .OrderBy(p => p.ChangeSeq)
            .Take(pageSize + 1)
            .Select(p => new DailyPlanSyncRow(
                p.Date, p.CommittedAt, p.PlannedTaskIds, p.PlannedMinutes,
                p.ChangeSeq, p.DeletedAt))
            .ToListAsync(ct);

        var settingRows = await db.UserSettings
            .IgnoreQueryFilters([AppDbContext.SoftDeleteFilter])
            .Where(s => s.ChangeSeq > since)
            .OrderBy(s => s.ChangeSeq)
            .Take(pageSize + 1)
            .Select(s => new SettingSyncRow(s.Key, s.Value, s.ChangeSeq, s.DeletedAt))
            .ToListAsync(ct);

        await tx.CommitAsync(ct);

        // Global merge by change_seq, then cut at pageSize. Track each row's seq so the cut
        // is decided once across every table; HasMore = any table had a row beyond the cut.
        var tagged = new List<(long Seq, int Table)>(
            taskRows.Count + subtaskRows.Count + projectRows.Count + noteGroupRows.Count +
            noteRows.Count + ruleRows.Count + focusRows.Count + planRows.Count + settingRows.Count);

        void Tag(IEnumerable<long> seqs, int table)
        {
            foreach (var seq in seqs)
            {
                tagged.Add((seq, table));
            }
        }

        Tag(taskRows.Select(r => r.ChangeSeq), 0);
        Tag(subtaskRows.Select(r => r.ChangeSeq), 1);
        Tag(projectRows.Select(r => r.ChangeSeq), 2);
        Tag(noteGroupRows.Select(r => r.ChangeSeq), 3);
        Tag(noteRows.Select(r => r.ChangeSeq), 4);
        Tag(ruleRows.Select(r => r.ChangeSeq), 5);
        Tag(focusRows.Select(r => r.ChangeSeq), 6);
        Tag(planRows.Select(r => r.ChangeSeq), 7);
        Tag(settingRows.Select(r => r.ChangeSeq), 8);

        tagged.Sort((a, b) => a.Seq.CompareTo(b.Seq));

        var hasMore = tagged.Count > pageSize;
        var kept = hasMore ? tagged.Take(pageSize).ToList() : tagged;

        // The seq of the last kept row is the cut boundary; everything <= it (per table) is returned.
        var cutSeq = kept.Count > 0 ? kept[^1].Seq : since;

        var changes = new SyncChanges(
            Tasks: taskRows.Where(r => r.ChangeSeq <= cutSeq).ToList(),
            Subtasks: subtaskRows.Where(r => r.ChangeSeq <= cutSeq).ToList(),
            Projects: projectRows.Where(r => r.ChangeSeq <= cutSeq).ToList(),
            NoteGroups: noteGroupRows.Where(r => r.ChangeSeq <= cutSeq).ToList(),
            Notes: noteRows.Where(r => r.ChangeSeq <= cutSeq).ToList(),
            RecurrenceRules: ruleRows.Where(r => r.ChangeSeq <= cutSeq).ToList(),
            FocusSessions: focusRows.Where(r => r.ChangeSeq <= cutSeq).ToList(),
            DailyPlans: planRows.Where(r => r.ChangeSeq <= cutSeq).ToList(),
            Settings: settingRows.Where(r => r.ChangeSeq <= cutSeq).ToList());

        var nextSince = kept.Count > 0 ? cutSeq : since;
        return TypedResults.Ok(new SyncResponse(changes, nextSince, hasMore));
    }
}
