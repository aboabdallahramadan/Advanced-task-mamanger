using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Diagnostics;
using Tmap.Api.Common;

namespace Tmap.Api.Infrastructure.Persistence;

// Stamps UserId from ICurrentUser on inserted IOwnedByUser entries; rejects a mismatched
// client-supplied UserId on update (request body is never trusted for ownership).
public sealed class OwnershipSaveChangesInterceptor(ICurrentUser currentUser) : SaveChangesInterceptor
{
    public override InterceptionResult<int> SavingChanges(
        DbContextEventData eventData, InterceptionResult<int> result)
    {
        Stamp(eventData);
        return base.SavingChanges(eventData, result);
    }

    public override ValueTask<InterceptionResult<int>> SavingChangesAsync(
        DbContextEventData eventData, InterceptionResult<int> result, CancellationToken cancellationToken = default)
    {
        Stamp(eventData);
        return base.SavingChangesAsync(eventData, result, cancellationToken);
    }

    private void Stamp(DbContextEventData eventData)
    {
        var context = eventData.Context;
        if (context is null)
            return;

        var ownedEntries = context.ChangeTracker.Entries<IOwnedByUser>()
            .Where(e => e.State is EntityState.Added or EntityState.Modified)
            .ToList();

        if (ownedEntries.Count == 0)
            return; // Nothing to stamp — skip ICurrentUser resolution (allows unauthenticated saves, e.g. refresh-token insert during register).

        var ownerId = currentUser.Id; // fail-closed: throws if unauthenticated (only reached when owned entities are in flight)

        foreach (var entry in ownedEntries)
        {
            switch (entry.State)
            {
                case EntityState.Added:
                    // Always stamp from the auth context, overriding any client-supplied value.
                    entry.Entity.UserId = ownerId;
                    break;

                case EntityState.Modified:
                    // Ownership is immutable; reject any attempt to reassign it.
                    if (entry.Entity.UserId != ownerId)
                    {
                        throw new InvalidOperationException(
                            "Cannot change UserId of an owned entity; ownership is server-authoritative.");
                    }
                    break;
            }
        }
    }
}
