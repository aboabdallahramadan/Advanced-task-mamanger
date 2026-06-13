using System.Net;
using System.Net.Http.Json;
using FluentAssertions;
using Microsoft.EntityFrameworkCore;
using Tmap.Api.Features.Projects;
using Tmap.Api.Infrastructure.Entities;
using Xunit;
using TaskStatus = Tmap.Api.Infrastructure.Entities.TaskStatus;

namespace Tmap.Api.Tests;

[Collection("db")]
public sealed class ProjectsTests(PostgresFixture fixture) : IntegrationTestBase(fixture)
{
    [Fact]
    public async Task Create_then_get_then_patch_roundtrips()
    {
        var authed = await RegisterAsync();

        var createReq = new CreateProjectRequest(
            Name: "Acme",
            Color: "#ff8800",
            Emoji: "🚀",
            Rank: "a0");
        var createResp = await authed.Client.PostAsJsonAsync("/api/v1/projects", createReq);
        createResp.StatusCode.Should().Be(HttpStatusCode.Created);

        var created = await createResp.Content.ReadFromJsonAsync<ProjectResponse>();
        created.Should().NotBeNull();
        created!.Name.Should().Be("Acme");
        created.Color.Should().Be("#ff8800");
        created.Emoji.Should().Be("🚀");
        created.Rank.Should().Be("a0");
        created.ActualTimeMinutes.Should().Be(0);
        created.Id.Should().NotBe(Guid.Empty);

        var listResp = await authed.Client.GetAsync("/api/v1/projects");
        listResp.StatusCode.Should().Be(HttpStatusCode.OK);
        var list = await listResp.Content.ReadFromJsonAsync<List<ProjectResponse>>();
        list.Should().ContainSingle(p => p.Id == created.Id);

        var patchReq = new UpdateProjectRequest(
            Name: "Acme Renamed",
            Color: "#00aa55",
            Emoji: "🎯",
            Rank: "a1");
        var patchResp = await authed.Client.PatchAsJsonAsync($"/api/v1/projects/{created.Id}", patchReq);
        patchResp.StatusCode.Should().Be(HttpStatusCode.OK);
        var patched = await patchResp.Content.ReadFromJsonAsync<ProjectResponse>();
        patched!.Name.Should().Be("Acme Renamed");
        patched.Color.Should().Be("#00aa55");
        patched.Emoji.Should().Be("🎯");
        patched.Rank.Should().Be("a1");
        patched.Id.Should().Be(created.Id);
    }

    [Fact]
    public async Task Partial_patch_only_name_leaves_color_emoji_rank_unchanged()
    {
        var authed = await RegisterAsync();

        // Create a project with known color/emoji/rank values.
        var createResp = await authed.Client.PostAsJsonAsync(
            "/api/v1/projects",
            new CreateProjectRequest("Original Name", "#ff0000", "🔥", "a0"));
        createResp.StatusCode.Should().Be(HttpStatusCode.Created);
        var created = await createResp.Content.ReadFromJsonAsync<ProjectResponse>();
        created.Should().NotBeNull();

        // PATCH with only Name provided; all other fields are omitted (null).
        var partialPatch = new UpdateProjectRequest(Name: "Updated Name");
        var patchResp = await authed.Client.PatchAsJsonAsync(
            $"/api/v1/projects/{created!.Id}", partialPatch);
        patchResp.StatusCode.Should().Be(HttpStatusCode.OK);

        var patched = await patchResp.Content.ReadFromJsonAsync<ProjectResponse>();
        patched.Should().NotBeNull();
        // Name must be updated.
        patched!.Name.Should().Be("Updated Name");
        // Color, emoji, and rank must remain unchanged.
        patched.Color.Should().Be("#ff0000");
        patched.Emoji.Should().Be("🔥");
        patched.Rank.Should().Be("a0");
        patched.ActualTimeMinutes.Should().Be(0);
        patched.Id.Should().Be(created.Id);
    }

