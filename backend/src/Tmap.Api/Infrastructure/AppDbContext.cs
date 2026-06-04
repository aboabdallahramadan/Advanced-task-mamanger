using Microsoft.AspNetCore.Identity;
using Microsoft.AspNetCore.Identity.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore;
using Tmap.Api.Common;
using Tmap.Api.Infrastructure.Entities;

namespace Tmap.Api.Infrastructure;

public class AppDbContext(DbContextOptions<AppDbContext> options, ICurrentUser currentUser)
    : IdentityDbContext<ApplicationUser, IdentityRole<Guid>, Guid>(options)
{
    public const string TenantFilter = "Tenant";
    public const string SoftDeleteFilter = "SoftDelete";

    private readonly ICurrentUser _currentUser = currentUser;

    public DbSet<TaskItem> Tasks => Set<TaskItem>();
    public DbSet<Subtask> Subtasks => Set<Subtask>();
    public DbSet<Project> Projects => Set<Project>();
    public DbSet<NoteGroup> NoteGroups => Set<NoteGroup>();
    public DbSet<Note> Notes => Set<Note>();
    public DbSet<RecurrenceRule> RecurrenceRules => Set<RecurrenceRule>();
    public DbSet<RecurrenceException> RecurrenceExceptions => Set<RecurrenceException>();
    public DbSet<FocusSession> FocusSessions => Set<FocusSession>();
    public DbSet<DailyPlan> DailyPlans => Set<DailyPlan>();
    public DbSet<UserSetting> UserSettings => Set<UserSetting>();
    public DbSet<RefreshToken> RefreshTokens => Set<RefreshToken>();

    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        base.OnModelCreating(modelBuilder);

        // Composite-key entities have no single Id — EF cannot infer their PK.
        // Declare composite keys here; full mapping arrives in Task P1-5.
        modelBuilder.Entity<DailyPlan>().HasKey(e => new { e.UserId, e.Date });
        modelBuilder.Entity<UserSetting>().HasKey(e => new { e.UserId, e.Key });

        // Full entity configuration added in Task P1-5.
    }
}
