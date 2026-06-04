namespace Tmap.Api.Infrastructure.Jwt;

public interface IJwtService
{
    // Signs with the configured ActiveKeyId. Returns the compact JWT and its absolute expiry.
    (string Token, DateTimeOffset ExpiresAt) CreateAccessToken(Guid userId);

    // 256-bit CSPRNG refresh token. Returns the RAW token (return to caller) and its
    // SHA-256 hex hash (the only value persisted).
    (string Raw, string Hash) CreateRefreshToken();

    // Hash an incoming raw refresh token for lookup/compare (SHA-256 hex).
    string HashRefreshToken(string raw);
}