    [Fact]
    public async Task Cross_user_cannot_see_or_mutate_anothers_project()
    {
        var alice = await RegisterAsync();
        var bob = await RegisterAsync();

        var createResp = await alice.Client.PostAsJsonAsync(
            "/api/v1/projects",
            new CreateProjectRequest("Alice Project", "#111111", "🔒", "a0"));
        createResp.StatusCode.Should().Be(HttpStatusCode.Created);
        var aliceProject = await createResp.Content.ReadFromJsonAsync<ProjectResponse>();

        // Bob's list must not include Alice's project.
        var bobList = await bob.Client.GetFromJsonAsync<List<ProjectResponse>>("/api/v1/projects");
        bobList.Should().NotContain(p => p.Id == aliceProject!.Id);

        // Bob cannot patch Alice's project (filtered out → 404, not 403).
        var bobPatch = await bob.Client.PatchAsJsonAsync(
            $"/api/v1/projects/{aliceProject!.Id}",
            new UpdateProjectRequest("Hijacked", "#000000", "💀", "z9"));
        bobPatch.StatusCode.Should().Be(HttpStatusCode.NotFound);

        // Bob cannot delete Alice's project.
        var bobDelete = await bob.Client.DeleteAsync($"/api/v1/projects/{aliceProject.Id}");
        bobDelete.StatusCode.Should().Be(HttpStatusCode.NotFound);

        // Alice still sees her project unchanged.
        var aliceList = await alice.Client.GetFromJsonAsync<List<ProjectResponse>>("/api/v1/projects");
        aliceList.Should().ContainSingle(p => p.Id == aliceProject.Id && p.Name == "Alice Project");
    }

    [Fact]
    public async Task Delete_softdeletes_project_and_nulls_child_project_ids()
    {
        var authed = await RegisterAsync();

        var createResp = await authed.Client.PostAsJsonAsync(
            "/api/v1/projects",
            new CreateProjectRequest("ToDelete", "#abcabc", "🗑️", "a0"));
        var project = await createResp.Content.ReadFromJsonAsync<ProjectResponse>();
        var projectId = project!.Id;

        // Arrange children that reference the project, via the elevated context.
        var noteGroupId = Guid.CreateVersion7();
        var noteId = Guid.CreateVersion7();
        var taskId = Guid.CreateVersion7();
        await using (var arrange = NewElevatedDbContext())
        {
            arrange.NoteGroups.Add(new NoteGroup
            {
                Id = noteGroupId,
                UserId = authed.UserId,
                Name = "G",
                Emoji = "📁",
                ProjectId = projectId,
                Rank = "a0",
            });
            arrange.Notes.Add(new Note
            {
                Id = noteId,
                UserId = authed.UserId,
                GroupId = null,
                ProjectId = projectId,
                Title = "N",
                Content = "body",
                Rank = "a0",
            });
            arrange.Tasks.Add(new TaskItem
            {
                Id = taskId,
                UserId = authed.UserId,
                Title = "T",
                Notes = "",
                ProjectId = projectId,
                Labels = [],
                Source = "manual",
                Status = TaskStatus.Inbox,
                Rank = "a0",
            });
            await arrange.SaveChangesAsync();
        }

        var deleteResp = await authed.Client.DeleteAsync($"/api/v1/projects/{projectId}");
        deleteResp.StatusCode.Should().Be(HttpStatusCode.NoContent);

        // Project is gone from the live list.
        var list = await authed.Client.GetFromJsonAsync<List<ProjectResponse>>("/api/v1/projects");
        list.Should().NotContain(p => p.Id == projectId);

        await using var assertDb = NewElevatedDbContext();

        // Project itself is soft-deleted (tombstoned), not hard-deleted.
        var deletedProject = await assertDb.Projects
            .IgnoreQueryFilters(["SoftDelete"])
            .SingleAsync(p => p.Id == projectId);
        deletedProject.DeletedAt.Should().NotBeNull();

        // Children: project_id set to null, rows themselves still live.
        var group = await assertDb.NoteGroups.SingleAsync(g => g.Id == noteGroupId);
        group.ProjectId.Should().BeNull();
        group.DeletedAt.Should().BeNull();

        var note = await assertDb.Notes.SingleAsync(n => n.Id == noteId);
        note.ProjectId.Should().BeNull();
        note.DeletedAt.Should().BeNull();

        var task = await assertDb.Tasks.SingleAsync(t => t.Id == taskId);
        task.ProjectId.Should().BeNull();
        task.DeletedAt.Should().BeNull();
    }

