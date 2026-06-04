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
                Id = noteInGroupId, UserId = authed.UserId, GroupId = groupId,
                ProjectId = null, Title = "in group", Content = "x", Rank = "a0",
            });
            arrange.Notes.Add(new Note
            {
                Id = looseNoteId, UserId = authed.UserId, GroupId = null,
                ProjectId = null, Title = "loose", Content = "y", Rank = "a0",
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
}
