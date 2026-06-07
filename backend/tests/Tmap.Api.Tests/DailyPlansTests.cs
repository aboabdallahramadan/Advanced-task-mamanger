using System.Net;
using System.Net.Http.Json;
using FluentAssertions;
using Microsoft.EntityFrameworkCore;
using Tmap.Api.Features.DailyPlans;
using Tmap.Api.Infrastructure;
using Tmap.Api.Infrastructure.Entities;
using Xunit;

namespace Tmap.Api.Tests;

[Collection("db")]
public class DailyPlansTests(PostgresFixture fixture) : IntegrationTestBase(fixture)
{
    [Fact]
    public async Task Get_ReturnsNotFound_WhenNoPlanForDate()
    {
        var user = await RegisterAsync();
        var resp = await user.Client.GetAsync("/api/v1/daily-plans/2026-06-01");
        resp.StatusCode.Should().Be(HttpStatusCode.NotFound);
    }

    [Fact]
    public async Task Put_InsertsThenUpdates_WholeDayLww()
    {
        var user = await RegisterAsync();
        var id1 = Guid.CreateVersion7();
        var id2 = Guid.CreateVersion7();

        // First upsert (insert)
        var put1 = await user.Client.PutAsJsonAsync("/api/v1/daily-plans/2026-06-01",
            new UpsertDailyPlanRequest(new List<Guid> { id1 }, 60));
        put1.StatusCode.Should().Be(HttpStatusCode.OK);
        var dto1 = await put1.Content.ReadFromJsonAsync<DailyPlanResponse>();
        dto1!.PlannedTaskIds.Should().Equal(id1);
        dto1.PlannedMinutes.Should().Be(60);
        dto1.Date.Should().Be(new DateOnly(2026, 6, 1));

        // Second upsert (update / whole-day overwrite)
        var put2 = await user.Client.PutAsJsonAsync("/api/v1/daily-plans/2026-06-01",
            new UpsertDailyPlanRequest(new List<Guid> { id2 }, 90));
        put2.StatusCode.Should().Be(HttpStatusCode.OK);

        var get = await user.Client.GetFromJsonAsync<DailyPlanResponse>("/api/v1/daily-plans/2026-06-01");
        get!.PlannedTaskIds.Should().Equal(id2); // whole-day replaced, not merged
        get.PlannedMinutes.Should().Be(90);
    }

    [Fact]
    public async Task Put_IsScopedPerUser()
    {
        var a = await RegisterAsync();
        var b = await RegisterAsync();
        var idA = Guid.CreateVersion7();

        await a.Client.PutAsJsonAsync("/api/v1/daily-plans/2026-06-02",
            new UpsertDailyPlanRequest(new List<Guid> { idA }, 30));

        // Same date, different user -> independent row, B sees none.
        var bGet = await b.Client.GetAsync("/api/v1/daily-plans/2026-06-02");
        bGet.StatusCode.Should().Be(HttpStatusCode.NotFound);

        var aGet = await a.Client.GetFromJsonAsync<DailyPlanResponse>("/api/v1/daily-plans/2026-06-02");
        aGet!.PlannedTaskIds.Should().Equal(idA);
    }

    [Fact]
    public async Task Put_RevivesSoftDeletedRow_NoDuplicateKey()
    {
        var user = await RegisterAsync();
        var date = new DateOnly(2026, 6, 3);
        var idA = Guid.CreateVersion7();
        var idB = Guid.CreateVersion7();

        // First upsert (insert the live row).
        var put1 = await user.Client.PutAsJsonAsync($"/api/v1/daily-plans/{date:yyyy-MM-dd}",
            new UpsertDailyPlanRequest(new List<Guid> { idA }, 60));
        put1.StatusCode.Should().Be(HttpStatusCode.OK);

        // Soft-delete the row out-of-band (tombstone the same composite PK).
        await using (var db = NewElevatedDbContext(user.UserId))
        {
            var row = await db.Set<DailyPlan>()
                .SingleAsync(p => p.UserId == user.UserId && p.Date == date);
            row.DeletedAt = DateTimeOffset.UtcNow;
            await db.SaveChangesAsync();
        }

        // PUT again: must revive the tombstone in place, not Add a duplicate-PK row.
        var put2 = await user.Client.PutAsJsonAsync($"/api/v1/daily-plans/{date:yyyy-MM-dd}",
            new UpsertDailyPlanRequest(new List<Guid> { idB }, 90));
        put2.StatusCode.Should().Be(HttpStatusCode.OK);
        var dto = await put2.Content.ReadFromJsonAsync<DailyPlanResponse>();
        dto!.PlannedTaskIds.Should().Equal(idB);
        dto.PlannedMinutes.Should().Be(90);

        // The row is revived (DeletedAt null) and there is exactly one row for that key.
        await using (var db = NewElevatedDbContext(user.UserId))
        {
            var rows = await db.Set<DailyPlan>()
                .IgnoreQueryFilters([AppDbContext.SoftDeleteFilter])
                .Where(p => p.UserId == user.UserId && p.Date == date)
                .ToListAsync();
            rows.Should().HaveCount(1);
            rows[0].DeletedAt.Should().BeNull();
            rows[0].PlannedTaskIds.Should().Equal(idB);
            rows[0].PlannedMinutes.Should().Be(90);
        }

        // The live GET path sees the revived row.
        var get = await user.Client.GetFromJsonAsync<DailyPlanResponse>(
            $"/api/v1/daily-plans/{date:yyyy-MM-dd}");
        get!.PlannedTaskIds.Should().Equal(idB);
    }

    [Fact]
    public async Task Put_RejectsNegativeMinutes()
    {
        var user = await RegisterAsync();
        var resp = await user.Client.PutAsJsonAsync("/api/v1/daily-plans/2026-06-01",
            new UpsertDailyPlanRequest(new List<Guid>(), -1));
        resp.StatusCode.Should().Be(HttpStatusCode.BadRequest);
    }

    [Fact]
    public async Task Get_RequiresAuthentication()
    {
        var resp = await Client.GetAsync("/api/v1/daily-plans/2026-06-01");
        resp.StatusCode.Should().Be(HttpStatusCode.Unauthorized);
    }
}
