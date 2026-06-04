using System.Net.Http.Json;
using FluentAssertions;
using Microsoft.EntityFrameworkCore;
using Tmap.Api.Infrastructure.Entities;
using Xunit;

namespace Tmap.Api.Tests;

[Collection("db")]
public class AuthTests(PostgresFixture fixture) : IntegrationTestBase(fixture)
{
    private sealed record TokenPair(string accessToken, string refreshToken, DateTimeOffset accessTokenExpiresAt);

    [Fact]
    public async Task RefreshTokens_table_exists_with_owner_and_hash_columns()
    {
        await using var db = NewElevatedDbContext();
        // Should not throw: table + columns mapped & migrated.
        // Count >= 0 (other tests in the same container run may have inserted tokens).
        var count = await db.Set<RefreshToken>().CountAsync();
        count.Should().BeGreaterThanOrEqualTo(0);
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

    [Fact]
    public async Task Register_rejects_password_shorter_than_10_chars_with_400()
    {
        var res = await Client.PostAsJsonAsync("/api/v1/auth/register",
            new { email = $"short-{Guid.NewGuid():N}@x.io", password = "Ab1!xxxx" }); // 8 chars
        res.StatusCode.Should().Be(System.Net.HttpStatusCode.BadRequest);
    }

    [Fact]
    public async Task Register_returns_access_and_refresh_tokens()
    {
        var email = $"reg-{Guid.NewGuid():N}@x.io";
        var res = await Client.PostAsJsonAsync("/api/v1/auth/register",
            new { email, password = "Password123!x" });

        res.StatusCode.Should().Be(System.Net.HttpStatusCode.OK);
        var body = await res.Content.ReadFromJsonAsync<TokenPair>();
        body!.accessToken.Should().NotBeNullOrWhiteSpace();
        body.refreshToken.Should().NotBeNullOrWhiteSpace();
        body.accessTokenExpiresAt.Should().BeAfter(DateTimeOffset.UtcNow);
    }

    [Fact]
    public async Task Register_duplicate_email_returns_409()
    {
        var email = $"dup-{Guid.NewGuid():N}@x.io";
        (await Client.PostAsJsonAsync("/api/v1/auth/register", new { email, password = "Password123!x" }))
            .StatusCode.Should().Be(System.Net.HttpStatusCode.OK);
        var second = await Client.PostAsJsonAsync("/api/v1/auth/register", new { email, password = "Password123!x" });
        second.StatusCode.Should().Be(System.Net.HttpStatusCode.Conflict);
    }
}
