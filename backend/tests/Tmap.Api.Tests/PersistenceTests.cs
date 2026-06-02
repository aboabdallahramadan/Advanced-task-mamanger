using System;
using FluentAssertions;
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
