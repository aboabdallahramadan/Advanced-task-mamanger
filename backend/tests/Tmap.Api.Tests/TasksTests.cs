using System.Net;
using System.Net.Http.Json;
using FluentAssertions;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using Tmap.Api.Features.Tasks;
using Tmap.Api.Infrastructure.Entities;
using Xunit;
using TaskStatus = Tmap.Api.Infrastructure.Entities.TaskStatus;

namespace Tmap.Api.Tests;

[Collection("db")]
public sealed class TasksTests(PostgresFixture fixture) : IntegrationTestBase(fixture)
{
    [Fact]
    public async Task Unmapped_route_returns_problem_details_content_type()
    {
        var auth = await RegisterAsync();
        var resp = await auth.Client.GetAsync("/api/v1/tasks/does-not-exist-route/extra");

        resp.StatusCode.Should().Be(HttpStatusCode.NotFound);
        resp.Content.Headers.ContentType?.MediaType.Should().Be("application/problem+json");
    }

    [Fact]
    public async Task Create_with_empty_title_returns_validation_problem_400()
    {
        var auth = await RegisterAsync();
        var resp = await auth.Client.PostAsJsonAsync("/api/v1/tasks", new { title = "" });

        resp.StatusCode.Should().Be(HttpStatusCode.BadRequest);
        resp.Content.Headers.ContentType?.MediaType.Should().Be("application/problem+json");

        var problem = await resp.Content.ReadFromJsonAsync<ValidationProblemDetails>();
        problem.Should().NotBeNull();
        problem!.Errors.Should().ContainKey("Title");
    }

    [Fact]
    public async Task Create_returns_201_and_persists_row_scoped_to_user()
    {
        var auth = await RegisterAsync();

        var resp = await auth.Client.PostAsJsonAsync("/api/v1/tasks", new
        {
            title = "Write the plan",
            notes = "first draft",
            status = "Inbox",
        });

        resp.StatusCode.Should().Be(HttpStatusCode.Created);
        resp.Headers.Location.Should().NotBeNull();

        var body = await resp.Content.ReadFromJsonAsync<TaskResponse>();
        body.Should().NotBeNull();
        body!.Title.Should().Be("Write the plan");
        body.Subtasks.Should().BeEmpty();
        body.ChangeSeq.Should().BeGreaterThan(0);
        body.Rank.Should().NotBeNullOrEmpty();

        await using var db = NewElevatedDbContext();
        var row = await db.Tasks.SingleAsync(t => t.Id == body.Id);
        row.UserId.Should().Be(auth.UserId);
        row.Title.Should().Be("Write the plan");
        row.DeletedAt.Should().BeNull();
    }

    [Fact]
    public async Task GetAll_returns_only_callers_live_tasks()
    {
        var auth = await RegisterAsync();
        await auth.Client.PostAsJsonAsync("/api/v1/tasks", new { title = "A", status = "Inbox" });
        await auth.Client.PostAsJsonAsync("/api/v1/tasks", new { title = "B", status = "Backlog" });

        var resp = await auth.Client.GetAsync("/api/v1/tasks");
        resp.StatusCode.Should().Be(HttpStatusCode.OK);

        var tasks = await resp.Content.ReadFromJsonAsync<List<TaskResponse>>();
        tasks.Should().NotBeNull();
        tasks!.Select(t => t.Title).Should().BeEquivalentTo(["A", "B"]);
    }

    [Fact]
    public async Task GetAll_filters_by_status_date_and_query()
    {
        var auth = await RegisterAsync();
        await auth.Client.PostAsJsonAsync("/api/v1/tasks",
            new { title = "Inbox alpha", status = "Inbox" });
        await auth.Client.PostAsJsonAsync("/api/v1/tasks",
            new { title = "Planned beta", status = "Planned", plannedDate = "2026-06-02" });
        await auth.Client.PostAsJsonAsync("/api/v1/tasks",
            new { title = "Planned gamma", status = "Planned", plannedDate = "2026-06-03" });

        // status filter
        var byStatus = await (await auth.Client.GetAsync("/api/v1/tasks?status=Planned"))
            .Content.ReadFromJsonAsync<List<TaskResponse>>();
        byStatus!.Select(t => t.Title).Should().BeEquivalentTo(["Planned beta", "Planned gamma"]);

        // date filter
        var byDate = await (await auth.Client.GetAsync("/api/v1/tasks?date=2026-06-02"))
            .Content.ReadFromJsonAsync<List<TaskResponse>>();
        byDate!.Select(t => t.Title).Should().BeEquivalentTo(["Planned beta"]);

        // q filter (case-insensitive)
        var byQuery = await (await auth.Client.GetAsync("/api/v1/tasks?q=alpha"))
            .Content.ReadFromJsonAsync<List<TaskResponse>>();
        byQuery!.Select(t => t.Title).Should().BeEquivalentTo(["Inbox alpha"]);

        // combined status + date
        var combined = await (await auth.Client.GetAsync("/api/v1/tasks?status=Planned&date=2026-06-03"))
            .Content.ReadFromJsonAsync<List<TaskResponse>>();
        combined!.Select(t => t.Title).Should().BeEquivalentTo(["Planned gamma"]);
    }

    [Fact]
    public async Task Patch_updates_only_supplied_fields_and_advances_change_seq()
    {
        var auth = await RegisterAsync();
        var created = await (await auth.Client.PostAsJsonAsync("/api/v1/tasks",
            new { title = "Original", notes = "keep me", status = "Inbox" }))
            .Content.ReadFromJsonAsync<TaskResponse>();

        var resp = await auth.Client.PatchAsJsonAsync($"/api/v1/tasks/{created!.Id}",
            new { title = "Renamed", status = "Done" });

        resp.StatusCode.Should().Be(HttpStatusCode.OK);
        var updated = await resp.Content.ReadFromJsonAsync<TaskResponse>();
        updated!.Title.Should().Be("Renamed");
        updated.Status.Should().Be(TaskStatus.Done);
        updated.Notes.Should().Be("keep me");          // untouched field preserved
        updated.ChangeSeq.Should().BeGreaterThan(created.ChangeSeq);
    }
}
