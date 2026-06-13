using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Tmap.Api.Migrations;

/// <inheritdoc />
public partial class ChangeSeqIndexes : Migration
{
    // The 10 synced tables (same set the change_seq trigger covers in SyncTriggersAndRls).
    // recurrence_exceptions is NOT pulled by /sync, but it IS a synced (trigger-bearing) table,
    // so it gets the index too for parity with the trigger set.
    private static readonly string[] SyncedTables =
    [
        "tasks", "subtasks", "projects", "note_groups", "notes",
        "recurrence_rules", "recurrence_exceptions", "focus_sessions",
        "daily_plans", "user_settings"
    ];

    /// <inheritdoc />
    protected override void Up(MigrationBuilder migrationBuilder)
    {
        // Composite (user_id, change_seq) index supports the per-tenant pull access path
        // (WHERE user_id = current AND change_seq > since ORDER BY change_seq).
        foreach (var table in SyncedTables)
        {
            migrationBuilder.Sql(
                $@"CREATE INDEX IF NOT EXISTS ""ix_{table}_user_id_change_seq"" ON ""{table}"" (""user_id"", ""change_seq"");");
        }
    }

    /// <inheritdoc />
    protected override void Down(MigrationBuilder migrationBuilder)
    {
        foreach (var table in SyncedTables)
        {
            migrationBuilder.Sql($@"DROP INDEX IF EXISTS ""ix_{table}_user_id_change_seq"";");
        }
    }
}
