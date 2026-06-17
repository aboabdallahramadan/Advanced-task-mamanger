using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Tmap.Api.Migrations
{
    /// <inheritdoc />
    public partial class SyncPurgeStateTable : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateTable(
                name: "sync_purge_state",
                columns: table => new
                {
                    id = table.Column<int>(type: "integer", nullable: false),
                    purged_below_change_seq = table.Column<long>(type: "bigint", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_sync_purge_state", x => x.id);
                });

            // Seed the single watermark row (Id = 1, watermark = 0). The purge advances it monotonically.
            migrationBuilder.Sql(
                "INSERT INTO sync_purge_state (id, purged_below_change_seq) VALUES (1, 0) ON CONFLICT (id) DO NOTHING;");
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.Sql("DELETE FROM sync_purge_state WHERE id = 1;");
            migrationBuilder.DropTable(
                name: "sync_purge_state");
        }
    }
}