    [Fact]
    public async Task Reorder_updates_only_specified_rows_ranks()
    {
        var authed = await RegisterAsync();

        var a = await (await authed.Client.PostAsJsonAsync(
            "/api/v1/projects", new CreateProjectRequest("A", "#1", "🅰️", "a0")))
            .Content.ReadFromJsonAsync<ProjectResponse>();
        var b = await (await authed.Client.PostAsJsonAsync(
            "/api/v1/projects", new CreateProjectRequest("B", "#2", "🅱️", "a1")))
            .Content.ReadFromJsonAsync<ProjectResponse>();

        // Move B to rank "a0m" (between nothing and A in lexicographic terms is irrelevant;
        // the contract only requires the single addressed row's rank change).
        var reorderResp = await authed.Client.PatchAsJsonAsync(
            "/api/v1/projects/reorder",
            new[] { new ReorderItem(b!.Id, "Zz") });
        reorderResp.StatusCode.Should().Be(HttpStatusCode.NoContent);

        var list = await authed.Client.GetFromJsonAsync<List<ProjectResponse>>("/api/v1/projects");
        list!.Single(p => p.Id == b.Id).Rank.Should().Be("Zz");
        // A is untouched.
        list.Single(p => p.Id == a!.Id).Rank.Should().Be("a0");
        // Ordering reflects new ranks: A ("a0") before B ("Zz")? No — "Zz" < "a0" lexicographically,
        // so B sorts first. Verify ordering by rank.
        list.Select(p => p.Rank).Should().BeInAscendingOrder();
    }

    [Fact]
    public async Task Reorder_bumps_change_seq_via_db_trigger_on_reordered_row_only()
    {
        // The reorder endpoint uses ExecuteUpdate, which bypasses the SaveChanges interceptor,
        // so only the BEFORE UPDATE DB trigger can advance change_seq. Prove the HTTP path fires it.
        var authed = await RegisterAsync();

        var a = await (await authed.Client.PostAsJsonAsync(
            "/api/v1/projects", new CreateProjectRequest("A", "#1", "🅰️", "a0")))
            .Content.ReadFromJsonAsync<ProjectResponse>();
        var b = await (await authed.Client.PostAsJsonAsync(
            "/api/v1/projects", new CreateProjectRequest("B", "#2", "🅱️", "a1")))
            .Content.ReadFromJsonAsync<ProjectResponse>();

        long aSeqBefore, bSeqBefore;
        await using (var db = NewElevatedDbContext())
        {
            aSeqBefore = await db.Projects.Where(p => p.Id == a!.Id).Select(p => p.ChangeSeq).SingleAsync();
            bSeqBefore = await db.Projects.Where(p => p.Id == b!.Id).Select(p => p.ChangeSeq).SingleAsync();
        }

        var reorderResp = await authed.Client.PatchAsJsonAsync(
            "/api/v1/projects/reorder",
            new[] { new ReorderItem(b!.Id, "Zz") });
        reorderResp.StatusCode.Should().Be(HttpStatusCode.NoContent);

        await using var assertDb = NewElevatedDbContext();
        var aSeqAfter = await assertDb.Projects.Where(p => p.Id == a!.Id).Select(p => p.ChangeSeq).SingleAsync();
        var bSeqAfter = await assertDb.Projects.Where(p => p.Id == b.Id).Select(p => p.ChangeSeq).SingleAsync();

        bSeqAfter.Should().BeGreaterThan(bSeqBefore,
            "the reorder ExecuteUpdate must trigger the DB change_seq bump on the reordered row");
        aSeqAfter.Should().Be(aSeqBefore, "an untouched row's change_seq must not move");
    }

