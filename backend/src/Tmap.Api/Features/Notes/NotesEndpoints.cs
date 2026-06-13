using Microsoft.AspNetCore.Http.HttpResults;
using Microsoft.EntityFrameworkCore;
using Tmap.Api.Common;
using Tmap.Api.Common.Validation;
using Tmap.Api.Infrastructure;
using Tmap.Api.Infrastructure.Entities;

namespace Tmap.Api.Features.Notes;

public static class NotesEndpoints
{
    public static RouteGroupBuilder MapNotes(this IEndpointRouteBuilder app)
    {
        var group = app.MapGroup("notes").RequireAuthorization();

        group.MapGet("/", GetAll);
        group.MapGet("/{id:guid}", GetById);
        group.MapPost("/", Create).AddEndpointFilter<ValidationFilter<CreateNoteRequest>>();

        // reorder must be registered BEFORE /{id:guid} so the literal segment wins.
        group.MapPatch("/reorder", Reorder)
            .AddEndpointFilter<ValidationFilter<IReadOnlyList<ReorderItem>>>();

        group.MapPatch("/{id:guid}", Update).AddEndpointFilter<ValidationFilter<UpdateNoteRequest>>();
        group.MapDelete("/{id:guid}", Delete);

        return group;
    }

    private static async Task<Ok<List<NoteResponse>>> GetAll(
        Guid? groupId,
        Guid? projectId,
        AppDbContext db,
        ICurrentUser currentUser,
        CancellationToken ct)
    {
        var query = db.Notes.AsQueryable();

        if (groupId is { } gid)
        {
            query = query.Where(n => n.GroupId == gid);
        }

        if (projectId is { } pid)
        {
            query = query.Where(n => n.ProjectId == pid);
        }

        var notes = await query
            .OrderBy(n => EF.Functions.Collate(n.Rank, "C"))
            .ToListAsync(ct);

        return TypedResults.Ok(notes.Select(ToResponse).ToList());
    }

    private static async Task<Results<Ok<NoteResponse>, NotFound>> GetById(
        Guid id,
        AppDbContext db,
        ICurrentUser currentUser,
        CancellationToken ct)
    {
        var note = await db.Notes.FirstOrDefaultAsync(n => n.Id == id, ct);
        if (note is null)
        {
            return TypedResults.NotFound();
        }

        return TypedResults.Ok(ToResponse(note));
    }

    private static async Task<Results<Created<NoteResponse>, Ok<NoteResponse>, ValidationProblem>> Create(
        CreateNoteRequest req,
        AppDbContext db,
        ICurrentUser currentUser,
        CancellationToken ct)
    {
        // Idempotent replay: an existing owned note with this id (live or tombstoned) → 200 + its DTO.
        if (req.Id is { } reqId && reqId != Guid.Empty)
        {
            var existing = await CreateConflict.FindExistingByIdAsync(
                db.Notes, n => n.Id == reqId, ct);
            if (existing is not null)
            {
                return TypedResults.Ok(ToResponse(existing));
            }
        }

        // WRITE-side ownership: tenant-filtered NoteGroups/Projects mean a non-match = not the caller's.
        if (req.GroupId is { } gid && !await db.NoteGroups.AnyAsync(g => g.Id == gid, ct))
        {
            return GroupNotOwned();
        }

        if (req.ProjectId is { } pid && !await db.Projects.AnyAsync(p => p.Id == pid, ct))
        {
            return ProjectNotOwned();
        }

        var rank = !string.IsNullOrEmpty(req.Rank)
            ? req.Rank
            : await NextRankAsync(db, currentUser.Id, req.GroupId, req.ProjectId, ct);

        var note = new Note
        {
            Id = req.Id is { } id && id != Guid.Empty ? id : Guid.CreateVersion7(),
            UserId = currentUser.Id,
            GroupId = req.GroupId,
            ProjectId = req.ProjectId,
            Title = req.Title,
            Content = req.Content ?? string.Empty,
            Rank = rank,
        };

        db.Notes.Add(note);
        await db.SaveChangesAsync(ct);

        return TypedResults.Created($"/api/v1/notes/{note.Id}", ToResponse(note));
    }

