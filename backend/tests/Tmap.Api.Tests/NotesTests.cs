using System.Net;
using System.Net.Http.Json;
using FluentAssertions;
using Microsoft.EntityFrameworkCore;
using Tmap.Api.Features.Notes;
using Tmap.Api.Infrastructure.Entities;
using Xunit;

namespace Tmap.Api.Tests;

[Collection("db")]
public sealed class NotesTests(PostgresFixture fixture) : IntegrationTestBase(fixture)
{
    [Fact]
    public async Task Create_getById_list_patch_roundtrips()
    {
        var authed = await RegisterAsync();

        var createResp = await authed.Client.PostAsJsonAsync(
            "/api/v1/notes",
            new CreateNoteRequest(GroupId: null, ProjectId: null, Title: "First", Content: "hello", Rank: "a0"));
        createResp.StatusCode.Should().Be(HttpStatusCode.Created);
        var created = await createResp.Content.ReadFromJsonAsync<NoteResponse>();
        created!.Title.Should().Be("First");
        created.Content.Should().Be("hello");
        created.GroupId.Should().BeNull();
        created.ProjectId.Should().BeNull();
        created.Rank.Should().Be("a0");
        created.Id.Should().NotBe(Guid.Empty);

        var getResp = await authed.Client.GetAsync($"/api/v1/notes/{created.Id}");
        getResp.StatusCode.Should().Be(HttpStatusCode.OK);
        var fetched = await getResp.Content.ReadFromJsonAsync<NoteResponse>();
        fetched!.Id.Should().Be(created.Id);
        fetched.Content.Should().Be("hello");

        var list = await authed.Client.GetFromJsonAsync<List<NoteResponse>>("/api/v1/notes");
        list.Should().ContainSingle(n => n.Id == created.Id);

        var patchResp = await authed.Client.PatchAsJsonAsync(
            $"/api/v1/notes/{created.Id}",
            new UpdateNoteRequest(GroupId: null, ProjectId: null, Title: "First Edited", Content: "world", Rank: "a1"));
        patchResp.StatusCode.Should().Be(HttpStatusCode.OK);
        var patched = await patchResp.Content.ReadFromJsonAsync<NoteResponse>();
        patched!.Title.Should().Be("First Edited");
        patched.Content.Should().Be("world");
        patched.Rank.Should().Be("a1");
        patched.Id.Should().Be(created.Id);
    }

    [Fact]
    public async Task GetAll_filters_by_groupId_and_projectId()
    {
        var authed = await RegisterAsync();
        var groupId = Guid.CreateVersion7();
        var projectId = Guid.CreateVersion7();

        await using (var arrange = NewElevatedDbContext())
        {
            arrange.Projects.Add(new Project { Id = projectId, UserId = authed.UserId, Name = "P", Color = "#1", Emoji = "📁", Rank = "a0", ActualTimeMinutes = 0 });
            arrange.NoteGroups.Add(new NoteGroup { Id = groupId, UserId = authed.UserId, Name = "G", Emoji = "📁", ProjectId = null, Rank = "a0" });
            await arrange.SaveChangesAsync();
        }

        var inGroup = await (await authed.Client.PostAsJsonAsync("/api/v1/notes",
            new CreateNoteRequest(groupId, null, "in-group", "x", "a0")))
            .Content.ReadFromJsonAsync<NoteResponse>();
        var inProject = await (await authed.Client.PostAsJsonAsync("/api/v1/notes",
            new CreateNoteRequest(null, projectId, "in-project", "y", "a0")))
            .Content.ReadFromJsonAsync<NoteResponse>();
        var loose = await (await authed.Client.PostAsJsonAsync("/api/v1/notes",
            new CreateNoteRequest(null, null, "loose", "z", "a0")))
            .Content.ReadFromJsonAsync<NoteResponse>();

        var byGroup = await authed.Client.GetFromJsonAsync<List<NoteResponse>>(
            $"/api/v1/notes?groupId={groupId}");
        byGroup.Should().ContainSingle(n => n.Id == inGroup!.Id);
        byGroup.Should().NotContain(n => n.Id == inProject!.Id || n.Id == loose!.Id);

        var byProject = await authed.Client.GetFromJsonAsync<List<NoteResponse>>(
            $"/api/v1/notes?projectId={projectId}");
        byProject.Should().ContainSingle(n => n.Id == inProject!.Id);
        byProject.Should().NotContain(n => n.Id == inGroup!.Id || n.Id == loose!.Id);

        var all = await authed.Client.GetFromJsonAsync<List<NoteResponse>>("/api/v1/notes");
        all!.Select(n => n.Id).Should().Contain(new[] { inGroup!.Id, inProject!.Id, loose!.Id });
    }

