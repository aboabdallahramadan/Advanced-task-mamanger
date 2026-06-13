using System.Net;
using System.Net.Http.Json;
using FluentAssertions;
using Microsoft.EntityFrameworkCore;
using Tmap.Api.Features.NoteGroups;
using Tmap.Api.Infrastructure.Entities;
using Xunit;

namespace Tmap.Api.Tests;

[Collection("db")]
public sealed class NoteGroupsTests(PostgresFixture fixture) : IntegrationTestBase(fixture)
{
    [Fact]
    public async Task Create_then_get_then_patch_roundtrips()
    {
        var authed = await RegisterAsync();

        var createResp = await authed.Client.PostAsJsonAsync(
            "/api/v1/note-groups",
            new CreateNoteGroupRequest(Name: "Inbox", Emoji: "📥", ProjectId: null, Rank: "a0"));
        createResp.StatusCode.Should().Be(HttpStatusCode.Created);

        var created = await createResp.Content.ReadFromJsonAsync<NoteGroupResponse>();
        created!.Name.Should().Be("Inbox");
        created.Emoji.Should().Be("📥");
        created.ProjectId.Should().BeNull();
        created.Rank.Should().Be("a0");
        created.Id.Should().NotBe(Guid.Empty);

        var list = await authed.Client.GetFromJsonAsync<List<NoteGroupResponse>>("/api/v1/note-groups");
        list.Should().ContainSingle(g => g.Id == created.Id);

        var patchResp = await authed.Client.PatchAsJsonAsync(
            $"/api/v1/note-groups/{created.Id}",
            new UpdateNoteGroupRequest(Name: "Renamed", Emoji: "📦", ProjectId: null, Rank: "a1"));
        patchResp.StatusCode.Should().Be(HttpStatusCode.OK);
        var patched = await patchResp.Content.ReadFromJsonAsync<NoteGroupResponse>();
        patched!.Name.Should().Be("Renamed");
        patched.Emoji.Should().Be("📦");
        patched.Rank.Should().Be("a1");
        patched.Id.Should().Be(created.Id);
    }

    [Fact]
    public async Task GetAll_filters_by_projectId()
    {
        var authed = await RegisterAsync();
        var projectId = Guid.CreateVersion7();
        var otherProjectId = Guid.CreateVersion7();

        // Create a real project so the FK reference is valid, then groups referencing it.
        await using (var arrange = NewElevatedDbContext())
        {
            arrange.Projects.Add(new Project { Id = projectId, UserId = authed.UserId, Name = "P1", Color = "#1", Emoji = "📁", Rank = "a0", ActualTimeMinutes = 0 });
            arrange.Projects.Add(new Project { Id = otherProjectId, UserId = authed.UserId, Name = "P2", Color = "#2", Emoji = "📁", Rank = "a1", ActualTimeMinutes = 0 });
            await arrange.SaveChangesAsync();
        }

        var inP1 = await (await authed.Client.PostAsJsonAsync("/api/v1/note-groups",
            new CreateNoteGroupRequest("G-P1", "📁", projectId, "a0")))
            .Content.ReadFromJsonAsync<NoteGroupResponse>();
        var inP2 = await (await authed.Client.PostAsJsonAsync("/api/v1/note-groups",
            new CreateNoteGroupRequest("G-P2", "📁", otherProjectId, "a0")))
            .Content.ReadFromJsonAsync<NoteGroupResponse>();
        var unassigned = await (await authed.Client.PostAsJsonAsync("/api/v1/note-groups",
            new CreateNoteGroupRequest("G-none", "📁", null, "a0")))
            .Content.ReadFromJsonAsync<NoteGroupResponse>();

        var filtered = await authed.Client.GetFromJsonAsync<List<NoteGroupResponse>>(
            $"/api/v1/note-groups?projectId={projectId}");
        filtered.Should().ContainSingle(g => g.Id == inP1!.Id);
        filtered.Should().NotContain(g => g.Id == inP2!.Id);
        filtered.Should().NotContain(g => g.Id == unassigned!.Id);

        var all = await authed.Client.GetFromJsonAsync<List<NoteGroupResponse>>("/api/v1/note-groups");
        all!.Select(g => g.Id).Should().Contain(new[] { inP1!.Id, inP2!.Id, unassigned!.Id });
    }

