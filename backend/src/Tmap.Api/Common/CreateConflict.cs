using Microsoft.EntityFrameworkCore;
using Tmap.Api.Infrastructure;

namespace Tmap.Api.Common;

/// <summary>
/// Idempotent-create support: a replayed create (same client id) must return the existing
/// row instead of inserting a duplicate or 500'ing. Looks up by id with the soft-delete
/// filter OFF (a tombstoned row also counts as "exists"; the client's recovery reconciles
/// the tombstone). The Tenant filter stays ON, so a foreign id is treated as "not found"
/// and the create proceeds normally (the ownership interceptor stamps the caller's id).
/// </summary>
public static class CreateConflict
{
    public static async Task<TEntity?> FindExistingByIdAsync<TEntity>(
        IQueryable<TEntity> set,
        System.Linq.Expressions.Expression<Func<TEntity, bool>> idMatch,
        CancellationToken ct)
        where TEntity : class
    {
        return await set
            .IgnoreQueryFilters([AppDbContext.SoftDeleteFilter])
            .FirstOrDefaultAsync(idMatch, ct);
    }
}
