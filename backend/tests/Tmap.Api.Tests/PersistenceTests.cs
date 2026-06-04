using System;
using System.Linq;
using System.Security.Claims;
using FluentAssertions;
using Microsoft.AspNetCore.Http;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata;
using Microsoft.Extensions.DependencyInjection;
using Tmap.Api.Common;
using Tmap.Api.Infrastructure;
using Tmap.Api.Infrastructure.Entities;
using Tmap.Api.Infrastructure.Persistence;
using Xunit;
using TaskStatus = Tmap.Api.Infrastructure.Entities.TaskStatus;

namespace Tmap.Api.Tests;

public class SharedContractTests
{
    [Fact]
    public void SyncEntity_Implements_Both_Contracts_And_Has_Required_Members()
    {
        typeof(IOwnedByUser).IsAssignableFrom(typeof(SyncEntity)).Should().BeTrue();
        typeof(ISyncable).IsAssignableFrom(typeof(SyncEntity)).Should().BeTrue();

        // SyncEntity is the single-uuid-PK base with all six fields.
        var t = typeof(SyncEntity);
        t.GetProperty(nameof(SyncEntity.Id))!.PropertyType.Should().Be(typeof(Guid));
        t.GetProperty(nameof(SyncEntity.UserId))!.PropertyType.Should().Be(typeof(Guid));
        t.GetProperty(nameof(SyncEntity.CreatedAt))!.PropertyType.Should().Be(typeof(DateTimeOffset));
        t.GetProperty(nameof(SyncEntity.UpdatedAt))!.PropertyType.Should().Be(typeof(DateTimeOffset));
        t.GetProperty(nameof(SyncEntity.DeletedAt))!.PropertyType.Should().Be(typeof(DateTimeOffset?));
        t.GetProperty(nameof(SyncEntity.ChangeSeq))!.PropertyType.Should().Be(typeof(long));
    }
}

public class CurrentUserTests
{
    private static CurrentUser MakeHttp(ClaimsPrincipal? principal)
    {
        var accessor = new HttpContextAccessor
        {
            HttpContext = principal is null ? null : new DefaultHttpContext { User = principal }
        };
        return new CurrentUser(accessor);
    }

    [Fact]
    public void CurrentUser_Unauthenticated_IsAuthenticated_False_And_Id_Throws()
    {
        var sut = MakeHttp(null);
        sut.IsAuthenticated.Should().BeFalse();
        var act = () => sut.Id;
        act.Should().Throw<InvalidOperationException>();
    }

    [Fact]
    public void CurrentUser_Reads_Sub_Claim_As_Id()
    {
        var id = Guid.NewGuid();
        var principal = new ClaimsPrincipal(new ClaimsIdentity(
            new[] { new Claim("sub", id.ToString()) }, authenticationType: "TestAuth"));
        var sut = MakeHttp(principal);
        sut.IsAuthenticated.Should().BeTrue();
        sut.Id.Should().Be(id);
    }

    [Fact]
    public void SystemCurrentUser_Is_Authenticated_With_Fixed_Id()
    {
        var sut = new SystemCurrentUser();
        sut.IsAuthenticated.Should().BeTrue();
        sut.Id.Should().Be(SystemCurrentUser.SystemUserId);
    }
}

public class EntityShapeTests
{
    [Fact]
    public void Enums_Have_Exact_Members()
    {
        Enum.GetNames<TaskStatus>().Should().Equal(
            "Inbox", "Backlog", "Planned", "Scheduled", "Done", "Archived");
        Enum.GetNames<RecurrenceFrequency>().Should().Equal("Daily", "Weekly");
        Enum.GetNames<RecurrenceEndType>().Should().Equal("Never", "Count", "Date");
    }

    [Fact]
    public void SyncEntities_Derive_From_SyncEntity()
    {
        foreach (var t in new[]
        {
            typeof(TaskItem), typeof(Subtask), typeof(Project), typeof(NoteGroup),
            typeof(Note), typeof(RecurrenceRule), typeof(RecurrenceException), typeof(FocusSession)
        })
        {
            t.BaseType.Should().Be(typeof(Tmap.Api.Common.SyncEntity), $"{t.Name} must be a SyncEntity");
        }
    }

    [Fact]
    public void Composite_Key_Entities_Implement_Sync_Interfaces_But_Not_SyncEntity()
    {
        // DailyPlan and UserSetting have composite keys and NO Id.
        typeof(DailyPlan).BaseType.Should().Be(typeof(object));
        typeof(UserSetting).BaseType.Should().Be(typeof(object));
        typeof(Tmap.Api.Common.IOwnedByUser).IsAssignableFrom(typeof(DailyPlan)).Should().BeTrue();
        typeof(Tmap.Api.Common.ISyncable).IsAssignableFrom(typeof(DailyPlan)).Should().BeTrue();
        typeof(Tmap.Api.Common.IOwnedByUser).IsAssignableFrom(typeof(UserSetting)).Should().BeTrue();
        typeof(Tmap.Api.Common.ISyncable).IsAssignableFrom(typeof(UserSetting)).Should().BeTrue();
        typeof(DailyPlan).GetProperty("Id").Should().BeNull("DailyPlan has no Id (composite key)");
        typeof(UserSetting).GetProperty("Id").Should().BeNull("UserSetting has no Id (composite key)");
    }