    [Fact]
    public async Task Cross_user_cannot_see_or_mutate_anothers_note_group()
    {
        var alice = await RegisterAsync();
        var bob = await RegisterAsync();

        var created = await (await alice.Client.PostAsJsonAsync("/api/v1/note-groups",
            new CreateNoteGroupRequest("Alice Group", "🔒", null, "a0")))
            .Content.ReadFromJsonAsync<NoteGroupResponse>();

        var bobList = await bob.Client.GetFromJsonAsync<List<NoteGroupResponse>>("/api/v1/note-groups");
        bobList.Should().NotContain(g => g.Id == created!.Id);

        var bobPatch = await bob.Client.PatchAsJsonAsync($"/api/v1/note-groups/{created!.Id}",
            new UpdateNoteGroupRequest("Hijack", "💀", null, "z9"));
        bobPatch.StatusCode.Should().Be(HttpStatusCode.NotFound);

        var bobDelete = await bob.Client.DeleteAsync($"/api/v1/note-groups/{created.Id}");
        bobDelete.StatusCode.Should().Be(HttpStatusCode.NotFound);
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

        var resp = await intruder.Client.PostAsJsonAsync("/api/v1/note-groups",
            new CreateNoteGroupRequest("Smuggled", "📁", ownerProjectId, "a0"));

        resp.StatusCode.Should().Be(HttpStatusCode.BadRequest);
        resp.Content.Headers.ContentType?.MediaType.Should().Be("application/problem+json");
        var problem = await resp.Content.ReadFromJsonAsync<Microsoft.AspNetCore.Mvc.ValidationProblemDetails>();
        problem!.Errors.Should().ContainKey("projectId");

        await using var db = NewElevatedDbContext(intruder.UserId);
        var any = await db.NoteGroups.IgnoreQueryFilters(["SoftDelete"]).AnyAsync(g => g.Name == "Smuggled");
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
            arrange.Projects.Add(new Project { Id = ownerProjectId, UserId = owner.UserId, Name = "Owner P", Color = "#1", Emoji = "📁", Rank = "a0", ActualTimeMinutes = 0 });
            await arrange.SaveChangesAsync();
        }

        var group = await (await intruder.Client.PostAsJsonAsync("/api/v1/note-groups",
            new CreateNoteGroupRequest("Mine", "📁", null, "a0")))
            .Content.ReadFromJsonAsync<NoteGroupResponse>();

        var resp = await intruder.Client.PatchAsJsonAsync($"/api/v1/note-groups/{group!.Id}",
            new UpdateNoteGroupRequest(Name: null, Emoji: null, ProjectId: ownerProjectId, Rank: null));

        resp.StatusCode.Should().Be(HttpStatusCode.BadRequest);
        var problem = await resp.Content.ReadFromJsonAsync<Microsoft.AspNetCore.Mvc.ValidationProblemDetails>();
        problem!.Errors.Should().ContainKey("projectId");

