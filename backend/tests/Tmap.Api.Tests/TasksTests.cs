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
    public async Task Create_stamps_created_at_to_now_via_trigger()
    {
        var auth = await RegisterAsync();

        var before = DateTimeOffset.UtcNow.AddSeconds(-5);
        var resp = await auth.Client.PostAsJsonAsync("/api/v1/tasks", new
        {
            title = "Stamp me",
            status = "Inbox",
        });
        resp.StatusCode.Should().Be(HttpStatusCode.Created);
        var body = await resp.Content.ReadFromJsonAsync<TaskResponse>();
        body.Should().NotBeNull();
        var after = DateTimeOffset.UtcNow.AddSeconds(5);

        // The bump_change_seq trigger must populate created_at on INSERT — not leave the
        // 0001-01-01 CLR default. Read it back via an elevated context (not the API response).
        await using var db = NewElevatedDbContext();
        var row = await db.Tasks.SingleAsync(t => t.Id == body!.Id);
        row.CreatedAt.Should().BeAfter(DateTimeOffset.UnixEpoch);
        row.CreatedAt.Should().BeOnOrAfter(before).And.BeOnOrBefore(after);
        row.CreatedAt.Should().BeCloseTo(DateTimeOffset.UtcNow, TimeSpan.FromMinutes(1));
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

    [Fact]
    public async Task Patch_with_explicit_null_clears_scheduledStart_but_omitting_it_leaves_value()
    {
        var auth = await RegisterAsync();
        var created = await (await auth.Client.PostAsJsonAsync("/api/v1/tasks",
            new
            {
                title = "Scheduled",
                status = "Scheduled",
                scheduledStart = "2026-06-10T09:00:00+00:00",
                scheduledEnd = "2026-06-10T10:00:00+00:00",
            }))
            .Content.ReadFromJsonAsync<TaskResponse>();
        created!.ScheduledStart.Should().NotBeNull();

        // (1) Explicit null in the body CLEARS the field.
        var clearResp = await auth.Client.PatchAsJsonAsync($"/api/v1/tasks/{created.Id}",
            new { scheduledStart = (DateTimeOffset?)null });
        clearResp.StatusCode.Should().Be(HttpStatusCode.OK);
        var cleared = await clearResp.Content.ReadFromJsonAsync<TaskResponse>();
        cleared!.ScheduledStart.Should().BeNull("explicit null must clear scheduledStart");
        cleared.ScheduledEnd.Should().NotBeNull("an absent field must be left unchanged");

        // The clear persists across a fresh read (the original bug: stale value reappeared on getAll).
        await using (var db = NewElevatedDbContext())
        {
            var row = await db.Tasks.SingleAsync(t => t.Id == created.Id);
            row.ScheduledStart.Should().BeNull();
            row.ScheduledEnd.Should().NotBeNull();
        }

        // (2) Set a value again, then a PATCH that OMITS scheduledStart must leave it intact.
        var setResp = await auth.Client.PatchAsJsonAsync($"/api/v1/tasks/{created.Id}",
            new { scheduledStart = "2026-06-11T08:00:00+00:00" });
        setResp.StatusCode.Should().Be(HttpStatusCode.OK);
        (await setResp.Content.ReadFromJsonAsync<TaskResponse>())!.ScheduledStart.Should().NotBeNull();

        var unrelatedPatch = await auth.Client.PatchAsJsonAsync($"/api/v1/tasks/{created.Id}",
            new { title = "Renamed only" });
        unrelatedPatch.StatusCode.Should().Be(HttpStatusCode.OK);
        var afterUnrelated = await unrelatedPatch.Content.ReadFromJsonAsync<TaskResponse>();
        afterUnrelated!.Title.Should().Be("Renamed only");
        afterUnrelated.ScheduledStart.Should().NotBeNull(
            "omitting scheduledStart must leave the existing value untouched");
    }

    [Fact]
    public async Task Patch_with_explicit_null_clears_projectId_priority_and_plannedDate()
    {
        var auth = await RegisterAsync();

        var projectId = Guid.CreateVersion7();
        await using (var arrange = NewElevatedDbContext())
        {
            arrange.Projects.Add(new Project
            {
                Id = projectId,
                UserId = auth.UserId,
                Name = "P",
                Color = "#1",
                Emoji = "📁",
                Rank = "a0",
                ActualTimeMinutes = 0,
            });
            await arrange.SaveChangesAsync();
        }

        var created = await (await auth.Client.PostAsJsonAsync("/api/v1/tasks",
            new
            {
                title = "Full",
                status = "Planned",
                projectId,
                priority = 2,
                reminderMinutes = 15,
                plannedDate = "2026-06-10",
            }))
            .Content.ReadFromJsonAsync<TaskResponse>();
        created!.ProjectId.Should().Be(projectId);
        created.Priority.Should().Be(2);

        var resp = await auth.Client.PatchAsJsonAsync($"/api/v1/tasks/{created.Id}", new
        {
            projectId = (Guid?)null,
            priority = (int?)null,
            reminderMinutes = (int?)null,
            plannedDate = (DateOnly?)null,
        });
        resp.StatusCode.Should().Be(HttpStatusCode.OK);

        await using var db = NewElevatedDbContext();
        var row = await db.Tasks.SingleAsync(t => t.Id == created.Id);
        row.ProjectId.Should().BeNull("explicit null must clear projectId (move-to-backlog/detach)");
        row.Priority.Should().BeNull();
        row.ReminderMinutes.Should().BeNull();
        row.PlannedDate.Should().BeNull();
    }

    [Fact]
    public async Task Reorder_updates_rank_on_targeted_rows_only()
    {
        var auth = await RegisterAsync();
        var a = await (await auth.Client.PostAsJsonAsync("/api/v1/tasks",
            new { title = "A", status = "Inbox" })).Content.ReadFromJsonAsync<TaskResponse>();
        var b = await (await auth.Client.PostAsJsonAsync("/api/v1/tasks",
            new { title = "B", status = "Inbox" })).Content.ReadFromJsonAsync<TaskResponse>();

        var resp = await auth.Client.PatchAsJsonAsync("/api/v1/tasks/reorder",
            new[] { new { id = a!.Id, rank = "zzz" } });
        resp.StatusCode.Should().Be(HttpStatusCode.NoContent);

        await using var db = NewElevatedDbContext();
        var aRow = await db.Tasks.SingleAsync(t => t.Id == a.Id);
        var bRow = await db.Tasks.SingleAsync(t => t.Id == b!.Id);
        aRow.Rank.Should().Be("zzz");
        bRow.Rank.Should().Be(b!.Rank);   // unchanged
    }

    [Fact]
    public async Task Reorder_bumps_change_seq_via_db_trigger_on_reordered_row_only()
    {
        // The reorder endpoint uses ExecuteUpdate, which bypasses the SaveChanges interceptor,
        // so only the BEFORE UPDATE DB trigger can advance change_seq. Prove the HTTP path fires it.
        var auth = await RegisterAsync();
        var a = await (await auth.Client.PostAsJsonAsync("/api/v1/tasks",
            new { title = "A", status = "Inbox" })).Content.ReadFromJsonAsync<TaskResponse>();
        var b = await (await auth.Client.PostAsJsonAsync("/api/v1/tasks",
            new { title = "B", status = "Inbox" })).Content.ReadFromJsonAsync<TaskResponse>();

        long aSeqBefore, bSeqBefore;
        await using (var db = NewElevatedDbContext())
        {
            aSeqBefore = await db.Tasks.Where(t => t.Id == a!.Id).Select(t => t.ChangeSeq).SingleAsync();
            bSeqBefore = await db.Tasks.Where(t => t.Id == b!.Id).Select(t => t.ChangeSeq).SingleAsync();
        }

        var resp = await auth.Client.PatchAsJsonAsync("/api/v1/tasks/reorder",
            new[] { new { id = a!.Id, rank = "zzz" } });
        resp.StatusCode.Should().Be(HttpStatusCode.NoContent);

        await using var assertDb = NewElevatedDbContext();
        var aSeqAfter = await assertDb.Tasks.Where(t => t.Id == a.Id).Select(t => t.ChangeSeq).SingleAsync();
        var bSeqAfter = await assertDb.Tasks.Where(t => t.Id == b!.Id).Select(t => t.ChangeSeq).SingleAsync();

        aSeqAfter.Should().BeGreaterThan(aSeqBefore,
            "the reorder ExecuteUpdate must trigger the DB change_seq bump on the reordered row");
        bSeqAfter.Should().Be(bSeqBefore, "an untouched row's change_seq must not move");
    }

    [Fact]
    public async Task Delete_soft_deletes_task_and_tombstones_subtasks()
    {
        var auth = await RegisterAsync();
        var task = await (await auth.Client.PostAsJsonAsync("/api/v1/tasks",
            new { title = "Parent", status = "Inbox" }))
            .Content.ReadFromJsonAsync<TaskResponse>();

        // Arrange subtask directly via elevated DbContext (subtask POST not yet implemented)
        await using var arrangeDb = NewElevatedDbContext(auth.UserId);
        arrangeDb.Subtasks.Add(new Tmap.Api.Infrastructure.Entities.Subtask
        {
            Id = Guid.CreateVersion7(),
            UserId = auth.UserId,
            TaskId = task!.Id,
            Title = "child",
            SortOrder = 1,
        });
        await arrangeDb.SaveChangesAsync();

        var del = await auth.Client.DeleteAsync($"/api/v1/tasks/{task.Id}");
        del.StatusCode.Should().Be(HttpStatusCode.NoContent);

        // gone from the normal read path
        var afterGet = await auth.Client.GetAsync($"/api/v1/tasks");
        var live = await afterGet.Content.ReadFromJsonAsync<List<TaskResponse>>();
        live!.Should().NotContain(t => t.Id == task.Id);

        // tombstones exist in the DB (SoftDelete filter ignored)
        await using var db = NewElevatedDbContext();
        var taskRow = await db.Tasks.IgnoreQueryFilters(["SoftDelete"])
            .SingleAsync(t => t.Id == task.Id);
        taskRow.DeletedAt.Should().NotBeNull();

        var childRows = await db.Subtasks.IgnoreQueryFilters(["SoftDelete"])
            .Where(s => s.TaskId == task.Id).ToListAsync();
        childRows.Should().NotBeEmpty();
        childRows.Should().OnlyContain(s => s.DeletedAt != null);
    }

    [Fact]
    public async Task Create_with_another_users_projectId_returns_400_and_does_not_persist()
    {
        var owner = await RegisterAsync();
        var intruder = await RegisterAsync();

        // Owner's project: only the owner may attach rows to it.
        var ownerProjectId = Guid.CreateVersion7();
        await using (var arrange = NewElevatedDbContext())
        {
            arrange.Projects.Add(new Project
            {
                Id = ownerProjectId,
                UserId = owner.UserId,
                Name = "Owner P",
                Color = "#1",
                Emoji = "📁",
                Rank = "a0",
                ActualTimeMinutes = 0,
            });
            await arrange.SaveChangesAsync();
        }

        var resp = await intruder.Client.PostAsJsonAsync("/api/v1/tasks", new
        {
            title = "smuggled",
            status = "Inbox",
            projectId = ownerProjectId,
        });

        resp.StatusCode.Should().Be(HttpStatusCode.BadRequest);
        resp.Content.Headers.ContentType?.MediaType.Should().Be("application/problem+json");
        var problem = await resp.Content.ReadFromJsonAsync<ValidationProblemDetails>();
        problem!.Errors.Should().ContainKey("projectId");

        // Nothing persisted for the intruder.
        await using var db = NewElevatedDbContext(intruder.UserId);
        var any = await db.Tasks.IgnoreQueryFilters(["SoftDelete"]).AnyAsync(t => t.Title == "smuggled");
        any.Should().BeFalse();
    }

    [Fact]
    public async Task Patch_with_another_users_projectId_returns_400_and_does_not_persist()
    {
        var owner = await RegisterAsync();
        var intruder = await RegisterAsync();

        var ownerProjectId = Guid.CreateVersion7();
        await using (var arrange = NewElevatedDbContext())
        {
            arrange.Projects.Add(new Project
            {
                Id = ownerProjectId,
                UserId = owner.UserId,
                Name = "Owner P",
                Color = "#1",
                Emoji = "📁",
                Rank = "a0",
                ActualTimeMinutes = 0,
            });
            await arrange.SaveChangesAsync();
        }

        var task = await (await intruder.Client.PostAsJsonAsync("/api/v1/tasks",
            new { title = "mine", status = "Inbox" }))
            .Content.ReadFromJsonAsync<TaskResponse>();

        var resp = await intruder.Client.PatchAsJsonAsync($"/api/v1/tasks/{task!.Id}",
            new { projectId = ownerProjectId });

        resp.StatusCode.Should().Be(HttpStatusCode.BadRequest);
        var problem = await resp.Content.ReadFromJsonAsync<ValidationProblemDetails>();
        problem!.Errors.Should().ContainKey("projectId");

        // The task's project assignment was NOT changed.
        await using var db = NewElevatedDbContext(intruder.UserId);
        var row = await db.Tasks.SingleAsync(t => t.Id == task.Id);
        row.ProjectId.Should().BeNull();
    }

    [Fact]
    public async Task Create_and_patch_with_own_projectId_succeeds()
    {
        var owner = await RegisterAsync();

        var ownProjectId = Guid.CreateVersion7();
        await using (var arrange = NewElevatedDbContext())
        {
            arrange.Projects.Add(new Project
            {
                Id = ownProjectId,
                UserId = owner.UserId,
                Name = "Own P",
                Color = "#1",
                Emoji = "📁",
                Rank = "a0",
                ActualTimeMinutes = 0,
            });
            await arrange.SaveChangesAsync();
        }

        var createResp = await owner.Client.PostAsJsonAsync("/api/v1/tasks", new
        {
            title = "linked",
            status = "Inbox",
            projectId = ownProjectId,
        });
        createResp.StatusCode.Should().Be(HttpStatusCode.Created);
        var created = await createResp.Content.ReadFromJsonAsync<TaskResponse>();
        created!.ProjectId.Should().Be(ownProjectId);

        // Detach then re-link via PATCH to exercise the update path too.
        var patchResp = await owner.Client.PatchAsJsonAsync($"/api/v1/tasks/{created.Id}",
            new { projectId = ownProjectId });
        patchResp.StatusCode.Should().Be(HttpStatusCode.OK);
        var patched = await patchResp.Content.ReadFromJsonAsync<TaskResponse>();
        patched!.ProjectId.Should().Be(ownProjectId);
    }

    [Fact]
    public async Task Cross_user_cannot_read_update_or_delete_anothers_task_returns_404()
    {
        var owner = await RegisterAsync("owner@example.com");
        var intruder = await RegisterAsync("intruder@example.com");

        var task = await (await owner.Client.PostAsJsonAsync("/api/v1/tasks",
            new { title = "secret", status = "Inbox" }))
            .Content.ReadFromJsonAsync<TaskResponse>();

        // intruder's list must not contain it
        var intruderList = await (await intruder.Client.GetAsync("/api/v1/tasks"))
            .Content.ReadFromJsonAsync<List<TaskResponse>>();
        intruderList!.Should().NotContain(t => t.Id == task!.Id);

        // update → 404
        var patch = await intruder.Client.PatchAsJsonAsync($"/api/v1/tasks/{task!.Id}",
            new { title = "hijack" });
        patch.StatusCode.Should().Be(HttpStatusCode.NotFound);

        // delete → 404
        var del = await intruder.Client.DeleteAsync($"/api/v1/tasks/{task.Id}");
        del.StatusCode.Should().Be(HttpStatusCode.NotFound);

        // owner's row is intact & not tombstoned
        await using var db = NewElevatedDbContext();
        var row = await db.Tasks.IgnoreQueryFilters(["SoftDelete", "Tenant"])
            .SingleAsync(t => t.Id == task.Id);
        row.DeletedAt.Should().BeNull();
        row.Title.Should().Be("secret");
    }

    [Fact]
    public async Task Create_SameClientId_Twice_Returns200_SameId_NoDuplicate()
    {
        var user = await RegisterAsync();
        var id = Guid.CreateVersion7();
        var body = new { id, title = "Replayed", rank = "a0" };

        var first = await user.Client.PostAsJsonAsync("/api/v1/tasks", body);
        first.StatusCode.Should().Be(HttpStatusCode.Created);

        var second = await user.Client.PostAsJsonAsync("/api/v1/tasks", body);
        second.StatusCode.Should().Be(HttpStatusCode.OK, "replaying a create is idempotent");
        var dto = await second.Content.ReadFromJsonAsync<TaskResponse>();
        dto!.Id.Should().Be(id);

        var list = await user.Client.GetFromJsonAsync<List<TaskResponse>>("/api/v1/tasks");
        list!.Count(t => t.Id == id).Should().Be(1, "no duplicate row");
    }

    [Fact]
    public async Task List_TiedRanks_AreOrderedById_Stably()
    {
        var user = await RegisterAsync();
        // Two ids in known order; both rank "a0" (the tie case).
        var idLo = new Guid("00000000-0000-0000-0000-000000000001");
        var idHi = new Guid("00000000-0000-0000-0000-000000000002");
        // Insert hi first to prove order comes from id, not insertion.
        await user.Client.PostAsJsonAsync("/api/v1/tasks", new { id = idHi, title = "Hi", rank = "a0" });
        await user.Client.PostAsJsonAsync("/api/v1/tasks", new { id = idLo, title = "Lo", rank = "a0" });

        var list1 = await user.Client.GetFromJsonAsync<List<TaskResponse>>("/api/v1/tasks");
        var tied1 = list1!.Where(t => t.Id == idLo || t.Id == idHi).Select(t => t.Id).ToList();
        tied1.Should().Equal(new[] { idLo, idHi }, "tied ranks order by id ascending");

        // Stable across a repeated read.
        var list2 = await user.Client.GetFromJsonAsync<List<TaskResponse>>("/api/v1/tasks");
        var tied2 = list2!.Where(t => t.Id == idLo || t.Id == idHi).Select(t => t.Id).ToList();
        tied2.Should().Equal(tied1, "ordering is stable across list reads");
    }
}
