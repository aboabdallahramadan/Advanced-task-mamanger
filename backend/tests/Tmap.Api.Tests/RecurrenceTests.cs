using System.Net;
using System.Net.Http.Json;
using FluentAssertions;
using Microsoft.EntityFrameworkCore;
using Tmap.Api.Features.Recurrence;
using Tmap.Api.Infrastructure.Entities;
using Xunit;

namespace Tmap.Api.Tests;

[Collection("db")]
public class RecurrenceTests(PostgresFixture fixture) : IntegrationTestBase(fixture)
{
    private static CreateRecurringTaskRequest WeeklyMonWedFri(string? plannedDate = "2026-06-01") =>
        new(
            new RecurringTaskInput(
                Title: "Standup", Notes: "", ProjectId: null, Labels: new(), Source: "local",
                PlannedDate: plannedDate is null ? null : DateOnly.Parse(plannedDate),
                DurationMinutes: 15, Priority: null, ReminderMinutes: 0),
            new RecurrenceRuleInput(
                Frequency: RecurrenceFrequency.Weekly, Interval: 1, DaysOfWeek: new List<int> { 1, 3, 5 },
                EndType: RecurrenceEndType.Never, EndCount: null, EndDate: null));

    // Next Monday strictly after "today" (server uses DateTime.UtcNow for the today cutoff).
    // Guarantees every Mon/Wed/Fri occurrence generated from this anchor is a FUTURE instance,
    // independent of the wall clock the test suite happens to run on.
    private static DateOnly NextFutureMonday()
    {
        var today = DateOnly.FromDateTime(DateTime.UtcNow);
        var daysUntilMonday = ((int)DayOfWeek.Monday - (int)today.DayOfWeek + 7) % 7;
        if (daysUntilMonday == 0)
        {
            daysUntilMonday = 7; // strictly after today
        }

        return today.AddDays(daysUntilMonday);
    }

    [Fact]
    public async Task GetRule_ReturnsCreatedRule()
    {
        var user = await RegisterAsync();
        var created = await (await user.Client.PostAsJsonAsync("/api/v1/recurrence", WeeklyMonWedFri()))
            .Content.ReadFromJsonAsync<RecurrenceTaskResponse[]>();

        var ruleId = created![0].RecurrenceRuleId!.Value;
        var rule = await user.Client.GetFromJsonAsync<RecurrenceRuleResponse>($"/api/v1/recurrence/rules/{ruleId}");

        rule!.Id.Should().Be(ruleId);
        rule.Frequency.Should().Be(RecurrenceFrequency.Weekly);
        rule.DaysOfWeek.Should().BeEquivalentTo(new[] { 1, 3, 5 });
    }

    [Fact]
    public async Task GetRule_ReturnsNotFound_ForUnknownId()
    {
        var user = await RegisterAsync();
        var resp = await user.Client.GetAsync($"/api/v1/recurrence/rules/{Guid.NewGuid()}");
        resp.StatusCode.Should().Be(HttpStatusCode.NotFound);
    }

    [Fact]
    public async Task UpdateRule_MutatesFields_ResetsGeneratedUntil_AndTombstonesFutureInstances()
    {
        var user = await RegisterAsync();
        // Anchor the template's start to a date strictly after "today" so every generated
        // instance is a FUTURE instance — the handler intentionally only tombstones
        // PlannedDate >= today, so the all-live assertion below must see exclusively future rows.
        var monday = NextFutureMonday();
        var created = await (await user.Client.PostAsJsonAsync(
                "/api/v1/recurrence", WeeklyMonWedFri(monday.ToString("yyyy-MM-dd"))))
            .Content.ReadFromJsonAsync<RecurrenceTaskResponse[]>();
        var ruleId = created![0].RecurrenceRuleId!.Value;

        // Ensure some future instances exist.
        var rangeStart = monday.ToString("yyyy-MM-dd");
        var rangeEnd = monday.AddDays(29).ToString("yyyy-MM-dd");
        await user.Client.PostAsync(
            $"/api/v1/recurrence/ensure-instances?start={rangeStart}&end={rangeEnd}", null);

        var patch = await user.Client.PatchAsJsonAsync($"/api/v1/recurrence/rules/{ruleId}",
            new UpdateRuleRequest(Frequency: RecurrenceFrequency.Daily, Interval: 2,
                DaysOfWeek: new List<int>(), EndType: RecurrenceEndType.Never, EndCount: null, EndDate: null));
        patch.StatusCode.Should().Be(HttpStatusCode.OK);
        var updated = await patch.Content.ReadFromJsonAsync<RecurrenceRuleResponse>();
        updated!.Frequency.Should().Be(RecurrenceFrequency.Daily);
        updated.Interval.Should().Be(2);
        updated.GeneratedUntil.Should().BeNull();

        await using var db = NewElevatedDbContext();
        // Future, non-detached, not-done instances should be tombstoned.
        var liveFuture = await db.Set<TaskItem>()
            .Where(t => t.RecurrenceRuleId == ruleId && !t.IsRecurrenceTemplate
                        && !t.RecurrenceDetached && t.DeletedAt == null)
            .CountAsync();
        liveFuture.Should().Be(0);
    }

