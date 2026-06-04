using System;
using System.Security.Claims;
using FluentAssertions;
using Microsoft.AspNetCore.Http;
using Tmap.Api.Common;
using Tmap.Api.Infrastructure.Entities;
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
