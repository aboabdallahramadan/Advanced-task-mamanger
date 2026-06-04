using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Tmap.Api.Migrations;

/// <inheritdoc />
public partial class SyncTriggersAndRls : Migration
{
    private static readonly string[] SyncedTables =
    [
        "tasks", "subtasks", "projects", "note_groups", "notes",
        "recurrence_rules", "recurrence_exceptions", "focus_sessions",
        "daily_plans", "user_settings"
    ];

    /// <inheritdoc />
    protected override void Up(MigrationBuilder migrationBuilder)
    {
        // 1) One global sequence feeding change_seq across all synced tables.
        migrationBuilder.Sql("CREATE SEQUENCE IF NOT EXISTS global_change_seq AS bigint START WITH 1 INCREMENT BY 1;");

        // 2) Trigger function: assign next change_seq and refresh updated_at on every write.
        migrationBuilder.Sql(@"
CREATE OR REPLACE FUNCTION bump_change_seq() RETURNS trigger AS $$
BEGIN
    NEW.change_seq := nextval('global_change_seq');
    NEW.updated_at := now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;");

        foreach (var table in SyncedTables)
        {
            // 3) BEFORE INSERT OR UPDATE trigger on every synced table — authoritative for change_seq + updated_at.
            migrationBuilder.Sql($@"
DROP TRIGGER IF EXISTS trg_bump_change_seq ON ""{table}"";
CREATE TRIGGER trg_bump_change_seq
    BEFORE INSERT OR UPDATE ON ""{table}""
    FOR EACH ROW EXECUTE FUNCTION bump_change_seq();");

            // 4) Enable RLS + per-table policy: user_id = current_setting('app.user_id', true)::uuid.
            //    'true' (missing_ok) means an unset GUC yields NULL -> no rows (fail-closed), not an error.
            //    The system id ('00000000-0000-0000-0000-000000000001') is additionally allowed so the
            //    elevated SystemCurrentUser context (migrations/seed/sync) can arrange/assert any user's
            //    data, while real (HTTP) requests stay strictly tenant-scoped.
            migrationBuilder.Sql($@"ALTER TABLE ""{table}"" ENABLE ROW LEVEL SECURITY;");
            migrationBuilder.Sql($@"ALTER TABLE ""{table}"" FORCE ROW LEVEL SECURITY;");
            migrationBuilder.Sql($@"
DROP POLICY IF EXISTS tenant_isolation ON ""{table}"";
CREATE POLICY tenant_isolation ON ""{table}""
    USING (
        current_setting('app.user_id', true)::uuid = '00000000-0000-0000-0000-000000000001'::uuid
        OR user_id = current_setting('app.user_id', true)::uuid)
    WITH CHECK (
        current_setting('app.user_id', true)::uuid = '00000000-0000-0000-0000-000000000001'::uuid
        OR user_id = current_setting('app.user_id', true)::uuid);");
        }
    }

    /// <inheritdoc />
    protected override void Down(MigrationBuilder migrationBuilder)
    {
        foreach (var table in SyncedTables)
        {
            migrationBuilder.Sql($@"DROP POLICY IF EXISTS tenant_isolation ON ""{table}"";");
            migrationBuilder.Sql($@"ALTER TABLE ""{table}"" NO FORCE ROW LEVEL SECURITY;");
            migrationBuilder.Sql($@"ALTER TABLE ""{table}"" DISABLE ROW LEVEL SECURITY;");
            migrationBuilder.Sql($@"DROP TRIGGER IF EXISTS trg_bump_change_seq ON ""{table}"";");
        }
        migrationBuilder.Sql("DROP FUNCTION IF EXISTS bump_change_seq();");
        migrationBuilder.Sql("DROP SEQUENCE IF EXISTS global_change_seq;");
    }
}