    [Fact]
    public async Task Cross_user_cannot_see_get_or_mutate_anothers_note()
    {
        var alice = await RegisterAsync();
        var bob = await RegisterAsync();

        var created = await (await alice.Client.PostAsJsonAsync("/api/v1/notes",
            new CreateNoteRequest(null, null, "Alice Note", "secret", "a0")))
            .Content.ReadFromJsonAsync<NoteResponse>();

        var bobList = await bob.Client.GetFromJsonAsync<List<NoteResponse>>("/api/v1/notes");
        bobList.Should().NotContain(n => n.Id == created!.Id);

        var bobGet = await bob.Client.GetAsync($"/api/v1/notes/{created!.Id}");
        bobGet.StatusCode.Should().Be(HttpStatusCode.NotFound);

        var bobPatch = await bob.Client.PatchAsJsonAsync($"/api/v1/notes/{created.Id}",
            new UpdateNoteRequest(null, null, "Hijack", "evil", "z9"));
        bobPatch.StatusCode.Should().Be(HttpStatusCode.NotFound);

        var bobDelete = await bob.Client.DeleteAsync($"/api/v1/notes/{created.Id}");
        bobDelete.StatusCode.Should().Be(HttpStatusCode.NotFound);
    }

    [Fact]
    public async Task Reorder_updates_only_specified_rows_ranks()
    {
        var authed = await RegisterAsync();

        var a = await (await authed.Client.PostAsJsonAsync("/api/v1/notes",
            new CreateNoteRequest(null, null, "A", "x", "a0")))
            .Content.ReadFromJsonAsync<NoteResponse>();
        var b = await (await authed.Client.PostAsJsonAsync("/api/v1/notes",
            new CreateNoteRequest(null, null, "B", "y", "a1")))
            .Content.ReadFromJsonAsync<NoteResponse>();

        var reorderResp = await authed.Client.PatchAsJsonAsync(
            "/api/v1/notes/reorder",
            new[] { new ReorderItem(b!.Id, "Zz") });
        reorderResp.StatusCode.Should().Be(HttpStatusCode.NoContent);

        var list = await authed.Client.GetFromJsonAsync<List<NoteResponse>>("/api/v1/notes");
        list!.Single(n => n.Id == b.Id).Rank.Should().Be("Zz");
        list.Single(n => n.Id == a!.Id).Rank.Should().Be("a0");
        list.Select(n => n.Rank).Should().BeInAscendingOrder();
    }

    [Fact]
    public async Task Create_with_another_users_projectId_returns_400_and_does_not_persist()
    {
        var owner = await RegisterAsync();
        var intruder = await RegisterAsync();

        var ownerProjectId = Guid.CreateVersion7();
        await using (var arrange = NewElevatedDbContext())
        {
            arrange.Projects.Add(new Project { Id = ownerProjectId, UserId = owner.UserId, Name = "Owner P", Color = "#1", Emoji = "📁", Rank = "a0", ActualTimeMinutes = 0 });
            await arrange.SaveChangesAsync();
        }

        var resp = await intruder.Client.PostAsJsonAsync("/api/v1/notes",
            new CreateNoteRequest(GroupId: null, ProjectId: ownerProjectId, Title: "Smuggled", Content: "x", Rank: "a0"));

        resp.StatusCode.Should().Be(HttpStatusCode.BadRequest);
        resp.Content.Headers.ContentType?.MediaType.Should().Be("application/problem+json");
        var problem = await resp.Content.ReadFromJsonAsync<Microsoft.AspNetCore.Mvc.ValidationProblemDetails>();
        problem!.Errors.Should().ContainKey("projectId");

        await using var db = NewElevatedDbContext(intruder.UserId);
        var any = await db.Notes.IgnoreQueryFilters(["SoftDelete"]).AnyAsync(n => n.Title == "Smuggled");
        any.Should().BeFalse();
    }

    [Fact]
    public async Task Create_with_another_users_groupId_returns_400_and_does_not_persist()
    {
        var owner = await RegisterAsync();
        var intruder = await RegisterAsync();

        var ownerGroupId = Guid.CreateVersion7();
        await using (var arrange = NewElevatedDbContext())
        {
            arrange.NoteGroups.Add(new NoteGroup { Id = ownerGroupId, UserId = owner.UserId, Name = "Owner G", Emoji = "📁", ProjectId = null, Rank = "a0" });
            await arrange.SaveChangesAsync();
        }

        var resp = await intruder.Client.PostAsJsonAsync("/api/v1/notes",
            new CreateNoteRequest(GroupId: ownerGroupId, ProjectId: null, Title: "SmuggledGroup", Content: "x", Rank: "a0"));

        resp.StatusCode.Should().Be(HttpStatusCode.BadRequest);
        var problem = await resp.Content.ReadFromJsonAsync<Microsoft.AspNetCore.Mvc.ValidationProblemDetails>();
        problem!.Errors.Should().ContainKey("groupId");

        await using var db = NewElevatedDbContext(intruder.UserId);
        var any = await db.Notes.IgnoreQueryFilters(["SoftDelete"]).AnyAsync(n => n.Title == "SmuggledGroup");
        any.Should().BeFalse();
    }

