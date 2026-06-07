using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Tmap.Api.Migrations;

/// <inheritdoc />
public partial class FixCreatedAtTrigger : Migration
{
    /// <inheritdoc />
    protected override void Up(MigrationBuilder migrationBuilder)
    {
        // Redefine bump_change_seq() (originally created in SyncTriggersAndRls) so that, in addition
        // to advancing change_seq and refreshing updated_at on every write, it stamps created_at on
        // INSERT when EF left it at the CLR default (0001-01-01) / NULL. created_at is never written
        // by EF or the handlers, so without this the column persisted the 0001-01-01 sentinel.
        // The sentinel guard ('0001-01-02') leaves any caller-provided created_at intact (e.g.
        // system/seed inserts), while updated_at + change_seq behavior is unchanged for INSERT/UPDATE.
        migrationBuilder.Sql(@"
CREATE OR REPLACE FUNCTION bump_change_seq() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'INSERT'
       AND (NEW.created_at IS NULL OR NEW.created_at <= '0001-01-02 00:00:00+00'::timestamptz) THEN
        NEW.created_at := now();
    END IF;
    NEW.change_seq := nextval('global_change_seq');
    NEW.updated_at := now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;");
    }

    /// <inheritdoc />
    protected override void Down(MigrationBuilder migrationBuilder)
    {
        // Restore the SyncTriggersAndRls version (no created_at handling).
        migrationBuilder.Sql(@"
CREATE OR REPLACE FUNCTION bump_change_seq() RETURNS trigger AS $$
BEGIN
    NEW.change_seq := nextval('global_change_seq');
    NEW.updated_at := now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;");
    }
}
