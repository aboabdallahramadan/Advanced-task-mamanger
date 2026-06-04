using System.Data.Common;
using Microsoft.EntityFrameworkCore.Diagnostics;
using Tmap.Api.Common;

namespace Tmap.Api.Infrastructure.Persistence;

// Sets the Postgres 'app.user_id' setting per opened connection from ICurrentUser, so RLS
// policies (user_id = current_setting('app.user_id', true)::uuid) scope every query — even
// raw SQL or IgnoreQueryFilters paths. Cleared when not authenticated (RLS then returns no rows).
public sealed class UserIdConnectionInterceptor(ICurrentUser currentUser) : DbConnectionInterceptor
{
    public override async Task ConnectionOpenedAsync(
        DbConnection connection, ConnectionEndEventData eventData, CancellationToken cancellationToken = default)
    {
        await ApplyAsync(connection, cancellationToken);
        await base.ConnectionOpenedAsync(connection, eventData, cancellationToken);
    }

    public override void ConnectionOpened(DbConnection connection, ConnectionEndEventData eventData)
    {
        ApplyAsync(connection, CancellationToken.None).GetAwaiter().GetResult();
        base.ConnectionOpened(connection, eventData);
    }

    private async Task ApplyAsync(DbConnection connection, CancellationToken cancellationToken)
    {
        await using var cmd = connection.CreateCommand();
        if (currentUser.IsAuthenticated)
        {
            // set_config(name, value, is_local=false) — session-scoped; reset on next open below.
            cmd.CommandText = "SELECT set_config('app.user_id', @user_id, false);";
            var p = cmd.CreateParameter();
            p.ParameterName = "user_id";
            p.Value = currentUser.Id.ToString();
            cmd.Parameters.Add(p);
        }
        else
        {
            // Clear any value left on a pooled connection -> fail-closed (no rows visible).
            cmd.CommandText = "SELECT set_config('app.user_id', '', false);";
        }
        await cmd.ExecuteNonQueryAsync(cancellationToken);
    }
}
