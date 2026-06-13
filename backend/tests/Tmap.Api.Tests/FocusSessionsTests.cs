using System.Net;
using System.Net.Http.Json;
using FluentAssertions;
using Microsoft.EntityFrameworkCore;
using Tmap.Api.Features.FocusSessions;
using Tmap.Api.Infrastructure.Entities;
using Xunit;

namespace Tmap.Api.Tests;

[Collection("db")]
public class FocusSessionsTests(PostgresFixture fixture) : IntegrationTestBase(fixture)
{
    [Fact]
    public async Task Add_PersistsAppendOnlySession_ScopedToUser()
    {
        var user = await RegisterAsync();
        var body = new CreateFocusSessionRequest(
            TaskId: null,
            Project: "Deep Work",
            StartedAt: DateTimeOffset.Parse("2026-06-01T09:00:00Z"),
            EndedAt: DateTimeOffset.Parse("2026-06-01T09:25:00Z"),
            Minutes: 25,
            Date: new DateOnly(2026, 6, 1));

        var resp = await user.Client.PostAsJsonAsync("/api/v1/focus-sessions", body);
        resp.StatusCode.Should().Be(HttpStatusCode.Created);

        var dto = await resp.Content.ReadFromJsonAsync<FocusSessionResponse>();
        dto!.Project.Should().Be("Deep Work");
        dto.Minutes.Should().Be(25);
        dto.Id.Should().NotBe(Guid.Empty);

        await using var db = NewElevatedDbContext();
        var row = await db.Set<FocusSession>().SingleAsync(f => f.Id == dto.Id);
        row.UserId.Should().Be(user.UserId);
        row.ChangeSeq.Should().BeGreaterThan(0);
    }

    [Fact]
    public async Task Add_RequiresAuthentication()
    {
        var body = new CreateFocusSessionRequest(null, "x",
            DateTimeOffset.UtcNow, DateTimeOffset.UtcNow, 5, new DateOnly(2026, 6, 1));
        var resp = await Client.PostAsJsonAsync("/api/v1/focus-sessions", body);
        resp.StatusCode.Should().Be(HttpStatusCode.Unauthorized);
    }

    [Fact]
    public async Task Add_RejectsNonPositiveMinutes()
    {
        var user = await RegisterAsync();
        var body = new CreateFocusSessionRequest(null, "x",
            DateTimeOffset.UtcNow, DateTimeOffset.UtcNow, 0, new DateOnly(2026, 6, 1));
        var resp = await user.Client.PostAsJsonAsync("/api/v1/focus-sessions", body);
        resp.StatusCode.Should().Be(HttpStatusCode.BadRequest);
    }

    [Fact]
    public async Task Add_SameClientId_Twice_Returns200_OneRow()
    {
        var user = await RegisterAsync();
        var id = Guid.CreateVersion7();
        var body = new CreateFocusSessionRequest(
            Id: id,
            TaskId: null,
            Project: "Deep Work",
            StartedAt: DateTimeOffset.Parse("2026-06-01T09:00:00Z"),
            EndedAt: DateTimeOffset.Parse("2026-06-01T09:25:00Z"),
            Minutes: 25,
            Date: new DateOnly(2026, 6, 1));

        var first = await user.Client.PostAsJsonAsync("/api/v1/focus-sessions", body);
        first.StatusCode.Should().Be(HttpStatusCode.Created);
        var firstDto = await first.Content.ReadFromJsonAsync<FocusSessionResponse>();
        firstDto!.Id.Should().Be(id);

        var second = await user.Client.PostAsJsonAsync("/api/v1/focus-sessions", body);
        second.StatusCode.Should().Be(HttpStatusCode.OK, "replaying a focus-session create is idempotent");
        var secondDto = await second.Content.ReadFromJsonAsync<FocusSessionResponse>();
        secondDto!.Id.Should().Be(id);

        await using var db = NewElevatedDbContext();
        var count = await db.Set<FocusSession>().CountAsync(f => f.Id == id);
        count.Should().Be(1, "no duplicate session");
    }
}
