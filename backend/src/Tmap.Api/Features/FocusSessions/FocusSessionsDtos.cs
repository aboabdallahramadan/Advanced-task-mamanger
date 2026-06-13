namespace Tmap.Api.Features.FocusSessions;

public sealed record CreateFocusSessionRequest(
    Guid? TaskId,
    string Project,
    DateTimeOffset StartedAt,
    DateTimeOffset EndedAt,
    int Minutes,
    DateOnly Date,
    Guid? Id = null);

public sealed record FocusSessionResponse(
    Guid Id,
    Guid? TaskId,
    string Project,
    DateTimeOffset StartedAt,
    DateTimeOffset EndedAt,
    int Minutes,
    DateOnly Date,
    DateTimeOffset CreatedAt,
    DateTimeOffset UpdatedAt);