    [Fact]
    public async Task GetRule_IsScopedPerUser()
    {
        var a = await RegisterAsync();
        var b = await RegisterAsync();
        var created = await (await a.Client.PostAsJsonAsync("/api/v1/recurrence", WeeklyMonWedFri()))
            .Content.ReadFromJsonAsync<RecurrenceTaskResponse[]>();

        var resp = await b.Client.GetAsync($"/api/v1/recurrence/rules/{created![0].RecurrenceRuleId!.Value}");
        resp.StatusCode.Should().Be(HttpStatusCode.NotFound); // tenant filter hides it
    }

    [Fact]
    public async Task Create_PersistsTemplateAndRule_NoInstancesYet()
    {
        var user = await RegisterAsync();
        var resp = await user.Client.PostAsJsonAsync("/api/v1/recurrence", WeeklyMonWedFri());
        resp.StatusCode.Should().Be(HttpStatusCode.Created);
        var dtoArr = await resp.Content.ReadFromJsonAsync<RecurrenceTaskResponse[]>();
        dtoArr.Should().NotBeNullOrEmpty();
        var dto = dtoArr![0];
        dto.IsRecurrenceTemplate.Should().BeTrue();
        dto.RecurrenceRuleId.Should().NotBe(Guid.Empty);

        await using var db = NewElevatedDbContext();
        var template = await db.Set<TaskItem>().SingleAsync(t => t.Id == dto.Id);
        template.IsRecurrenceTemplate.Should().BeTrue();
        template.RecurrenceOriginalDate.Should().Be(new DateOnly(2026, 6, 1));
        template.UserId.Should().Be(user.UserId);

        var instanceCount = await db.Set<TaskItem>()
            .CountAsync(t => t.RecurrenceRuleId == dto.RecurrenceRuleId && !t.IsRecurrenceTemplate);
        instanceCount.Should().Be(0); // generation is deferred to ensure-instances
    }

    [Fact]
    public async Task UpdateSeries_PropagatesToTemplateAndFutureLiveInstances()
    {
        var user = await RegisterAsync();
        // Anchor to a future Monday so every generated instance is in the future and the
        // handler (which propagates only to future live instances) renames all of them.
        var monday = NextFutureMonday();
        var created = await (await user.Client.PostAsJsonAsync(
                "/api/v1/recurrence", WeeklyMonWedFri(monday.ToString("yyyy-MM-dd"))))
            .Content.ReadFromJsonAsync<RecurrenceTaskResponse[]>();
        var ruleId = created![0].RecurrenceRuleId!.Value;
        var rangeStart = monday.ToString("yyyy-MM-dd");
        var rangeEnd = monday.AddDays(29).ToString("yyyy-MM-dd");
        await user.Client.PostAsync(
            $"/api/v1/recurrence/ensure-instances?start={rangeStart}&end={rangeEnd}", null);

        var patch = await user.Client.PatchAsJsonAsync($"/api/v1/recurrence/rules/{ruleId}/series",
            new UpdateSeriesRequest(Title: "Renamed Standup", Notes: null, ProjectId: null,
                Labels: null, DurationMinutes: 45, Priority: null, ReminderMinutes: null));
        patch.StatusCode.Should().Be(HttpStatusCode.NoContent);

        await using var db = NewElevatedDbContext();
        var template = await db.Set<TaskItem>()
            .SingleAsync(t => t.RecurrenceRuleId == ruleId && t.IsRecurrenceTemplate);
        template.Title.Should().Be("Renamed Standup");
        template.DurationMinutes.Should().Be(45);

        var future = await db.Set<TaskItem>()
            .Where(t => t.RecurrenceRuleId == ruleId && !t.IsRecurrenceTemplate && t.DeletedAt == null)
            .ToListAsync();
        future.Should().OnlyContain(t => t.Title == "Renamed Standup" && t.DurationMinutes == 45);
    }

