using System.Net;
using System.Net.Http.Json;
using FluentAssertions;
using Microsoft.EntityFrameworkCore;
using Tmap.Api.Features.Reports;
using Tmap.Api.Infrastructure.Entities;
using Xunit;
using TaskStatus = Tmap.Api.Infrastructure.Entities.TaskStatus;

namespace Tmap.Api.Tests;

[Collection("db")]
public class ReportsTests(PostgresFixture fixture) : IntegrationTestBase(fixture)
{
    [Fact]
    public async Task GetData_AggregatesCompletedTasksSessionsAndPlans_WithProjectNames()
    {
        var user = await RegisterAsync();

        var projectId = Guid.CreateVersion7();
        var doneId = Guid.CreateVersion7();
        var noProjectDoneId = Guid.CreateVersion7();

        await using (var db = NewElevatedDbContext())
        {
            db.Add(new Project { Id = projectId, UserId = user.UserId, Name = "Acme", Color = "#fff", Emoji = "", Rank = "a0", ActualTimeMinutes = 0 });
            // Completed task with a project, completed at 2026-06-01T10:00Z (UTC -> 2026-06-01 local in UTC tz).
            db.Add(new TaskItem
            {
                Id = doneId, UserId = user.UserId, Title = "T1", Notes = "", ProjectId = projectId,
                Labels = new(), Source = "local", Status = TaskStatus.Done, Rank = "a0",
                DurationMinutes = 30, ActualTimeMinutes = 0, CompletedAt = DateTimeOffset.Parse("2026-06-01T10:00:00Z"),
            });
            // Completed task with NO project.
            db.Add(new TaskItem
            {
                Id = noProjectDoneId, UserId = user.UserId, Title = "T2", Notes = "", ProjectId = null,
                Labels = new(), Source = "local", Status = TaskStatus.Done, Rank = "a1",
                DurationMinutes = 30, ActualTimeMinutes = 0, CompletedAt = DateTimeOffset.Parse("2026-06-01T11:00:00Z"),
            });
            db.Add(new FocusSession
            {
                Id = Guid.CreateVersion7(), UserId = user.UserId, TaskId = doneId, Project = "Acme",
                StartedAt = DateTimeOffset.Parse("2026-06-01T09:00:00Z"),
                EndedAt = DateTimeOffset.Parse("2026-06-01T09:25:00Z"), Minutes = 25, Date = new DateOnly(2026, 6, 1),
            });
            db.Add(new DailyPlan
            {
                UserId = user.UserId, Date = new DateOnly(2026, 6, 1), CommittedAt = DateTimeOffset.UtcNow,
                PlannedTaskIds = new List<Guid> { doneId }, PlannedMinutes = 60,
            });
            await db.SaveChangesAsync();
        }

        var dto = await user.Client.GetFromJsonAsync<ReportDataResponse>(
            "/api/v1/reports?start=2026-06-01&end=2026-06-01");

        dto!.CompletedTasks.Should().HaveCount(2);
        dto.CompletedTasks.Should().ContainSingle(t => t.Id == doneId && t.Project == "Acme" && t.Date == new DateOnly(2026, 6, 1));
        dto.CompletedTasks.Should().ContainSingle(t => t.Id == noProjectDoneId && t.Project == "");
        dto.Sessions.Should().ContainSingle(s => s.Project == "Acme" && s.Minutes == 25 && s.Date == new DateOnly(2026, 6, 1));
        dto.DailyPlans.Should().ContainSingle(p => p.Date == new DateOnly(2026, 6, 1) && p.PlannedMinutes == 60);
    }