        await using var db = NewElevatedDbContext(intruder.UserId);
        var row = await db.NoteGroups.SingleAsync(g => g.Id == group.Id);
        row.ProjectId.Should().BeNull();
    }

    [Fact]
    public async Task Create_with_own_projectId_succeeds()
    {
        var owner = await RegisterAsync();

        var ownProjectId = Guid.CreateVersion7();
        await using (var arrange = NewElevatedDbContext())
        {
            arrange.Projects.Add(new Project { Id = ownProjectId, UserId = owner.UserId, Name = "Own P", Color = "#1", Emoji = "📁", Rank = "a0", ActualTimeMinutes = 0 });
            await arrange.SaveChangesAsync();
        }

        var resp = await owner.Client.PostAsJsonAsync("/api/v1/note-groups",
            new CreateNoteGroupRequest("Linked", "📁", ownProjectId, "a0"));
        resp.StatusCode.Should().Be(HttpStatusCode.Created);
        var created = await resp.Content.ReadFromJsonAsync<NoteGroupResponse>();
        created!.ProjectId.Should().Be(ownProjectId);
    }

    [Fact]
    public async Task Reorder_updates_ranks_for_all_supplied_ids()
    {
        var authed = await RegisterAsync();

        // Create three groups.
        var a = await (await authed.Client.PostAsJsonAsync("/api/v1/note-groups",
            new CreateNoteGroupRequest("A", "📁", null, "a0")))
            .Content.ReadFromJsonAsync<NoteGroupResponse>();
        var b = await (await authed.Client.PostAsJsonAsync("/api/v1/note-groups",
            new CreateNoteGroupRequest("B", "📁", null, "b0")))
            .Content.ReadFromJsonAsync<NoteGroupResponse>();
        var c = await (await authed.Client.PostAsJsonAsync("/api/v1/note-groups",
            new CreateNoteGroupRequest("C", "📁", null, "c0")))
            .Content.ReadFromJsonAsync<NoteGroupResponse>();

        // Reorder: move A to end by giving it rank "z0".
        var items = new[]
        {
            new { id = a!.Id, rank = "z0" },
            new { id = b!.Id, rank = "a0" },
            new { id = c!.Id, rank = "m0" },
        };

        var resp = await authed.Client.PatchAsJsonAsync("/api/v1/note-groups/reorder", items);
        resp.StatusCode.Should().Be(HttpStatusCode.NoContent);

        var list = await authed.Client.GetFromJsonAsync<List<NoteGroupResponse>>("/api/v1/note-groups");
        list!.First(g => g.Id == b.Id).Rank.Should().Be("a0");
        list.First(g => g.Id == c.Id).Rank.Should().Be("m0");
        list.First(g => g.Id == a.Id).Rank.Should().Be("z0");
        // Verify list is returned sorted by new ranks.
        list.Select(g => g.Rank).Should().BeInAscendingOrder(StringComparer.Ordinal);
    }

    [Fact]
    public async Task Reorder_ignores_ids_belonging_to_other_users()
    {
        var alice = await RegisterAsync();
        var bob = await RegisterAsync();

        var aliceGroup = await (await alice.Client.PostAsJsonAsync("/api/v1/note-groups",
            new CreateNoteGroupRequest("AliceG", "📁", null, "a0")))
            .Content.ReadFromJsonAsync<NoteGroupResponse>();

        // Bob tries to reorder Alice's group.
        var resp = await bob.Client.PatchAsJsonAsync("/api/v1/note-groups/reorder",
            new[] { new { id = aliceGroup!.Id, rank = "z9" } });
        resp.StatusCode.Should().Be(HttpStatusCode.NoContent); // no error, but nothing changed

        // Alice's group rank is unchanged.
        var aliceList = await alice.Client.GetFromJsonAsync<List<NoteGroupResponse>>("/api/v1/note-groups");
        aliceList!.Single(g => g.Id == aliceGroup.Id).Rank.Should().Be("a0");
    }

    [Fact]
    public async Task Delete_softdeletes_group_and_tombstones_its_notes()
    {
        var authed = await RegisterAsync();

        var group = await (await authed.Client.PostAsJsonAsync("/api/v1/note-groups",
            new CreateNoteGroupRequest("WithNotes", "📁", null, "a0")))
            .Content.ReadFromJsonAsync<NoteGroupResponse>();
        var groupId = group!.Id;

        var noteInGroupId = Guid.CreateVersion7();
        var looseNoteId = Guid.CreateVersion7();
        await using (var arrange = NewElevatedDbContext())
        {
            arrange.Notes.Add(new Note
            {
                Id = noteInGroupId,
                UserId = authed.UserId,
                GroupId = groupId,
                ProjectId = null,
                Title = "in group",
                Content = "x",
                Rank = "a0",
            });
            arrange.Notes.Add(new Note
            {
                Id = looseNoteId,
                UserId = authed.UserId,
                GroupId = null,
                ProjectId = null,
                Title = "loose",
                Content = "y",
                Rank = "a0",
            });
            await arrange.SaveChangesAsync();
        }

        var deleteResp = await authed.Client.DeleteAsync($"/api/v1/note-groups/{groupId}");
        deleteResp.StatusCode.Should().Be(HttpStatusCode.NoContent);

        await using var assertDb = NewElevatedDbContext();

        // Group is tombstoned, not hard-deleted.
        var deletedGroup = await assertDb.NoteGroups
            .IgnoreQueryFilters(["SoftDelete"])
            .SingleAsync(g => g.Id == groupId);
        deletedGroup.DeletedAt.Should().NotBeNull();

        // The note that was in the group is tombstoned.
        var deletedNote = await assertDb.Notes
            .IgnoreQueryFilters(["SoftDelete"])
            .SingleAsync(n => n.Id == noteInGroupId);
        deletedNote.DeletedAt.Should().NotBeNull();

        // The loose note (different group) is untouched.
        var liveNote = await assertDb.Notes.SingleAsync(n => n.Id == looseNoteId);
        liveNote.DeletedAt.Should().BeNull();
    }

    [Fact]
    public async Task Create_SameClientId_Twice_Returns200_SameId_NoDuplicate()
    {
        var user = await RegisterAsync();
        var id = Guid.CreateVersion7();
        var body = new { id, name = "G", emoji = "📝", projectId = (Guid?)null, rank = "a0" };

        var first = await user.Client.PostAsJsonAsync("/api/v1/note-groups", body);
        first.StatusCode.Should().Be(HttpStatusCode.Created);

        var second = await user.Client.PostAsJsonAsync("/api/v1/note-groups", body);
        second.StatusCode.Should().Be(HttpStatusCode.OK);
        var dto = await second.Content.ReadFromJsonAsync<NoteGroupResponse>();
        dto!.Id.Should().Be(id);

        var list = await user.Client.GetFromJsonAsync<List<NoteGroupResponse>>("/api/v1/note-groups");
        list!.Count(g => g.Id == id).Should().Be(1);
    }

    [Fact]
    public async Task List_TiedRanks_AreOrderedById_Stably()
    {
        var user = await RegisterAsync();
        var idLo = new Guid("00000000-0000-0000-0000-000000000031");
        var idHi = new Guid("00000000-0000-0000-0000-000000000032");
        await user.Client.PostAsJsonAsync("/api/v1/note-groups",
            new { id = idHi, name = "Hi", emoji = "📝", projectId = (Guid?)null, rank = "a0" });
        await user.Client.PostAsJsonAsync("/api/v1/note-groups",
            new { id = idLo, name = "Lo", emoji = "📝", projectId = (Guid?)null, rank = "a0" });

        var list = await user.Client.GetFromJsonAsync<List<NoteGroupResponse>>("/api/v1/note-groups");
        var tied = list!.Where(g => g.Id == idLo || g.Id == idHi).Select(g => g.Id).ToList();
        tied.Should().Equal(idLo, idHi);
    }
}