    [Fact]
    public async Task DeleteSeries_SoftDeletesRuleTasksAndExceptions()
    {
        var user = await RegisterAsync();
        var created = await (await user.Client.PostAsJsonAsync("/api/v1/recurrence", WeeklyMonWedFri()))
            .Content.ReadFromJsonAsync<RecurrenceTaskResponse[]>();
        var ruleId = created![0].RecurrenceRuleId!.Value;
        await user.Client.PostAsync("/api/v1/recurrence/ensure-instances?start=2026-06-01&end=2026-06-30", null);

        var del = await user.Client.DeleteAsync($"/api/v1/recurrence/rules/{ruleId}");
        del.StatusCode.Should().Be(HttpStatusCode.NoContent);

        await using var db = NewElevatedDbContext();
        // No live rows remain; but rows still EXIST (tombstoned) when soft-delete filter is ignored.
        var liveTasks = await db.Set<TaskItem>().CountAsync(t => t.RecurrenceRuleId == ruleId);
        liveTasks.Should().Be(0);
        var allTasks = await db.Set<TaskItem>()
            .IgnoreQueryFilters(["SoftDelete"]).CountAsync(t => t.RecurrenceRuleId == ruleId);
        allTasks.Should().BeGreaterThan(0);

        var liveRule = await db.Set<RecurrenceRule>().CountAsync(r => r.Id == ruleId);
        liveRule.Should().Be(0);
        var tombstonedRule = await db.Set<RecurrenceRule>()
            .IgnoreQueryFilters(["SoftDelete"]).SingleAsync(r => r.Id == ruleId);
        tombstonedRule.DeletedAt.Should().NotBeNull();

        // getRule now 404s (soft-delete filter active).
        var getResp = await user.Client.GetAsync($"/api/v1/recurrence/rules/{ruleId}");
        getResp.StatusCode.Should().Be(HttpStatusCode.NotFound);
    }

    [Fact]
    public async Task DeleteSeriesFuture_TombstonesFutureInstances_AndCapsRule()
    {
        var user = await RegisterAsync();
        var created = await (await user.Client.PostAsJsonAsync("/api/v1/recurrence", WeeklyMonWedFri()))
            .Content.ReadFromJsonAsync<RecurrenceTaskResponse[]>();
        var ruleId = created![0].RecurrenceRuleId!.Value;
        await user.Client.PostAsync("/api/v1/recurrence/ensure-instances?start=2026-06-01&end=2026-06-30", null);

        var resp = await user.Client.PostAsJsonAsync(
            $"/api/v1/recurrence/rules/{ruleId}/delete-future",
            new DeleteSeriesFutureRequest(new DateOnly(2026, 6, 15)));
        resp.StatusCode.Should().Be(HttpStatusCode.NoContent);

        await using var db = NewElevatedDbContext();
        var liveOnOrAfter = await db.Set<TaskItem>()
            .CountAsync(t => t.RecurrenceRuleId == ruleId && !t.IsRecurrenceTemplate
                             && t.PlannedDate >= new DateOnly(2026, 6, 15));
        liveOnOrAfter.Should().Be(0);

        var liveBefore = await db.Set<TaskItem>()
            .CountAsync(t => t.RecurrenceRuleId == ruleId && !t.IsRecurrenceTemplate
                             && t.PlannedDate < new DateOnly(2026, 6, 15));
        liveBefore.Should().BeGreaterThan(0);

        var rule = await db.Set<RecurrenceRule>().SingleAsync(r => r.Id == ruleId);
        rule.EndType.Should().Be(RecurrenceEndType.Date);
        rule.EndDate.Should().Be(new DateOnly(2026, 6, 14)); // day before fromDate
    }

