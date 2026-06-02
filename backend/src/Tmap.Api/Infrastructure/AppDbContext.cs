using Microsoft.EntityFrameworkCore;

namespace Tmap.Api.Infrastructure;

/// <summary>
/// Placeholder EF Core context. Real entities, RLS triggers, and query filters are added in P1.
/// </summary>
public class AppDbContext(DbContextOptions<AppDbContext> options) : DbContext(options)
{
    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        base.OnModelCreating(modelBuilder);
    }
}
