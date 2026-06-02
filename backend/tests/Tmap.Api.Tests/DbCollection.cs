using Xunit;

namespace Tmap.Api.Tests;

[CollectionDefinition(Name)]
public sealed class DbCollection : ICollectionFixture<PostgresFixture>
{
    public const string Name = "db";
}
