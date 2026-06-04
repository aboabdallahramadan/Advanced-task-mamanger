namespace Tmap.Api.Features.Subtasks;

public sealed record CreateSubtaskRequest(string Title);

public sealed record UpdateSubtaskRequest(string? Title, bool? Completed, int? SortOrder);

public sealed record SubtaskResponse(
    Guid Id,
    Guid TaskId,
    string Title,
    bool Completed,
    int SortOrder,
    DateTimeOffset CreatedAt,
    DateTimeOffset UpdatedAt);
