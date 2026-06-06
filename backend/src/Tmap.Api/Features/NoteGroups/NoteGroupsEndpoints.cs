using Microsoft.AspNetCore.Http.HttpResults;
using Microsoft.EntityFrameworkCore;
using Tmap.Api.Common;
using Tmap.Api.Common.Validation;
using Tmap.Api.Infrastructure;
using Tmap.Api.Infrastructure.Entities;

namespace Tmap.Api.Features.NoteGroups;

public static class NoteGroupsEndpoints
{
    public static RouteGroupBuilder MapNoteGroups(this IEndpointRouteBuilder app)
    {
        var group = app.MapGroup("note-groups").RequireAuthorization();

        group.MapGet("/", GetAll);
        group.MapPost("/", Create).AddEndpointFilter<ValidationFilter<CreateNoteGroupRequest>>();
        group.MapPatch("/{id:guid}", Update).AddEndpointFilter<ValidationFilter<UpdateNoteGroupRequest>>();
        group.MapDelete("/{id:guid}", Delete);

        return group;
    }

    private static async Task<Ok<List<NoteGroupResponse>>> GetAll(
        Guid? projectId,
        AppDbContext db,
        ICurrentUser currentUser,
        CancellationToken ct)
    {
        var query = db.NoteGroups.AsQueryable();
        if (projectId is { } pid)
        {
            query = query.Where(g => g.ProjectId == pid);
        }

        var groups = await query
            .OrderBy(g => EF.Functions.Collate(g.Rank, "C"))
            .ToListAsync(ct);

        return TypedResults.Ok(groups.Select(ToResponse).ToList());
    }

    private static async Task<Results<Created<NoteGroupResponse>, ValidationProblem>> Create(
        CreateNoteGroupRequest req,
        AppDbContext db,
        ICurrentUser currentUser,
        CancellationToken ct)
    {
        // WRITE-side ownership: tenant-filtered Projects means a non-match = not the caller's.
        if (req.ProjectId is { } pid && !await db.Projects.AnyAsync(p => p.Id == pid, ct))
        {
            return ProjectNotOwned();
        }

        var rank = !string.IsNullOrEmpty(req.Rank)
            ? req.Rank
            : await NextRankAsync(db, currentUser.Id, req.ProjectId, ct);

        var group = new NoteGroup
        {
            Id = req.Id is { } id && id != Guid.Empty ? id : Guid.CreateVersion7(),
            UserId = currentUser.Id,
            Name = req.Name,
            Emoji = req.Emoji ?? "📝",
            ProjectId = req.ProjectId,
            Rank = rank,
        };

        db.NoteGroups.Add(group);
        await db.SaveChangesAsync(ct);

        return TypedResults.Created($"/api/v1/note-groups/{group.Id}", ToResponse(group));
    }

    private static async Task<Results<Ok<NoteGroupResponse>, NotFound, ValidationProblem>> Update(
        Guid id,
        UpdateNoteGroupRequest req,
        AppDbContext db,
        ICurrentUser currentUser,
        CancellationToken ct)
    {
        var group = await db.NoteGroups.FirstOrDefaultAsync(g => g.Id == id, ct);
        if (group is null)
        {
            return TypedResults.NotFound();
        }

        // WRITE-side ownership: validate the (re)assigned project belongs to the caller.
        if (req.ProjectId is { } pid && !await db.Projects.AnyAsync(p => p.Id == pid, ct))
        {
            return ProjectNotOwned();
        }

        if (req.Name is not null)
        {
            group.Name = req.Name;
        }

        if (req.Emoji is not null)
        {
            group.Emoji = req.Emoji;
        }

        if (req.ProjectId is not null)
        {
            group.ProjectId = req.ProjectId;
        }

        if (!string.IsNullOrEmpty(req.Rank))
        {
            group.Rank = req.Rank;
        }

        await db.SaveChangesAsync(ct);

        return TypedResults.Ok(ToResponse(group));
    }

    private static async Task<Results<NoContent, NotFound>> Delete(
        Guid id,
        AppDbContext db,
        ICurrentUser currentUser,
        CancellationToken ct)
    {
        var group = await db.NoteGroups.FirstOrDefaultAsync(g => g.Id == id, ct);
        if (group is null)
        {
            return TypedResults.NotFound();
        }

        var now = DateTimeOffset.UtcNow;

        group.DeletedAt = now;
        await db.SaveChangesAsync(ct);

        // Cascade-tombstone the group's live notes.
        await db.Notes
            .Where(n => n.GroupId == id)
            .ExecuteUpdateAsync(s => s.SetProperty(n => n.DeletedAt, now), ct);

        return TypedResults.NoContent();
    }

    // --- helpers ---

    /// <summary>RFC 9457 400 for a ProjectId that is not owned by the caller (tenant-filtered absence).</summary>
    private static ValidationProblem ProjectNotOwned() =>
        TypedResults.ValidationProblem(new Dictionary<string, string[]>
        {
            ["projectId"] = ["projectId does not reference one of your projects."],
        });

    /// <summary>Append a new rank after the user's current max note-group rank for the given project container.</summary>
    private static async Task<string> NextRankAsync(AppDbContext db, Guid userId, Guid? projectId, CancellationToken ct)
    {
        var maxRank = await db.NoteGroups
            .Where(g => g.UserId == userId && g.ProjectId == projectId)
            .OrderByDescending(g => g.Rank)
            .Select(g => g.Rank)
            .FirstOrDefaultAsync(ct);
        return Ranking.RankAfter(maxRank);
    }

    private static NoteGroupResponse ToResponse(NoteGroup g) => new(
        g.Id,
        g.Name,
        g.Emoji,
        g.ProjectId,
        g.Rank,
        g.CreatedAt,
        g.UpdatedAt);
}
