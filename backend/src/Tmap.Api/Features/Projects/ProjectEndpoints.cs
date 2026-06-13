using Microsoft.AspNetCore.Http.HttpResults;
using Microsoft.EntityFrameworkCore;
using Tmap.Api.Common;
using Tmap.Api.Common.Validation;
using Tmap.Api.Infrastructure;
using Tmap.Api.Infrastructure.Entities;

namespace Tmap.Api.Features.Projects;

public static class ProjectEndpoints
{
    public static RouteGroupBuilder MapProjects(this IEndpointRouteBuilder app)
    {
        var group = app.MapGroup("projects").RequireAuthorization();

        group.MapGet("/", GetAll);
        group.MapPost("/", Create).AddEndpointFilter<ValidationFilter<CreateProjectRequest>>();

        // reorder must be registered BEFORE /{id:guid} so the literal segment wins.
        group.MapPatch("/reorder", Reorder)
            .AddEndpointFilter<ValidationFilter<IReadOnlyList<ReorderItem>>>();

        group.MapPatch("/{id:guid}", Update).AddEndpointFilter<ValidationFilter<UpdateProjectRequest>>();
        group.MapDelete("/{id:guid}", Delete);

        return group;
    }

    private static async Task<Ok<List<ProjectResponse>>> GetAll(AppDbContext db, CancellationToken ct)
    {
        // Sort using C locale (byte-order) so rank keys are ordered by their ASCII values,
        // matching the ordinal ordering assumed by the rank key scheme.
        var projects = await db.Projects
            .OrderBy(p => EF.Functions.Collate(p.Rank, "C"))
            .ThenBy(p => p.Id)
            .Select(p => ToResponse(p))
            .ToListAsync(ct);

        return TypedResults.Ok(projects);
    }

    private static async Task<Results<Created<ProjectResponse>, Ok<ProjectResponse>, ProblemHttpResult>> Create(
        CreateProjectRequest req,
        AppDbContext db,
        ICurrentUser currentUser,
        CancellationToken ct)
    {
        // Idempotent replay: an existing owned project with this id (live or tombstoned) → 200 + its DTO.
        if (req.Id is { } reqId && reqId != Guid.Empty)
        {
            var existingById = await CreateConflict.FindExistingByIdAsync(
                db.Projects, p => p.Id == reqId, ct);
            if (existingById is not null)
            {
                return TypedResults.Ok(ToResponse(existingById));
            }
        }

        // Unique-name pre-check among the caller's LIVE projects (mirrors the partial unique index).
        var nameClashId = await db.Projects
            .Where(p => p.Name == req.Name)
            .Select(p => (Guid?)p.Id)
            .FirstOrDefaultAsync(ct);
        if (nameClashId is { } clashId)
        {
            return NameConflict(clashId);
        }

        // Rank is optional — when omitted, server appends after the user's current max rank.
        var rank = !string.IsNullOrEmpty(req.Rank)
            ? req.Rank
            : await NextRankAsync(db, currentUser.Id, ct);

        var project = new Project
        {
            Id = req.Id is { } id && id != Guid.Empty ? id : Guid.CreateVersion7(),
            UserId = currentUser.Id,
            Name = req.Name,
            Color = req.Color,
            Emoji = req.Emoji,
            Rank = rank,
            ActualTimeMinutes = 0,
        };

        db.Projects.Add(project);
        await db.SaveChangesAsync(ct);

        return TypedResults.Created($"/api/v1/projects/{project.Id}", ToResponse(project));
    }

    private static async Task<Results<Ok<ProjectResponse>, NotFound, ProblemHttpResult>> Update(
        Guid id,
        UpdateProjectRequest req,
        AppDbContext db,
        CancellationToken ct)
    {
        var project = await db.Projects.FirstOrDefaultAsync(p => p.Id == id, ct);
        if (project is null)
        {
            return TypedResults.NotFound();
        }

        if (req.Name is not null && req.Name != project.Name)
        {
            // Rename must not collide with another LIVE project of the same user.
            var clashId = await db.Projects
                .Where(p => p.Name == req.Name && p.Id != id)
                .Select(p => (Guid?)p.Id)
                .FirstOrDefaultAsync(ct);
            if (clashId is { } existing)
            {
                return NameConflict(existing);
            }

            project.Name = req.Name;
        }

        if (req.Color is not null)
        {
            project.Color = req.Color;
        }

        if (req.Emoji is not null)
        {
            project.Emoji = req.Emoji;
        }

        if (req.Rank is not null)
        {
            project.Rank = req.Rank;
        }

        if (req.ActualTimeMinutes is { } am)
        {
            project.ActualTimeMinutes = am;
        }

        await db.SaveChangesAsync(ct);

        return TypedResults.Ok(ToResponse(project));
    }

    private static async Task<Results<NoContent, NotFound>> Delete(
        Guid id,
        AppDbContext db,
        ICurrentUser currentUser,
        CancellationToken ct)
    {
        var project = await db.Projects.FirstOrDefaultAsync(p => p.Id == id, ct);
        if (project is null)
        {
            return TypedResults.NotFound();
        }

        var now = DateTimeOffset.UtcNow;

        // Soft-delete the project itself.
        project.DeletedAt = now;
        await db.SaveChangesAsync(ct);

        // Set-null cascade: live note_groups, notes, and tasks owned by this user
        // that reference the project lose the reference (no-op tombstoning of children).
        await db.NoteGroups
            .Where(g => g.ProjectId == id)
            .ExecuteUpdateAsync(s => s.SetProperty(g => g.ProjectId, (Guid?)null), ct);

        await db.Notes
            .Where(n => n.ProjectId == id)
            .ExecuteUpdateAsync(s => s.SetProperty(n => n.ProjectId, (Guid?)null), ct);

        await db.Tasks
            .Where(t => t.ProjectId == id)
            .ExecuteUpdateAsync(s => s.SetProperty(t => t.ProjectId, (Guid?)null), ct);

        return TypedResults.NoContent();
    }

    private static async Task<NoContent> Reorder(
        IReadOnlyList<ReorderItem> items,
        AppDbContext db,
        CancellationToken ct)
    {
        foreach (var item in items)
        {
            await db.Projects
                .Where(p => p.Id == item.Id)
                .ExecuteUpdateAsync(s => s.SetProperty(p => p.Rank, item.Rank), ct);
        }

        return TypedResults.NoContent();
    }

    // --- helpers ---

    /// <summary>Append a new rank after the user's current max project rank.</summary>
    private static async Task<string> NextRankAsync(AppDbContext db, Guid userId, CancellationToken ct)
    {
        var maxRank = await db.Projects
            .Where(p => p.UserId == userId)
            .OrderByDescending(p => p.Rank)
            .Select(p => p.Rank)
            .FirstOrDefaultAsync(ct);
        return Ranking.RankAfter(maxRank);
    }

    /// <summary>RFC 9457 409 for a duplicate live project name; carries the existing row's id
    /// in extensions.existingId so the client can adopt-existing (SP3 §3.3).</summary>
    private static ProblemHttpResult NameConflict(Guid existingId) =>
        TypedResults.Problem(
            title: "A project with this name already exists.",
            statusCode: StatusCodes.Status409Conflict,
            extensions: new Dictionary<string, object?> { ["existingId"] = existingId });

    private static ProjectResponse ToResponse(Project p) => new(
        p.Id,
        p.Name,
        p.Color,
        p.Emoji,
        p.Rank,
        p.ActualTimeMinutes,
        p.CreatedAt,
        p.UpdatedAt);
}