    private static async Task<Results<Ok<NoteResponse>, NotFound, ValidationProblem>> Update(
        Guid id,
        UpdateNoteRequest req,
        AppDbContext db,
        ICurrentUser currentUser,
        CancellationToken ct)
    {
        var note = await db.Notes.FirstOrDefaultAsync(n => n.Id == id, ct);
        if (note is null)
        {
            return TypedResults.NotFound();
        }

        // WRITE-side ownership: validate the (re)assigned group/project belongs to the caller.
        if (req.GroupId is { } gid && !await db.NoteGroups.AnyAsync(g => g.Id == gid, ct))
        {
            return GroupNotOwned();
        }

        if (req.ProjectId is { } pid && !await db.Projects.AnyAsync(p => p.Id == pid, ct))
        {
            return ProjectNotOwned();
        }

        if (req.Title is not null)
        {
            note.Title = req.Title;
        }

        if (req.Content is not null)
        {
            note.Content = req.Content;
        }

        if (req.GroupId is not null)
        {
            note.GroupId = req.GroupId;
        }

        if (req.ProjectId is not null)
        {
            note.ProjectId = req.ProjectId;
        }

        if (!string.IsNullOrEmpty(req.Rank))
        {
            note.Rank = req.Rank;
        }

        await db.SaveChangesAsync(ct);

        return TypedResults.Ok(ToResponse(note));
    }

    private static async Task<Results<NoContent, NotFound>> Delete(
        Guid id,
        AppDbContext db,
        ICurrentUser currentUser,
        CancellationToken ct)
    {
        var note = await db.Notes.FirstOrDefaultAsync(n => n.Id == id, ct);
        if (note is null)
        {
            return TypedResults.NotFound();
        }

        note.DeletedAt = DateTimeOffset.UtcNow;
        await db.SaveChangesAsync(ct);

        return TypedResults.NoContent();
    }

    private static async Task<NoContent> Reorder(
        IReadOnlyList<ReorderItem> items,
        AppDbContext db,
        ICurrentUser currentUser,
        CancellationToken ct)
    {
        foreach (var item in items)
        {
            await db.Notes
                .Where(n => n.Id == item.Id && n.UserId == currentUser.Id)
                .ExecuteUpdateAsync(s => s.SetProperty(n => n.Rank, item.Rank), ct);
        }

        return TypedResults.NoContent();
    }

    // --- helpers ---

    /// <summary>RFC 9457 400 for a ProjectId that is not owned by the caller (tenant-filtered absence).</summary>
    private static ValidationProblem ProjectNotOwned() =>
        TypedResults.ValidationProblem(new Dictionary<string, string[]>
        {
            ["projectId"] = ["projectId does not reference one of your projects."],
        });

    /// <summary>RFC 9457 400 for a GroupId that is not owned by the caller (tenant-filtered absence).</summary>
    private static ValidationProblem GroupNotOwned() =>
        TypedResults.ValidationProblem(new Dictionary<string, string[]>
        {
            ["groupId"] = ["groupId does not reference one of your note groups."],
        });

    /// <summary>Append a new rank after the user's current max note rank for the given container.</summary>
    private static async Task<string> NextRankAsync(
        AppDbContext db,
        Guid userId,
        Guid? groupId,
        Guid? projectId,
        CancellationToken ct)
    {
        var maxRank = await db.Notes
            .Where(n => n.UserId == userId && n.GroupId == groupId && n.ProjectId == projectId)
            .OrderByDescending(n => n.Rank)
            .Select(n => n.Rank)
            .FirstOrDefaultAsync(ct);
        return Ranking.RankAfter(maxRank);
    }

    private static NoteResponse ToResponse(Note n) => new(
        n.Id,
        n.GroupId,
        n.ProjectId,
        n.Title,
        n.Content,
        n.Rank,
        n.CreatedAt,
        n.UpdatedAt);
}