    [Fact]
    public void TaskItem_Has_Contract_Fields()
    {
        var t = typeof(TaskItem);
        t.GetProperty("Title")!.PropertyType.Should().Be(typeof(string));
        t.GetProperty("ProjectId")!.PropertyType.Should().Be(typeof(Guid?));
        t.GetProperty("Labels")!.PropertyType.Should().Be(typeof(List<string>));
        t.GetProperty("Status")!.PropertyType.Should().Be(typeof(TaskStatus));
        t.GetProperty("PlannedDate")!.PropertyType.Should().Be(typeof(DateOnly?));
        t.GetProperty("Rank")!.PropertyType.Should().Be(typeof(string));
        t.GetProperty("DueDate")!.PropertyType.Should().Be(typeof(DateOnly?));
        t.GetProperty("Subtasks")!.PropertyType.Should().Be(typeof(List<Subtask>));
    }
}

[Collection("db")]
public class AppDbContextWiringTests(PostgresFixture fixture)
{
    [Fact]
    public void AddPersistence_Registers_Scoped_AppDbContext_That_Can_Connect()
    {
        var services = new ServiceCollection();
        services.AddHttpContextAccessor();
        services.AddScoped<Tmap.Api.Common.ICurrentUser, Tmap.Api.Common.SystemCurrentUser>();
        services.AddPersistence(fixture.ConnectionString);

        using var provider = services.BuildServiceProvider();
        using var scope = provider.CreateScope();
        var ctx = scope.ServiceProvider.GetRequiredService<AppDbContext>();

        ctx.Database.CanConnect().Should().BeTrue();
    }
}

[Collection("db")]
public class AppDbContextModelTests(PostgresFixture fixture)
{
    private AppDbContext NewContext()
    {
        var services = new ServiceCollection();
        services.AddHttpContextAccessor();
        services.AddScoped<Tmap.Api.Common.ICurrentUser, Tmap.Api.Common.SystemCurrentUser>();
        services.AddDbContext<AppDbContext>(o =>
        {
            o.UseNpgsql(fixture.ConnectionString);
            o.UseSnakeCaseNamingConvention();
        });
        var provider = services.BuildServiceProvider();
        return provider.GetRequiredService<AppDbContext>();
    }

    [Fact]
    public void ChangeSeq_Is_ValueGeneratedOnAddOrUpdate_And_Not_Concurrency_Token()
    {
        using var ctx = NewContext();
        var prop = ctx.Model.FindEntityType(typeof(TaskItem))!.FindProperty(nameof(TaskItem.ChangeSeq))!;
        prop.ValueGenerated.Should().Be(ValueGenerated.OnAddOrUpdate);
        prop.IsConcurrencyToken.Should().BeFalse();
        prop.GetBeforeSaveBehavior().Should().Be(PropertySaveBehavior.Ignore);
        prop.GetAfterSaveBehavior().Should().Be(PropertySaveBehavior.Save);
    }

    [Fact]
    public void Labels_DaysOfWeek_PlannedTaskIds_Are_Jsonb()
    {
        using var ctx = NewContext();
        ctx.Model.FindEntityType(typeof(TaskItem))!
           .FindProperty(nameof(TaskItem.Labels))!.GetColumnType().Should().Be("jsonb");
        ctx.Model.FindEntityType(typeof(RecurrenceRule))!
           .FindProperty(nameof(RecurrenceRule.DaysOfWeek))!.GetColumnType().Should().Be("jsonb");
        ctx.Model.FindEntityType(typeof(DailyPlan))!
           .FindProperty(nameof(DailyPlan.PlannedTaskIds))!.GetColumnType().Should().Be("jsonb");
        ctx.Model.FindEntityType(typeof(UserSetting))!
           .FindProperty(nameof(UserSetting.Value))!.GetColumnType().Should().Be("jsonb");
    }

    [Fact]
    public void Status_And_Frequency_Are_Stored_As_Text()
    {
        using var ctx = NewContext();
        ctx.Model.FindEntityType(typeof(TaskItem))!
           .FindProperty(nameof(TaskItem.Status))!.GetColumnType().Should().Be("text");
        ctx.Model.FindEntityType(typeof(RecurrenceRule))!
           .FindProperty(nameof(RecurrenceRule.Frequency))!.GetColumnType().Should().Be("text");
        ctx.Model.FindEntityType(typeof(RecurrenceRule))!
           .FindProperty(nameof(RecurrenceRule.EndType))!.GetColumnType().Should().Be("text");
    }

