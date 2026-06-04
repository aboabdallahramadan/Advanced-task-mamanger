using System.Net;
using System.Net.Http.Json;
using FluentAssertions;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using Tmap.Api.Features.Tasks;
using Xunit;

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
}
