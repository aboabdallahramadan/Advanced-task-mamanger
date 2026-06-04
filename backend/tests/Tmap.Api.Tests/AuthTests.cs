using System.Net.Http.Json;
using System.Text.Json;
using FluentAssertions;
using Microsoft.AspNetCore.Http;
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

    [Fact]
    public async Task Login_then_access_me_returns_profile()
    {
        var email = $"login-{Guid.NewGuid():N}@x.io";
        await Client.PostAsJsonAsync("/api/v1/auth/register", new { email, password = "Password123!x" });

        var login = await Client.PostAsJsonAsync("/api/v1/auth/login", new { email, password = "Password123!x" });
        login.StatusCode.Should().Be(System.Net.HttpStatusCode.OK);
        var pair = await login.Content.ReadFromJsonAsync<TokenPair>();

        using var req = new HttpRequestMessage(HttpMethod.Get, "/api/v1/auth/me");
        req.Headers.Authorization = new("Bearer", pair!.accessToken);
        var me = await Client.SendAsync(req);
        me.StatusCode.Should().Be(System.Net.HttpStatusCode.OK);
        var profile = await me.Content.ReadFromJsonAsync<JsonElement>();
        profile.GetProperty("email").GetString().Should().Be(email);
    }

    [Fact]
    public async Task Me_without_token_returns_401()
    {
        var res = await Client.GetAsync("/api/v1/auth/me");
        res.StatusCode.Should().Be(System.Net.HttpStatusCode.Unauthorized);
    }

    [Fact]
    public async Task Login_unknown_email_and_wrong_password_both_return_generic_401()
    {
        var email = $"gen-{Guid.NewGuid():N}@x.io";
        await Client.PostAsJsonAsync("/api/v1/auth/register", new { email, password = "Password123!x" });

        var unknown = await Client.PostAsJsonAsync("/api/v1/auth/login",
            new { email = $"nobody-{Guid.NewGuid():N}@x.io", password = "Password123!x" });
        var wrong = await Client.PostAsJsonAsync("/api/v1/auth/login",
            new { email, password = "WrongPassword!9" });

        unknown.StatusCode.Should().Be(System.Net.HttpStatusCode.Unauthorized);
        wrong.StatusCode.Should().Be(System.Net.HttpStatusCode.Unauthorized);
        // Bodies must be identical (no enumeration via message).
        var ub = await unknown.Content.ReadAsStringAsync();
        var wb = await wrong.Content.ReadAsStringAsync();
        ub.Should().Be(wb);
    }

    [Fact]
    public async Task Refresh_rotates_old_token_becomes_invalid()
    {
        var email = $"rot-{Guid.NewGuid():N}@x.io";
        var reg = await (await Client.PostAsJsonAsync("/api/v1/auth/register",
            new { email, password = "Password123!x" })).Content.ReadFromJsonAsync<TokenPair>();

        var first = await Client.PostAsJsonAsync("/api/v1/auth/refresh", new { refreshToken = reg!.refreshToken });
        first.StatusCode.Should().Be(System.Net.HttpStatusCode.OK);
        var rotated = await first.Content.ReadFromJsonAsync<TokenPair>();
        rotated!.refreshToken.Should().NotBe(reg.refreshToken);

        // Old token is now invalid.
        var reuseOld = await Client.PostAsJsonAsync("/api/v1/auth/refresh", new { refreshToken = reg.refreshToken });
        reuseOld.StatusCode.Should().Be(System.Net.HttpStatusCode.Unauthorized);
    }

    [Fact]
    public async Task Refresh_reuse_of_revoked_token_revokes_whole_family()
    {
        var email = $"reuse-{Guid.NewGuid():N}@x.io";
        var reg = await (await Client.PostAsJsonAsync("/api/v1/auth/register",
            new { email, password = "Password123!x" })).Content.ReadFromJsonAsync<TokenPair>();

        // Rotate once: reg.refreshToken -> r2.
        var r2 = await (await Client.PostAsJsonAsync("/api/v1/auth/refresh",
            new { refreshToken = reg!.refreshToken })).Content.ReadFromJsonAsync<TokenPair>();

        // Attacker replays the already-rotated (revoked) original -> must revoke the family.
        var reuse = await Client.PostAsJsonAsync("/api/v1/auth/refresh", new { refreshToken = reg.refreshToken });
        reuse.StatusCode.Should().Be(System.Net.HttpStatusCode.Unauthorized);

        // The legitimate current token r2 is now also dead (family revoked).
        var afterFamilyRevoke = await Client.PostAsJsonAsync("/api/v1/auth/refresh",
            new { refreshToken = r2!.refreshToken });
        afterFamilyRevoke.StatusCode.Should().Be(System.Net.HttpStatusCode.Unauthorized);
    }

    [Fact]
    public async Task Logout_revokes_presented_refresh_token()
    {
        var email = $"lo-{Guid.NewGuid():N}@x.io";
        var reg = await (await Client.PostAsJsonAsync("/api/v1/auth/register",
            new { email, password = "Password123!x" })).Content.ReadFromJsonAsync<TokenPair>();

        var logout = await Client.PostAsJsonAsync("/api/v1/auth/logout", new { refreshToken = reg!.refreshToken });
        logout.StatusCode.Should().Be(System.Net.HttpStatusCode.NoContent);

        var afterLogout = await Client.PostAsJsonAsync("/api/v1/auth/refresh", new { refreshToken = reg.refreshToken });
        afterLogout.StatusCode.Should().Be(System.Net.HttpStatusCode.Unauthorized);
    }

    [Fact]
    public async Task LogoutAll_revokes_every_refresh_token_for_user()
    {
        var email = $"loall-{Guid.NewGuid():N}@x.io";
        var reg = await (await Client.PostAsJsonAsync("/api/v1/auth/register",
            new { email, password = "Password123!x" })).Content.ReadFromJsonAsync<TokenPair>();
        var login = await (await Client.PostAsJsonAsync("/api/v1/auth/login",
            new { email, password = "Password123!x" })).Content.ReadFromJsonAsync<TokenPair>();

        using var req = new HttpRequestMessage(HttpMethod.Post, "/api/v1/auth/logout-all");
        req.Headers.Authorization = new("Bearer", reg!.accessToken);
        (await Client.SendAsync(req)).StatusCode.Should().Be(System.Net.HttpStatusCode.NoContent);

        // Both refresh tokens are now dead.
        (await Client.PostAsJsonAsync("/api/v1/auth/refresh", new { refreshToken = reg.refreshToken }))
            .StatusCode.Should().Be(System.Net.HttpStatusCode.Unauthorized);
        (await Client.PostAsJsonAsync("/api/v1/auth/refresh", new { refreshToken = login!.refreshToken }))
            .StatusCode.Should().Be(System.Net.HttpStatusCode.Unauthorized);
    }

    [Fact]
    public async Task Account_locks_out_after_five_failed_logins()
    {
        var email = $"lock-{Guid.NewGuid():N}@x.io";
        await Client.PostAsJsonAsync("/api/v1/auth/register", new { email, password = "Password123!x" });

        for (var i = 0; i < 5; i++)
        {
            var bad = await Client.PostAsJsonAsync("/api/v1/auth/login", new { email, password = "Wrong!Password9" });
            bad.StatusCode.Should().Be(System.Net.HttpStatusCode.Unauthorized);
        }

        // Correct password now blocked due to lockout.
        var correctButLocked = await Client.PostAsJsonAsync("/api/v1/auth/login",
            new { email, password = "Password123!x" });
        correctButLocked.StatusCode.Should().Be(System.Net.HttpStatusCode.Unauthorized);

        // Assert at the data layer that the account is actually locked.
        await using var db = NewElevatedDbContext();
        var user = await db.Users.AsNoTracking().SingleAsync(u => u.NormalizedEmail == email.ToUpperInvariant());
        user.LockoutEnd.Should().NotBeNull();
        user.LockoutEnd!.Value.Should().BeAfter(DateTimeOffset.UtcNow);
    }

    [Fact]
    public async Task Auth_endpoints_return_429_when_rate_limit_exceeded()
    {
        // Hammer register beyond the per-window permit for one IP+email key.
        var email = $"rl-{Guid.NewGuid():N}@x.io";
        var statuses = new List<int>();
        for (var i = 0; i < 30; i++)
        {
            var res = await Client.PostAsJsonAsync("/api/v1/auth/register", new { email, password = "Password123!x" });
            statuses.Add((int)res.StatusCode);
        }
        statuses.Should().Contain(StatusCodes.Status429TooManyRequests);
    }

    [Fact]
    public async Task Me_with_token_signed_by_unknown_key_returns_401()
    {
        var email = $"badkey-{Guid.NewGuid():N}@x.io";
        var reg = await (await Client.PostAsJsonAsync("/api/v1/auth/register",
            new { email, password = "Password123!x" })).Content.ReadFromJsonAsync<TokenPair>();

        // Tamper: flip a character in the signature segment.
        var parts = reg!.accessToken.Split('.');
        parts[2] = parts[2].Length > 0
            ? (parts[2][0] == 'A' ? "B" : "A") + parts[2][1..]
            : "AAAA";
        var tampered = string.Join('.', parts);

        using var req = new HttpRequestMessage(HttpMethod.Get, "/api/v1/auth/me");
        req.Headers.Authorization = new("Bearer", tampered);
        (await Client.SendAsync(req)).StatusCode.Should().Be(System.Net.HttpStatusCode.Unauthorized);
    }

    [Fact]
    public async Task RegisterAsync_yields_authed_client_reaching_me()
    {
        var authed = await RegisterAsync();
        authed.UserId.Should().NotBe(Guid.Empty);

        var me = await authed.Client.GetAsync("/api/v1/auth/me");
        me.StatusCode.Should().Be(System.Net.HttpStatusCode.OK);

        var profile = await me.Content.ReadFromJsonAsync<JsonElement>();
        Guid.Parse(profile.GetProperty("id").GetString()!).Should().Be(authed.UserId);
    }

    [Fact]
    public async Task Refresh_reuse_logs_family_revoke_without_leaking_tokens()
    {
        TestLogSink.Clear();

        var email = $"log-{Guid.NewGuid():N}@x.io";
        var reg = await (await Client.PostAsJsonAsync("/api/v1/auth/register",
            new { email, password = "Password123!x" })).Content.ReadFromJsonAsync<TokenPair>();
        var r2 = await (await Client.PostAsJsonAsync("/api/v1/auth/refresh",
            new { refreshToken = reg!.refreshToken })).Content.ReadFromJsonAsync<TokenPair>();
        await Client.PostAsJsonAsync("/api/v1/auth/refresh", new { refreshToken = reg.refreshToken }); // reuse

        var log = TestLogSink.Messages;
        log.Should().Contain(m => m.Contains("auth.refresh.reuse_detected"));
        // Never log raw/hashed tokens or passwords.
        log.Should().NotContain(m => m.Contains(reg.refreshToken) || m.Contains(r2!.refreshToken) || m.Contains("Password123!x"));
    }
}
