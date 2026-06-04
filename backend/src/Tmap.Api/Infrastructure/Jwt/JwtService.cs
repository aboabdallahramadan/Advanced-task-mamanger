using System.IdentityModel.Tokens.Jwt;
using System.Security.Claims;
using System.Security.Cryptography;
using System.Text;
using Microsoft.Extensions.Options;
using Microsoft.IdentityModel.Tokens;

namespace Tmap.Api.Infrastructure.Jwt;

public sealed class JwtService(IOptions<JwtOptions> options) : IJwtService
{
    private readonly JwtOptions _o = options.Value;

    public (string Token, DateTimeOffset ExpiresAt) CreateAccessToken(Guid userId)
    {
        var token = CreateAccessTokenWithKey(userId, _o.ActiveKeyId);
        var expiresAt = DateTimeOffset.UtcNow.AddMinutes(_o.AccessTokenMinutes);
        return (token, expiresAt);
    }

    // Exposed for tests / rotation drills: sign with a specific kid.
    public string CreateAccessTokenWithKey(Guid userId, string keyId)
    {
        if (!_o.SigningKeys.TryGetValue(keyId, out var secret))
        {
            throw new InvalidOperationException($"Unknown signing key id '{keyId}'.");
        }

        var now = DateTimeOffset.UtcNow;
        var key = new SymmetricSecurityKey(Encoding.UTF8.GetBytes(secret)) { KeyId = keyId };
        var creds = new SigningCredentials(key, SecurityAlgorithms.HmacSha256);

        var claims = new List<Claim>
        {
            new(JwtRegisteredClaimNames.Sub, userId.ToString()),
            new(JwtRegisteredClaimNames.Jti, Guid.NewGuid().ToString()),
            new(JwtRegisteredClaimNames.Iat, now.ToUnixTimeSeconds().ToString(), ClaimValueTypes.Integer64),
        };

        var jwt = new JwtSecurityToken(
            issuer: _o.Issuer,
            audience: _o.Audience,
            claims: claims,
            notBefore: now.UtcDateTime,
            expires: now.AddMinutes(_o.AccessTokenMinutes).UtcDateTime,
            signingCredentials: creds);
        jwt.Header["kid"] = keyId;

        return new JwtSecurityTokenHandler().WriteToken(jwt);
    }

    public (string Raw, string Hash) CreateRefreshToken()
    {
        var bytes = RandomNumberGenerator.GetBytes(32); // 256-bit CSPRNG
        var raw = Base64UrlEncode(bytes);
        return (raw, HashRefreshToken(raw));
    }

    public string HashRefreshToken(string raw)
    {
        var hash = SHA256.HashData(Encoding.UTF8.GetBytes(raw));
        return Convert.ToHexString(hash); // uppercase hex
    }

    private static string Base64UrlEncode(byte[] b) =>
        Convert.ToBase64String(b).TrimEnd('=').Replace('+', '-').Replace('/', '_');
}
