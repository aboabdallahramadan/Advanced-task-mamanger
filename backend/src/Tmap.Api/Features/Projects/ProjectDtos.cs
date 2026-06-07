namespace Tmap.Api.Features.Projects;

public sealed record CreateProjectRequest(
    string Name,
    string Color,
    string Emoji,
    string? Rank = null,
    Guid? Id = null);

public sealed record UpdateProjectRequest(
    string? Name = null,
    string? Color = null,
    string? Emoji = null,
    string? Rank = null,
    int? ActualTimeMinutes = null);

public sealed record ProjectResponse(
    Guid Id,
    string Name,
    string Color,
    string Emoji,
    string Rank,
    int ActualTimeMinutes,
    DateTimeOffset CreatedAt,
    DateTimeOffset UpdatedAt);

public sealed record ReorderItem(Guid Id, string Rank);
