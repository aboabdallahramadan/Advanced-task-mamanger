using System.Text;
using Microsoft.IdentityModel.Tokens;

namespace Tmap.Api.Infrastructure.Jwt;

public static class JwtBearerSetup
{
    public static TokenValidationParameters BuildValidationParameters(JwtOptions o) => new()
    {
        ValidateIssuer = true,
        ValidIssuer = o.Issuer,
        ValidateAudience = true,
        ValidAudience = o.Audience,
        ValidateLifetime = true,
        ValidateIssuerSigningKey = true,
        ValidAlgorithms = [SecurityAlgorithms.HmacSha256], // pin HS256: blocks alg-confusion / alg:none
        ClockSkew = TimeSpan.FromMinutes(2),
        // Resolve the signing key by the token's kid against ALL active keys (rotation).
        IssuerSigningKeyResolver = (token, securityToken, kid, parameters) =>
            o.SigningKeys.TryGetValue(kid ?? "", out var secret)
                ? [new SymmetricSecurityKey(Encoding.UTF8.GetBytes(secret)) { KeyId = kid }]
                : o.SigningKeys.Select(kv =>
                    (SecurityKey)new SymmetricSecurityKey(Encoding.UTF8.GetBytes(kv.Value)) { KeyId = kv.Key }),
        NameClaimType = "sub",
    };
}