    [Fact]
    public async Task GetData_ExcludesSoftDeletedRows()
    {
        var user = await RegisterAsync();
        var liveId = Guid.CreateVersion7();
        var deletedId = Guid.CreateVersion7();

        await using (var db = NewElevatedDbContext())
        {
            db.Add(new TaskItem
            {
                Id = liveId, UserId = user.UserId, Title = "live", Notes = "", Labels = new(), Source = "local",
                Status = TaskStatus.Done, Rank = "a0", DurationMinutes = 1, ActualTimeMinutes = 0,
                CompletedAt = DateTimeOffset.Parse("2026-07-01T10:00:00Z"),
            });
            db.Add(new TaskItem
            {
                Id = deletedId, UserId = user.UserId, Title = "deleted", Notes = "", Labels = new(), Source = "local",
                Status = TaskStatus.Done, Rank = "a1", DurationMinutes = 1, ActualTimeMinutes = 0,
                CompletedAt = DateTimeOffset.Parse("2026-07-01T11:00:00Z"),
                DeletedAt = DateTimeOffset.UtcNow,
            });
            await db.SaveChangesAsync();
        }

        var dto = await user.Client.GetFromJsonAsync<ReportDataResponse>(
            "/api/v1/reports?start=2026-07-01&end=2026-07-01");

        dto!.CompletedTasks.Should().ContainSingle(t => t.Id == liveId);
        dto.CompletedTasks.Should().NotContain(t => t.Id == deletedId);
    }

    [Fact]
    public async Task GetData_BucketsCompletedAtInUserTimeZone()
    {
        var user = await RegisterAsync();

        // Set the user's tz to a positive offset so a late-UTC completion rolls into the NEXT local day.
        await using (var db = NewElevatedDbContext())
        {
            var u = await db.Users.SingleAsync(x => x.Id == user.UserId);
            u.TimeZoneId = "Asia/Tokyo"; // UTC+9, no DST
            // Completed 2026-08-10T20:00Z == 2026-08-11T05:00 Tokyo -> local day is 08-11.
            db.Add(new TaskItem
            {
                Id = Guid.CreateVersion7(), UserId = user.UserId, Title = "tz", Notes = "", Labels = new(),
                Source = "local", Status = TaskStatus.Done, Rank = "a0", DurationMinutes = 1, ActualTimeMinutes = 0,
                CompletedAt = DateTimeOffset.Parse("2026-08-10T20:00:00Z"),
            });
            await db.SaveChangesAsync();
        }

        // Query the LOCAL day 08-11; the UTC-day (08-10) must NOT match.
        var hit = await user.Client.GetFromJsonAsync<ReportDataResponse>(
            "/api/v1/reports?start=2026-08-11&end=2026-08-11");
        hit!.CompletedTasks.Should().HaveCount(1);
        hit.CompletedTasks[0].Date.Should().Be(new DateOnly(2026, 8, 11));

        var miss = await user.Client.GetFromJsonAsync<ReportDataResponse>(
            "/api/v1/reports?start=2026-08-10&end=2026-08-10");
        miss!.CompletedTasks.Should().BeEmpty();
    }

    [Fact]
    public async Task GetData_IsScopedPerUser()
    {
        var a = await RegisterAsync();
        var b = await RegisterAsync();
        await using (var db = NewElevatedDbContext())
        {
            db.Add(new TaskItem
            {
                Id = Guid.CreateVersion7(), UserId = a.UserId, Title = "a", Notes = "", Labels = new(),
                Source = "local", Status = TaskStatus.Done, Rank = "a0", DurationMinutes = 1, ActualTimeMinutes = 0,
                CompletedAt = DateTimeOffset.Parse("2026-09-01T10:00:00Z"),
            });
            await db.SaveChangesAsync();
        }

        var bDto = await b.Client.GetFromJsonAsync<ReportDataResponse>(
            "/api/v1/reports?start=2026-09-01&end=2026-09-01");
        bDto!.CompletedTasks.Should().BeEmpty();
    }

    [Fact]
    public async Task GetData_RequiresAuthentication()
    {
        var resp = await Client.GetAsync("/api/v1/reports?start=2026-06-01&end=2026-06-01");
        resp.StatusCode.Should().Be(HttpStatusCode.Unauthorized);
    }
}