    [Fact]
    public async Task Create_SameClientId_Twice_Returns200_SameId_NoDuplicate()
    {
        var user = await RegisterAsync();
        var id = Guid.CreateVersion7();
        var body = new CreateProjectRequest("Replayed", "#111111", "📁", "a0", id);

        var first = await user.Client.PostAsJsonAsync("/api/v1/projects", body);
        first.StatusCode.Should().Be(HttpStatusCode.Created);

        var second = await user.Client.PostAsJsonAsync("/api/v1/projects", body);
        second.StatusCode.Should().Be(HttpStatusCode.OK);
        var dto = await second.Content.ReadFromJsonAsync<ProjectResponse>();
        dto!.Id.Should().Be(id);

        var list = await user.Client.GetFromJsonAsync<List<ProjectResponse>>("/api/v1/projects");
        list!.Count(p => p.Id == id).Should().Be(1);
    }

    [Fact]
    public async Task Create_DuplicateName_Returns409_WithExistingId()
    {
        var user = await RegisterAsync();
        var first = await (await user.Client.PostAsJsonAsync("/api/v1/projects",
            new CreateProjectRequest("Dupe", "#111111", "📁", "a0")))
            .Content.ReadFromJsonAsync<ProjectResponse>();

        // Different client id, same name → 409 carrying the live project's id.
        var resp = await user.Client.PostAsJsonAsync("/api/v1/projects",
            new CreateProjectRequest("Dupe", "#222222", "📁", "a1"));
        resp.StatusCode.Should().Be(HttpStatusCode.Conflict);

        using var doc = System.Text.Json.JsonDocument.Parse(await resp.Content.ReadAsStringAsync());
        doc.RootElement.GetProperty("existingId").GetGuid().Should().Be(first!.Id);
    }

    [Fact]
    public async Task Rename_ToExistingName_Returns409_WithExistingId()
    {
        var user = await RegisterAsync();
        var a = await (await user.Client.PostAsJsonAsync("/api/v1/projects",
            new CreateProjectRequest("Alpha", "#111111", "📁", "a0")))
            .Content.ReadFromJsonAsync<ProjectResponse>();
        var b = await (await user.Client.PostAsJsonAsync("/api/v1/projects",
            new CreateProjectRequest("Beta", "#222222", "📁", "a1")))
            .Content.ReadFromJsonAsync<ProjectResponse>();

        // Rename B → "Alpha" collides with A.
        var resp = await user.Client.PatchAsJsonAsync($"/api/v1/projects/{b!.Id}",
            new UpdateProjectRequest(Name: "Alpha"));
        resp.StatusCode.Should().Be(HttpStatusCode.Conflict);

        using var doc = System.Text.Json.JsonDocument.Parse(await resp.Content.ReadAsStringAsync());
        doc.RootElement.GetProperty("existingId").GetGuid().Should().Be(a!.Id);
    }

    [Fact]
    public async Task Create_DuplicateName_DoesNotCollideAcrossUsers()
    {
        var a = await RegisterAsync();
        var b = await RegisterAsync();
        await a.Client.PostAsJsonAsync("/api/v1/projects",
            new CreateProjectRequest("Shared", "#111111", "📁", "a0"));
        // B can use the same name — uniqueness is per-user.
        var bResp = await b.Client.PostAsJsonAsync("/api/v1/projects",
            new CreateProjectRequest("Shared", "#222222", "📁", "a0"));
        bResp.StatusCode.Should().Be(HttpStatusCode.Created);
    }

    [Fact]
    public async Task List_TiedRanks_AreOrderedById_Stably()
    {
        var user = await RegisterAsync();
        var idLo = new Guid("00000000-0000-0000-0000-000000000011");
        var idHi = new Guid("00000000-0000-0000-0000-000000000012");
        await user.Client.PostAsJsonAsync("/api/v1/projects",
            new CreateProjectRequest("Hi", "#111111", "📁", "a0", idHi));
        await user.Client.PostAsJsonAsync("/api/v1/projects",
            new CreateProjectRequest("Lo", "#222222", "📁", "a0", idLo));

        var list = await user.Client.GetFromJsonAsync<List<ProjectResponse>>("/api/v1/projects");
        var tied = list!.Where(p => p.Id == idLo || p.Id == idHi).Select(p => p.Id).ToList();
        tied.Should().Equal(idLo, idHi);
    }
}
