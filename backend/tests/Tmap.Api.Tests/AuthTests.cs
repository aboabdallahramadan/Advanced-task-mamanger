using FluentAssertions;
using Microsoft.EntityFrameworkCore;
using Tmap.Api.Infrastructure.Entities;
using Xunit;

namespace Tmap.Api.Tests;

[Collection("db")]
public class AuthTests(PostgresFixture fixture) : IntegrationTestBase(fixture)
{
    [Fact]
    public async Task RefreshTokens_table_exists_with_owner_and_hash_columns()
    {
        await using var db = NewElevatedDbContext();
        // Should not throw: table + columns mapped & migrated.
        var count = await db.Set<RefreshToken>().CountAsync();
        count.Should().Be(0);
    }

    [Fact]
    public async Task ApplicationUser_carries_TimeZoneId_defaulting_to_UTC()
    {
        await using var db = NewElevatedDbContext();
        var user = new ApplicationUser { Id = Guid.NewGuid(), UserName = "tz@x.io", Email = "tz@x.io" };
        db.Users.Add(user);
        await db.SaveChangesAsync();

        var loaded = await db.Users.AsNoTracking().SingleAsync(u => u.Id == user.Id);
        loaded.TimeZoneId.Should().Be("UTC");
    }
}
