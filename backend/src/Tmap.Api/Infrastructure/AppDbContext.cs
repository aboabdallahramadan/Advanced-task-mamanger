using Microsoft.AspNetCore.Identity;
using Microsoft.AspNetCore.Identity.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using Tmap.Api.Common;
using Tmap.Api.Infrastructure.Entities;

namespace Tmap.Api.Infrastructure;

public sealed class AppDbContext(DbContextOptions<AppDbContext> options, ICurrentUser currentUser)
    : IdentityDbContext<ApplicationUser, IdentityRole<Guid>, Guid>(options)
{
    public const string TenantFilter = "Tenant";
    public const string SoftDeleteFilter = "SoftDelete";

    private readonly ICurrentUser _currentUser = currentUser;

    // False when the context is running as the system/elevated user (e.g. in tests for arrange/assert).
    // Captured once at construction time so EF Core can translate the filter to SQL.
    private readonly bool _isTenantFilterActive = currentUser is not SystemCurrentUser;

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
    public DbSet<SyncPurgeState> SyncPurgeState => Set<SyncPurgeState>();

    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        base.OnModelCreating(modelBuilder);

        // ---- TaskItem ----
        modelBuilder.Entity<TaskItem>(b =>
        {
            b.ToTable("tasks");
            b.HasKey(x => x.Id);
            b.Property(x => x.Status).HasConversion<string>().HasColumnType("text");
            b.Property(x => x.Labels).HasColumnType("jsonb");
            b.Property(x => x.Rank).IsRequired();
            b.HasOne(x => x.Project)
                .WithMany()
                .HasForeignKey(x => x.ProjectId)
                .OnDelete(DeleteBehavior.Restrict); // soft-delete only; never cascade-DELETE user data
            b.HasMany(x => x.Subtasks)
                .WithOne(s => s.Task!)
                .HasForeignKey(s => s.TaskId)
                .OnDelete(DeleteBehavior.Restrict);
            b.HasIndex(x => new { x.UserId, x.Status });
            b.HasIndex(x => new { x.UserId, x.PlannedDate });
            ConfigureSyncEntity(b);
            ApplyTenantAndSoftDeleteFilters(b);
        });

        // ---- Subtask ----
        modelBuilder.Entity<Subtask>(b =>
        {
            b.ToTable("subtasks");
            b.HasKey(x => x.Id);
            b.HasIndex(x => new { x.UserId, x.TaskId });
            ConfigureSyncEntity(b);
            ApplyTenantAndSoftDeleteFilters(b);
        });

        // ---- Project ----
        modelBuilder.Entity<Project>(b =>
        {
            b.ToTable("projects");
            b.HasKey(x => x.Id);
            b.Property(x => x.Rank).IsRequired();
            // partial unique index: per-user name uniqueness among live rows only
            b.HasIndex(x => new { x.UserId, x.Name })
                .IsUnique()
                .HasFilter("deleted_at IS NULL");
            ConfigureSyncEntity(b);
            ApplyTenantAndSoftDeleteFilters(b);
        });

        // ---- NoteGroup ----
        modelBuilder.Entity<NoteGroup>(b =>
        {
            b.ToTable("note_groups");
            b.HasKey(x => x.Id);
            b.Property(x => x.Rank).IsRequired();
            b.HasIndex(x => new { x.UserId, x.ProjectId });
            ConfigureSyncEntity(b);
            ApplyTenantAndSoftDeleteFilters(b);
        });

        // ---- Note ----
        modelBuilder.Entity<Note>(b =>
        {
            b.ToTable("notes");
            b.HasKey(x => x.Id);
            b.Property(x => x.Rank).IsRequired();
            b.HasIndex(x => new { x.UserId, x.GroupId });
            b.HasIndex(x => new { x.UserId, x.ProjectId });
            ConfigureSyncEntity(b);
            ApplyTenantAndSoftDeleteFilters(b);
        });

        // ---- RecurrenceRule ----
        modelBuilder.Entity<RecurrenceRule>(b =>
        {
            b.ToTable("recurrence_rules");
            b.HasKey(x => x.Id);
            b.Property(x => x.Frequency).HasConversion<string>().HasColumnType("text");
            b.Property(x => x.EndType).HasConversion<string>().HasColumnType("text");
            b.Property(x => x.DaysOfWeek).HasColumnType("jsonb");
            ConfigureSyncEntity(b);
            ApplyTenantAndSoftDeleteFilters(b);
        });

        // ---- RecurrenceException ----
        modelBuilder.Entity<RecurrenceException>(b =>
        {
            b.ToTable("recurrence_exceptions");
            b.HasKey(x => x.Id);
            // partial unique index among live rows only
            b.HasIndex(x => new { x.RecurrenceRuleId, x.ExceptionDate })
                .IsUnique()
                .HasFilter("deleted_at IS NULL");
            ConfigureSyncEntity(b);
            ApplyTenantAndSoftDeleteFilters(b);
        });

        // ---- FocusSession ----
        modelBuilder.Entity<FocusSession>(b =>
        {
            b.ToTable("focus_sessions");
            b.HasKey(x => x.Id);
            b.HasIndex(x => new { x.UserId, x.Date });
            ConfigureSyncEntity(b);
            ApplyTenantAndSoftDeleteFilters(b);
        });

        // ---- DailyPlan (composite key (UserId, Date), no Id) ----
        modelBuilder.Entity<DailyPlan>(b =>
        {
            b.ToTable("daily_plans");
            b.HasKey(x => new { x.UserId, x.Date });
            b.Property(x => x.PlannedTaskIds).HasColumnType("jsonb");
            ConfigureSyncColumns(b);
            ApplyTenantAndSoftDeleteFilters(b);
        });

        // ---- UserSetting (composite key (UserId, Key), no Id) ----
        modelBuilder.Entity<UserSetting>(b =>
        {
            b.ToTable("user_settings");
            b.HasKey(x => new { x.UserId, x.Key });
            b.Property(x => x.Value).HasColumnType("jsonb");
            ConfigureSyncColumns(b);
            ApplyTenantAndSoftDeleteFilters(b);
        });

        // ---- RefreshToken (internal, NOT synced) ----
        modelBuilder.Entity<RefreshToken>(b =>
        {
            b.ToTable("refresh_tokens");
            b.HasKey(x => x.Id);
            b.Property(x => x.TokenHash).IsRequired();
            b.HasIndex(x => x.TokenHash).IsUnique();
            b.HasIndex(x => x.UserId);
            b.HasOne<ApplicationUser>()
                .WithMany()
                .HasForeignKey(x => x.UserId)
                .OnDelete(DeleteBehavior.Cascade);
            // no sync columns, no query filters
        });

        // ---- SyncPurgeState (single-row watermark; NOT synced/tenant-scoped) ----
        // Deliberately a PLAIN table: NO ConfigureSyncColumns/ConfigureSyncEntity (so no
        // change_seq/updated_at trigger config), NO ApplyTenantAndSoftDeleteFilters (so no RLS
        // policy and no query filters), and it is absent from the SyncTriggersAndRls /
        // ChangeSeqIndexes synced-table loops. The migration creates it and seeds row (1, 0).
        modelBuilder.Entity<SyncPurgeState>(b =>
        {
            b.ToTable("sync_purge_state");
            b.HasKey(x => x.Id);
            b.Property(x => x.Id).ValueGeneratedNever(); // fixed single row, Id = 1
            b.Property(x => x.PurgedBelowChangeSeq).IsRequired();
        });

        // ---- ApplicationUser extra column ----
        modelBuilder.Entity<ApplicationUser>(b =>
        {
            b.Property(x => x.TimeZoneId).IsRequired().HasDefaultValue("UTC");
        });
    }

    // change_seq + updated_at are written by the DB trigger, never by EF.
    // change_seq: ValueGeneratedOnAddOrUpdate, BeforeSave=Ignore, AfterSave=Save. NOT a concurrency token.
    private static void ConfigureSyncColumns<TEntity>(EntityTypeBuilder<TEntity> b) where TEntity : class
    {
        b.Property(nameof(ISyncable.ChangeSeq))
            .ValueGeneratedOnAddOrUpdate()
            .Metadata.SetAfterSaveBehavior(PropertySaveBehavior.Save);
        b.Property(nameof(ISyncable.ChangeSeq))
            .Metadata.SetBeforeSaveBehavior(PropertySaveBehavior.Ignore);

        // updated_at is also trigger-authoritative: never written by EF, always read back.
        b.Property(nameof(ISyncable.UpdatedAt))
            .ValueGeneratedOnAddOrUpdate()
            .Metadata.SetAfterSaveBehavior(PropertySaveBehavior.Save);
        b.Property(nameof(ISyncable.UpdatedAt))
            .Metadata.SetBeforeSaveBehavior(PropertySaveBehavior.Ignore);
    }

    private static void ConfigureSyncEntity<TEntity>(EntityTypeBuilder<TEntity> b) where TEntity : SyncEntity
    {
        b.Property(x => x.UserId).IsRequired();
        ConfigureSyncColumns(b);
    }

    // EF Core 10 NAMED query filters: "Tenant" (UserId == currentUser.Id) and "SoftDelete" (DeletedAt == null).
    // _currentUser.Id is read AT QUERY TIME inside the expression (context is Scoped).
    // When _currentUser is SystemCurrentUser (elevated/system context), _isTenantFilterActive is false
    // so the Tenant filter becomes a no-op and cross-user arrange/assert works without IgnoreQueryFilters().
    private void ApplyTenantAndSoftDeleteFilters<TEntity>(EntityTypeBuilder<TEntity> b)
        where TEntity : class, IOwnedByUser, ISyncable
    {
        b.HasQueryFilter(TenantFilter, e =>
            !_isTenantFilterActive || e.UserId == _currentUser.Id);
        b.HasQueryFilter(SoftDeleteFilter, e => e.DeletedAt == null);
    }
}
