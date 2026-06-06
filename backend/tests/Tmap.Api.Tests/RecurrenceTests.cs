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

    [Fact]
    public async Task GetRule_ReturnsCreatedRule()
    {
        var user = await RegisterAsync();
        var created = await (await user.Client.PostAsJsonAsync("/api/v1/recurrence", WeeklyMonWedFri()))
            .Content.ReadFromJsonAsync<RecurringTaskResponse>();

        var ruleId = created!.RecurrenceRuleId;
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
        var created = await (await user.Client.PostAsJsonAsync("/api/v1/recurrence", WeeklyMonWedFri()))
            .Content.ReadFromJsonAsync<RecurringTaskResponse>();
        var ruleId = created!.RecurrenceRuleId;

        // Ensure some future instances exist.
        await user.Client.PostAsync("/api/v1/recurrence/ensure-instances?start=2026-06-01&end=2026-06-30", null);

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
            .Content.ReadFromJsonAsync<RecurringTaskResponse>();

        var resp = await b.Client.GetAsync($"/api/v1/recurrence/rules/{created!.RecurrenceRuleId}");
        resp.StatusCode.Should().Be(HttpStatusCode.NotFound); // tenant filter hides it
    }
}