    [Fact]
    public async Task Patch_with_another_users_groupId_returns_400_and_does_not_persist()
    {
        var owner = await RegisterAsync();
        var intruder = await RegisterAsync();

        var ownerGroupId = Guid.CreateVersion7();
        await using (var arrange = NewElevatedDbContext())
        {
            arrange.NoteGroups.Add(new NoteGroup { Id = ownerGroupId, UserId = owner.UserId, Name = "Owner G", Emoji = "📁", ProjectId = null, Rank = "a0" });
            await arrange.SaveChangesAsync();
        }

        var note = await (await intruder.Client.PostAsJsonAsync("/api/v1/notes",
            new CreateNoteRequest(null, null, "Mine", "x", "a0")))
            .Content.ReadFromJsonAsync<NoteResponse>();

        var resp = await intruder.Client.PatchAsJsonAsync($"/api/v1/notes/{note!.Id}",
            new UpdateNoteRequest(GroupId: ownerGroupId, ProjectId: null, Title: null, Content: null, Rank: null));

        resp.StatusCode.Should().Be(HttpStatusCode.BadRequest);
        var problem = await resp.Content.ReadFromJsonAsync<Microsoft.AspNetCore.Mvc.ValidationProblemDetails>();
        problem!.Errors.Should().ContainKey("groupId");

        await using var db = NewElevatedDbContext(intruder.UserId);
        var row = await db.Notes.SingleAsync(n => n.Id == note.Id);
        row.GroupId.Should().BeNull();
    }

    [Fact]
    public async Task Create_with_own_group_and_project_succeeds()
    {
        var owner = await RegisterAsync();

        var ownProjectId = Guid.CreateVersion7();
        var ownGroupId = Guid.CreateVersion7();
        await using (var arrange = NewElevatedDbContext())
        {
            arrange.Projects.Add(new Project { Id = ownProjectId, UserId = owner.UserId, Name = "Own P", Color = "#1", Emoji = "📁", Rank = "a0", ActualTimeMinutes = 0 });
            arrange.NoteGroups.Add(new NoteGroup { Id = ownGroupId, UserId = owner.UserId, Name = "Own G", Emoji = "📁", ProjectId = null, Rank = "a0" });
            await arrange.SaveChangesAsync();
        }

        var resp = await owner.Client.PostAsJsonAsync("/api/v1/notes",
            new CreateNoteRequest(GroupId: ownGroupId, ProjectId: ownProjectId, Title: "Linked", Content: "x", Rank: "a0"));
        resp.StatusCode.Should().Be(HttpStatusCode.Created);
        var created = await resp.Content.ReadFromJsonAsync<NoteResponse>();
        created!.GroupId.Should().Be(ownGroupId);
        created.ProjectId.Should().Be(ownProjectId);
    }

    [Fact]
    public async Task Delete_softdeletes_note_and_removes_from_live_reads()
    {
        var authed = await RegisterAsync();

        var note = await (await authed.Client.PostAsJsonAsync("/api/v1/notes",
            new CreateNoteRequest(null, null, "Doomed", "bye", "a0")))
            .Content.ReadFromJsonAsync<NoteResponse>();
        var noteId = note!.Id;

        var deleteResp = await authed.Client.DeleteAsync($"/api/v1/notes/{noteId}");
        deleteResp.StatusCode.Should().Be(HttpStatusCode.NoContent);

        // Gone from live list and get-by-id.
        var list = await authed.Client.GetFromJsonAsync<List<NoteResponse>>("/api/v1/notes");
        list.Should().NotContain(n => n.Id == noteId);
        var getResp = await authed.Client.GetAsync($"/api/v1/notes/{noteId}");
        getResp.StatusCode.Should().Be(HttpStatusCode.NotFound);

        // Tombstoned, not hard-deleted.
        await using var assertDb = NewElevatedDbContext();
        var tombstoned = await assertDb.Notes
            .IgnoreQueryFilters(["SoftDelete"])
            .SingleAsync(n => n.Id == noteId);
        tombstoned.DeletedAt.Should().NotBeNull();
    }

    [Fact]
    public async Task Create_SameClientId_Twice_Returns200_SameId_NoDuplicate()
    {
        var user = await RegisterAsync();
        var id = Guid.CreateVersion7();
        var body = new { id, groupId = (Guid?)null, projectId = (Guid?)null, title = "N", content = "", rank = "a0" };

        var first = await user.Client.PostAsJsonAsync("/api/v1/notes", body);
        first.StatusCode.Should().Be(HttpStatusCode.Created);

        var second = await user.Client.PostAsJsonAsync("/api/v1/notes", body);
        second.StatusCode.Should().Be(HttpStatusCode.OK);
        var dto = await second.Content.ReadFromJsonAsync<NoteResponse>();
        dto!.Id.Should().Be(id);

        var list = await user.Client.GetFromJsonAsync<List<NoteResponse>>("/api/v1/notes");
        list!.Count(n => n.Id == id).Should().Be(1);
    }
}
