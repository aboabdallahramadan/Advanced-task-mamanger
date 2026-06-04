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
}