    [Fact]
    public void Composite_Keys_Are_Configured()
    {
        using var ctx = NewContext();
        var dp = ctx.Model.FindEntityType(typeof(DailyPlan))!.FindPrimaryKey()!;
        dp.Properties.Select(p => p.Name).Should().Equal(nameof(DailyPlan.UserId), nameof(DailyPlan.Date));
        var us = ctx.Model.FindEntityType(typeof(UserSetting))!.FindPrimaryKey()!;
        us.Properties.Select(p => p.Name).Should().Equal(nameof(UserSetting.UserId), nameof(UserSetting.Key));
    }

    [Fact]
    public void Named_Query_Filters_Tenant_And_SoftDelete_Exist_On_TaskItem()
    {
        using var ctx = NewContext();
        var filters = ctx.Model.FindEntityType(typeof(TaskItem))!.GetDeclaredQueryFilters();
        filters.Select(f => f.Key).Should().Contain(new[] { AppDbContext.TenantFilter, AppDbContext.SoftDeleteFilter });
    }

    [Fact]
    public void TaskItem_To_Project_FK_And_Subtask_To_Task_FK_Configured()
    {
        using var ctx = NewContext();
        var taskType = ctx.Model.FindEntityType(typeof(TaskItem))!;
        taskType.GetForeignKeys()
            .Should().Contain(fk => fk.PrincipalEntityType.ClrType == typeof(Project)
                && fk.Properties.Any(p => p.Name == nameof(TaskItem.ProjectId)));
        var subtaskType = ctx.Model.FindEntityType(typeof(Subtask))!;
        subtaskType.GetForeignKeys()
            .Should().Contain(fk => fk.PrincipalEntityType.ClrType == typeof(TaskItem)
                && fk.Properties.Any(p => p.Name == nameof(Subtask.TaskId)));
    }
}

[Collection("db")]
public class MigrationSchemaTests(PostgresFixture fixture) : IntegrationTestBase(fixture)
{
    [Fact]
    public async Task Migration_Creates_All_Core_Tables()
    {
        await using var ctx = NewElevatedDbContext();
        var tables = new[]
        {
            "tasks", "subtasks", "projects", "note_groups", "notes",
            "recurrence_rules", "recurrence_exceptions", "focus_sessions",
            "daily_plans", "user_settings", "refresh_tokens"
        };

        foreach (var table in tables)
        {
            var exists = await ctx.Database
                .SqlQuery<bool>($"SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = {table}) AS \"Value\"")
                .SingleAsync();
            exists.Should().BeTrue($"table {table} should exist after migration");
        }
    }
}

[Collection("db")]
public class ChangeSeqTriggerTests(PostgresFixture fixture) : IntegrationTestBase(fixture)
{
    [Fact]
    public async Task Insert_Assigns_ChangeSeq_And_Update_Bumps_It()
    {
        var user = await RegisterAsync();

        await using var arrange = NewElevatedDbContext();
        var project = new Project
        {
            Id = Guid.NewGuid(),
            UserId = user.UserId,
            Name = "P-" + Guid.NewGuid().ToString("N"),
            Rank = "a0",
            CreatedAt = DateTimeOffset.UtcNow
        };
        arrange.Projects.Add(project);
        await arrange.SaveChangesAsync();

        project.ChangeSeq.Should().BeGreaterThan(0, "the trigger assigns change_seq on INSERT");
        var afterInsert = project.ChangeSeq;
        var insertedUpdatedAt = project.UpdatedAt;

        project.Name = "renamed-" + Guid.NewGuid().ToString("N");
        await arrange.SaveChangesAsync();

        project.ChangeSeq.Should().BeGreaterThan(afterInsert, "the trigger bumps change_seq on UPDATE");
        project.UpdatedAt.Should().BeAfter(insertedUpdatedAt, "the trigger refreshes updated_at on UPDATE");
    }

    [Fact]
    public async Task ExecuteUpdate_Bumps_ChangeSeq_Proving_Trigger_Not_Interceptor()
    {
        var user = await RegisterAsync();

        // Pass the owning user id so the EF tenant query filter resolves to this user's rows;
        // the read-back/ExecuteUpdate below would otherwise be filtered out by the default
        // (system) tenant scope. RLS still passes via the elevated system-id GUC.
        await using var ctx = NewElevatedDbContext(user.UserId);
        var task = new TaskItem
        {
            Id = Guid.NewGuid(),
            UserId = user.UserId,
            Title = "T",
            Rank = "a0",
            CreatedAt = DateTimeOffset.UtcNow
        };
        ctx.Tasks.Add(task);
        await ctx.SaveChangesAsync();
        var seqBefore = task.ChangeSeq;

        // Bulk update bypasses the ChangeTracker (and any SaveChanges interceptor).
        await ctx.Tasks
            .Where(t => t.Id == task.Id)
            .ExecuteUpdateAsync(s => s.SetProperty(t => t.DurationMinutes, 45));

        var seqAfter = await ctx.Tasks
            .Where(t => t.Id == task.Id)
            .Select(t => t.ChangeSeq)
            .SingleAsync();

        seqAfter.Should().BeGreaterThan(seqBefore,
            "ExecuteUpdate bypasses the tracker, so only a DB trigger can advance change_seq");
    }
}
