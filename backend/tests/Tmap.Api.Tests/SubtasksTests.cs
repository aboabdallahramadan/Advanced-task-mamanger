using System.Net;
using System.Net.Http.Json;
using FluentAssertions;
using Microsoft.EntityFrameworkCore;
using Tmap.Api.Features.Subtasks;
using Tmap.Api.Features.Tasks;
using Xunit;

namespace Tmap.Api.Tests;

[Collection("db")]
public sealed class SubtasksTests(PostgresFixture fixture) : IntegrationTestBase(fixture)
{
    private async Task<TaskResponse> CreateTaskAsync(AuthedClient auth, string title = "Parent") =>
        (await (await auth.Client.PostAsJsonAsync("/api/v1/tasks", new { title, status = "Inbox" }))
            .Content.ReadFromJsonAsync<TaskResponse>())!;

    [Fact]
    public async Task Create_subtask_returns_201_and_embeds_in_parent_task_on_read()
    {
        var auth = await RegisterAsync();
        var task = await CreateTaskAsync(auth);

        var resp = await auth.Client.PostAsJsonAsync(
            $"/api/v1/tasks/{task.Id}/subtasks", new { title = "step one" });

        resp.StatusCode.Should().Be(HttpStatusCode.Created);
        var sub = await resp.Content.ReadFromJsonAsync<SubtaskResponse>();
        sub!.Title.Should().Be("step one");
        sub.TaskId.Should().Be(task.Id);

        // embedded on read
        var reread = await (await auth.Client.GetAsync("/api/v1/tasks"))
            .Content.ReadFromJsonAsync<List<TaskResponse>>();
        var parent = reread!.Single(t => t.Id == task.Id);
        parent.Subtasks.Should().ContainSingle(s => s.Id == sub.Id && s.Title == "step one");

        await using var db = NewElevatedDbContext();
        var row = await db.Subtasks.SingleAsync(s => s.Id == sub.Id);
        row.UserId.Should().Be(auth.UserId);
        row.TaskId.Should().Be(task.Id);
    }
}
