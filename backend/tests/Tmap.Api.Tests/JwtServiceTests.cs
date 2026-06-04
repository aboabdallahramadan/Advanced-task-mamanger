using System.IdentityModel.Tokens.Jwt;
using System.Security.Claims;
using FluentAssertions;
using Microsoft.Extensions.Options;
using Microsoft.IdentityModel.Tokens;
using Tmap.Api.Infrastructure.Jwt;
using Xunit;

namespace Tmap.Api.Tests;

public class JwtServiceTests
{
    private static JwtService Make(out JwtOptions opts)
    {
        opts = new JwtOptions
        {
            Issuer = "tmap-test",
            Audience = "tmap-clients",
            AccessTokenMinutes = 15,
            RefreshTokenDays = 60,
            ActiveKeyId = "k1",
            SigningKeys = new()
            {
                ["k1"] = "0123456789ABCDEF0123456789ABCDEF",          // 32 bytes
                ["k2"] = "FEDCBA9876543210FEDCBA9876543210",          // second active key
            },
        };
        return new JwtService(Options.Create(opts));
    }

    [Fact]
    public void Access_token_has_kid_header_and_required_claims_no_email()
    {
        var svc = Make(out var opts);
        var userId = Guid.NewGuid();

        var (jwt, expiresAt) = svc.CreateAccessToken(userId);

        var token = new JwtSecurityTokenHandler().ReadJwtToken(jwt);
        token.Header.Kid.Should().Be("k1");
        token.Header.Alg.Should().Be("HS256");
        token.Subject.Should().Be(userId.ToString());
        token.Issuer.Should().Be("tmap-test");
        token.Audiences.Should().Contain("tmap-clients");
        token.Claims.Should().Contain(c => c.Type == "jti");
        token.Claims.Should().Contain(c => c.Type == JwtRegisteredClaimNames.Iat);
        token.Claims.Should().NotContain(c => c.Type == JwtRegisteredClaimNames.Email);
        expiresAt.Should().BeCloseTo(DateTimeOffset.UtcNow.AddMinutes(15), TimeSpan.FromSeconds(5));
    }

    [Fact]
    public void Token_signed_with_secondary_key_validates_against_both_keys()
    {
        var svc = Make(out var opts);
        // Build params that accept either key (kid-based resolution).
        var tvp = JwtBearerSetup.BuildValidationParameters(opts);

        // Force-sign with k2 to simulate post-rotation tokens still being accepted.
        var jwt = svc.CreateAccessTokenWithKey(Guid.NewGuid(), "k2");
        var handler = new JwtSecurityTokenHandler();

        var act = () => handler.ValidateToken(jwt, tvp, out _);
        act.Should().NotThrow();
    }

    [Fact]
    public void Alg_none_token_is_rejected_by_validation_parameters()
    {
        var svc = Make(out var opts);
        var tvp = JwtBearerSetup.BuildValidationParameters(opts);
        // Hand-craft an unsigned token: header alg=none, valid claims.
        var none =
            "eyJhbGciOiJub25lIiwidHlwIjoiSldUIn0." +
            System.Convert.ToBase64String(System.Text.Encoding.UTF8.GetBytes(
                $$"""{"sub":"{{Guid.NewGuid()}}","iss":"tmap-test","aud":"tmap-clients"}""")).TrimEnd('=').Replace('+', '-').Replace('/', '_') +
            ".";
        var handler = new JwtSecurityTokenHandler();
        var act = () => handler.ValidateToken(none, tvp, out _);
        act.Should().Throw<SecurityTokenException>();
    }
}
