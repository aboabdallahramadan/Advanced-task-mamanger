namespace Tmap.Api.Infrastructure.Jwt;

public sealed class JwtOptions
{
    public const string SectionName = "Jwt";

    public string Issuer { get; set; } = "";
    public string Audience { get; set; } = "";
    public int AccessTokenMinutes { get; set; } = 15;
    public int RefreshTokenDays { get; set; } = 60;

    // kid used to SIGN new tokens.
    public string ActiveKeyId { get; set; } = "";

    // kid -> raw secret (>=256-bit / >=32 chars). Two active keys allow rotation
    // without mass logout: sign with ActiveKeyId, validate against ALL listed keys.
    public Dictionary<string, string> SigningKeys { get; set; } = new();
}