    [Fact]
    public async Task DetachInstance_MarksInstanceDetached()
    {
        var user = await RegisterAsync();
        var created = await (await user.Client.PostAsJsonAsync("/api/v1/recurrence", WeeklyMonWedFri()))
            .Content.ReadFromJsonAsync<RecurrenceTaskResponse[]>();
        var ruleId = created![0].RecurrenceRuleId!.Value;
        await user.Client.PostAsync("/api/v1/recurrence/ensure-instances?start=2026-06-01&end=2026-06-30", null);

        Guid instanceId;
        await using (var db = NewElevatedDbContext())
        {
            instanceId = await db.Set<TaskItem>()
                .Where(t => t.RecurrenceRuleId == ruleId && !t.IsRecurrenceTemplate)
                .OrderBy(t => t.PlannedDate).Select(t => t.Id).FirstAsync();
        }

        var resp = await user.Client.PatchAsync($"/api/v1/recurrence/instances/{instanceId}/detach", null);
        resp.StatusCode.Should().Be(HttpStatusCode.NoContent);

        await using var db2 = NewElevatedDbContext();
        var inst = await db2.Set<TaskItem>().SingleAsync(t => t.Id == instanceId);
        inst.RecurrenceDetached.Should().BeTrue();
    }

    [Fact]
    public async Task EnsureInstances_GeneratesExpectedWeeklyDates()
    {
        var user = await RegisterAsync();
        var created = await (await user.Client.PostAsJsonAsync("/api/v1/recurrence", WeeklyMonWedFri()))
            .Content.ReadFromJsonAsync<RecurrenceTaskResponse[]>();
        var ruleId = created![0].RecurrenceRuleId!.Value;

        var resp = await user.Client.PostAsync(
            "/api/v1/recurrence/ensure-instances?start=2026-06-01&end=2026-06-07", null);
        resp.StatusCode.Should().Be(HttpStatusCode.OK);
        var body = await resp.Content.ReadFromJsonAsync<RecurrenceTaskResponse[]>();

        // Mon 06-01, Wed 06-03, Fri 06-05 within the first week.
        body!.Select(c => DateOnly.Parse(c.PlannedDate!)).Should()
            .BeEquivalentTo(new[] { new DateOnly(2026, 6, 1), new DateOnly(2026, 6, 3), new DateOnly(2026, 6, 5) });

        await using var db = NewElevatedDbContext();
        var rule = await db.Set<RecurrenceRule>().SingleAsync(r => r.Id == ruleId);
        rule.GeneratedUntil.Should().Be(new DateOnly(2026, 6, 7));
    }

    [Fact]
    public async Task EnsureInstances_IsIdempotent_NoDuplicatesOnSecondCall()
    {
        var user = await RegisterAsync();
        var created = await (await user.Client.PostAsJsonAsync("/api/v1/recurrence", WeeklyMonWedFri()))
            .Content.ReadFromJsonAsync<RecurrenceTaskResponse[]>();
        var ruleId = created![0].RecurrenceRuleId!.Value;

        var first = await user.Client.PostAsync(
            "/api/v1/recurrence/ensure-instances?start=2026-06-01&end=2026-06-07", null);
        var firstBody = await first.Content.ReadFromJsonAsync<RecurrenceTaskResponse[]>();
        firstBody!.Should().HaveCount(3);

        // Second identical call must create NOTHING new.
        var second = await user.Client.PostAsync(
            "/api/v1/recurrence/ensure-instances?start=2026-06-01&end=2026-06-07", null);
        var secondBody = await second.Content.ReadFromJsonAsync<RecurrenceTaskResponse[]>();
        secondBody!.Should().BeEmpty();

        await using var db = NewElevatedDbContext();
        var total = await db.Set<TaskItem>()
            .CountAsync(t => t.RecurrenceRuleId == ruleId && !t.IsRecurrenceTemplate);
        total.Should().Be(3); // exactly the first batch
    }

