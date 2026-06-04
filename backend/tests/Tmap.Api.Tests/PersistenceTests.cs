using System;
using System.Security.Claims;
using FluentAssertions;
using Microsoft.AspNetCore.Http;
using Tmap.Api.Common;
using Xunit;

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
