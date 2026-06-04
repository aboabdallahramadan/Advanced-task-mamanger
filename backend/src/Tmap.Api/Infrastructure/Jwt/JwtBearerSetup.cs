using System.Text;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.Extensions.Options;
using Microsoft.IdentityModel.Tokens;

namespace Tmap.Api.Infrastructure.Jwt;

public static class JwtBearerSetup
{
    // Wires JWT bearer authentication + authorization so the pipeline has the
    // auth middleware that RequireAuthorization() / [Authorize] endpoints depend on.
    // JwtOptions is read via IOptions (resolved after configuration is fully built) so
    // test-harness config overrides — including signing keys — are honored.
    public static IServiceCollection AddTmapJwtAuth(this IServiceCollection services, IConfiguration configuration)
    {
        services.AddAuthentication(JwtBearerDefaults.AuthenticationScheme).AddJwtBearer();

        services.AddOptions<JwtBearerOptions>(JwtBearerDefaults.AuthenticationScheme)
            .Configure<IOptions<JwtOptions>>((bearer, jwt) =>
            {
                bearer.MapInboundClaims = false; // keep the raw 'sub' claim instead of remapping it
                bearer.TokenValidationParameters = BuildValidationParameters(jwt.Value);
            });

        services.AddAuthorization();

        return services;
    }

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
