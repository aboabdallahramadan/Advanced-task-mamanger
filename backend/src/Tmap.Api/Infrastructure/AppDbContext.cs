using Microsoft.EntityFrameworkCore;
using Tmap.Api.Common;

namespace Tmap.Api.Infrastructure;

/// <summary>
/// Application database context. Scoped lifetime. Placeholder in P0 (no entities yet);
/// P1 adds Identity, the synced entities, RLS, the change_seq trigger, named query filters,
/// and the ownership/connection interceptors. Do not add domain logic here in P0.
/// </summary>
public class AppDbContext(DbContextOptions<AppDbContext> options, ICurrentUser currentUser)
    : DbContext(options)
{
    // Retained so P1's tenant filter and ownership interceptor can read the current user at
    // query/save time. Suppress the unused warning until P1 consumes it.
#pragma warning disable IDE0052
    private readonly ICurrentUser _currentUser = currentUser;
#pragma warning restore IDE0052

    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        base.OnModelCreating(modelBuilder);
        // Entities, conversions, filters, and indexes are configured in P1.
    }
}
