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
}
