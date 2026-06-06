namespace Tmap.Api.Features.Notes;

public sealed record CreateNoteRequest(
    Guid? GroupId,
    Guid? ProjectId,
    string Title,
    string? Content,
    string? Rank = null,
    Guid? Id = null);

public sealed record UpdateNoteRequest(
    Guid? GroupId,
    Guid? ProjectId,
    string? Title,
    string? Content,
    string? Rank);

public sealed record NoteResponse(
    Guid Id,
    Guid? GroupId,
    Guid? ProjectId,
    string Title,
    string Content,
    string Rank,
    DateTimeOffset CreatedAt,
    DateTimeOffset UpdatedAt);

public sealed record ReorderItem(Guid Id, string Rank);