    [Fact]
    public async Task EnsureInstances_ConcurrentCalls_DoNotDoubleCreate()
    {
        var user = await RegisterAsync();
        var created = await (await user.Client.PostAsJsonAsync("/api/v1/recurrence", WeeklyMonWedFri()))
            .Content.ReadFromJsonAsync<RecurrenceTaskResponse[]>();
        var ruleId = created![0].RecurrenceRuleId!.Value;

        const string url = "/api/v1/recurrence/ensure-instances?start=2026-06-01&end=2026-06-30";
        // Fire two concurrent calls on the same authed client.
        var t1 = user.Client.PostAsync(url, null);
        var t2 = user.Client.PostAsync(url, null);
        await Task.WhenAll(t1, t2);

        await using var db = NewElevatedDbContext();
        // One row per unique occurrence date, never two for the same date.
        var dates = await db.Set<TaskItem>()
            .Where(t => t.RecurrenceRuleId == ruleId && !t.IsRecurrenceTemplate)
            .Select(t => t.RecurrenceOriginalDate)
            .ToListAsync();
        dates.Should().OnlyHaveUniqueItems();
    }

    [Fact]
    public async Task EnsureInstances_RequiresAuthentication()
    {
        var resp = await Client.PostAsync(
            "/api/v1/recurrence/ensure-instances?start=2026-06-01&end=2026-06-07", null);
        resp.StatusCode.Should().Be(HttpStatusCode.Unauthorized);
    }

    [Fact]
    public async Task EnsureInstances_returns_full_TaskResponse_array()
    {
        var user = await RegisterAsync();
        var monday = NextFutureMonday();
        var created = await (await user.Client.PostAsJsonAsync(
                "/api/v1/recurrence", WeeklyMonWedFri(monday.ToString("yyyy-MM-dd"))))
            .Content.ReadFromJsonAsync<RecurrenceTaskResponse[]>();

        // Create returns at least the template.
        created.Should().NotBeNullOrEmpty();
        created![0].Title.Should().Be("Standup");
        created[0].RecurrenceRuleId.Should().NotBeNull();

        var start = monday.ToString("yyyy-MM-dd");
        var end   = monday.AddDays(13).ToString("yyyy-MM-dd");

        var ensureRes = await user.Client.PostAsync(
            $"/api/v1/recurrence/ensure-instances?start={start}&end={end}", null);
        ensureRes.StatusCode.Should().Be(System.Net.HttpStatusCode.OK);

        var instances = await ensureRes.Content.ReadFromJsonAsync<RecurrenceTaskResponse[]>();
        instances.Should().NotBeNullOrEmpty();
        // Every element must be a full TaskResponse (has a non-empty id, title, status, rank).
        foreach (var t in instances!)
        {
            t.Id.Should().NotBe(Guid.Empty);
            t.Title.Should().NotBeNullOrEmpty();
            t.Status.Should().NotBeNull();
            t.Rank.Should().NotBeNullOrEmpty();
            t.RecurrenceRuleId.Should().NotBeNull();
            t.IsRecurrenceTemplate.Should().BeFalse();
            t.PlannedDate.Should().NotBeNull();
        }
    }

    [Fact]
    public async Task Create_recurrence_returns_TaskResponse_array_including_template()
    {
        var user = await RegisterAsync();
        var monday = NextFutureMonday();

        var res = await user.Client.PostAsJsonAsync(
            "/api/v1/recurrence", WeeklyMonWedFri(monday.ToString("yyyy-MM-dd")));
        res.StatusCode.Should().Be(System.Net.HttpStatusCode.Created);

        var tasks = await res.Content.ReadFromJsonAsync<RecurrenceTaskResponse[]>();
        tasks.Should().NotBeNullOrEmpty();
        var template = tasks!.SingleOrDefault(t => t.IsRecurrenceTemplate);
        template.Should().NotBeNull();
        template!.Title.Should().Be("Standup");
        template.RecurrenceRuleId.Should().NotBeNull();
    }
}

// Local DTO matching the API shape — avoids a namespace clash with the production TaskResponse.
file sealed record RecurrenceTaskResponse(
    Guid Id,
    string Title,
    Guid? RecurrenceRuleId,
    bool IsRecurrenceTemplate,
    string? PlannedDate,
    string Status,
    string Rank,
    bool RecurrenceDetached,
    string? RecurrenceOriginalDate);
