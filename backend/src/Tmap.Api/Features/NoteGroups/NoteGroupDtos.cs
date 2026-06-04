namespace Tmap.Api.Features.NoteGroups;

public sealed record CreateNoteGroupRequest(
    string Name,
    string? Emoji,
    Guid? ProjectId,
    string? Rank = null,
    Guid? Id = null);

public sealed record UpdateNoteGroupRequest(
    string? Name,
    string? Emoji,
    Guid? ProjectId,
    string? Rank);

public sealed record NoteGroupResponse(
    Guid Id,
    string Name,
    string Emoji,
    Guid? ProjectId,
    string Rank,
    DateTimeOffset CreatedAt,
    DateTimeOffset UpdatedAt);

public sealed record ReorderItem(Guid Id, string Rank);
