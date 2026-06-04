using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Tmap.Api.Migrations;

/// <inheritdoc />
public partial class InitialSchema : Migration
{
    /// <inheritdoc />
    protected override void Up(MigrationBuilder migrationBuilder)
    {
        migrationBuilder.CreateTable(
            name: "daily_plans",
            columns: table => new
            {
                user_id = table.Column<Guid>(type: "uuid", nullable: false),
                date = table.Column<DateOnly>(type: "date", nullable: false),
                committed_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                planned_task_ids = table.Column<string>(type: "jsonb", nullable: false),
                planned_minutes = table.Column<int>(type: "integer", nullable: false),
                created_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                updated_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                deleted_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: true),
                change_seq = table.Column<long>(type: "bigint", nullable: false)
            },
            constraints: table =>
            {
                table.PrimaryKey("pk_daily_plans", x => new { x.user_id, x.date });
            });

        migrationBuilder.CreateTable(
            name: "focus_sessions",
            columns: table => new
            {
                id = table.Column<Guid>(type: "uuid", nullable: false),
                task_id = table.Column<Guid>(type: "uuid", nullable: true),
                project = table.Column<string>(type: "text", nullable: false),
                started_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                ended_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                minutes = table.Column<int>(type: "integer", nullable: false),
                date = table.Column<DateOnly>(type: "date", nullable: false),
                user_id = table.Column<Guid>(type: "uuid", nullable: false),
                created_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                updated_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                deleted_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: true),
                change_seq = table.Column<long>(type: "bigint", nullable: false)
            },
            constraints: table =>
            {
                table.PrimaryKey("pk_focus_sessions", x => x.id);
            });

        migrationBuilder.CreateTable(
            name: "note_groups",
            columns: table => new
            {
                id = table.Column<Guid>(type: "uuid", nullable: false),
                name = table.Column<string>(type: "text", nullable: false),
                emoji = table.Column<string>(type: "text", nullable: false),
                project_id = table.Column<Guid>(type: "uuid", nullable: true),
                rank = table.Column<string>(type: "text", nullable: false),
                user_id = table.Column<Guid>(type: "uuid", nullable: false),
                created_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                updated_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                deleted_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: true),
                change_seq = table.Column<long>(type: "bigint", nullable: false)
            },
            constraints: table =>
            {
                table.PrimaryKey("pk_note_groups", x => x.id);
            });

        migrationBuilder.CreateTable(
            name: "notes",
            columns: table => new
            {
                id = table.Column<Guid>(type: "uuid", nullable: false),
                group_id = table.Column<Guid>(type: "uuid", nullable: true),
                project_id = table.Column<Guid>(type: "uuid", nullable: true),
                title = table.Column<string>(type: "text", nullable: false),
                content = table.Column<string>(type: "text", nullable: false),
                rank = table.Column<string>(type: "text", nullable: false),
                user_id = table.Column<Guid>(type: "uuid", nullable: false),
                created_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                updated_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                deleted_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: true),
                change_seq = table.Column<long>(type: "bigint", nullable: false)
            },
            constraints: table =>
            {
                table.PrimaryKey("pk_notes", x => x.id);
            });

        migrationBuilder.CreateTable(
            name: "projects",
            columns: table => new
            {
                id = table.Column<Guid>(type: "uuid", nullable: false),
                name = table.Column<string>(type: "text", nullable: false),
                color = table.Column<string>(type: "text", nullable: false),
                emoji = table.Column<string>(type: "text", nullable: false),
                rank = table.Column<string>(type: "text", nullable: false),
                actual_time_minutes = table.Column<int>(type: "integer", nullable: false),
                user_id = table.Column<Guid>(type: "uuid", nullable: false),
                created_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                updated_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                deleted_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: true),
                change_seq = table.Column<long>(type: "bigint", nullable: false)
            },
            constraints: table =>
            {
                table.PrimaryKey("pk_projects", x => x.id);
            });

        migrationBuilder.CreateTable(
            name: "recurrence_exceptions",
            columns: table => new
            {
                id = table.Column<Guid>(type: "uuid", nullable: false),
                recurrence_rule_id = table.Column<Guid>(type: "uuid", nullable: false),
                exception_date = table.Column<DateOnly>(type: "date", nullable: false),
                user_id = table.Column<Guid>(type: "uuid", nullable: false),
                created_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                updated_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                deleted_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: true),
                change_seq = table.Column<long>(type: "bigint", nullable: false)
            },
            constraints: table =>
            {
                table.PrimaryKey("pk_recurrence_exceptions", x => x.id);
            });

        migrationBuilder.CreateTable(
            name: "recurrence_rules",
            columns: table => new
            {
                id = table.Column<Guid>(type: "uuid", nullable: false),
                frequency = table.Column<string>(type: "text", nullable: false),
                interval_value = table.Column<int>(type: "integer", nullable: false),
                days_of_week = table.Column<string>(type: "jsonb", nullable: false),
                end_type = table.Column<string>(type: "text", nullable: false),
                end_count = table.Column<int>(type: "integer", nullable: true),
                end_date = table.Column<DateOnly>(type: "date", nullable: true),
                generated_until = table.Column<DateOnly>(type: "date", nullable: true),
                user_id = table.Column<Guid>(type: "uuid", nullable: false),
                created_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                updated_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                deleted_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: true),
                change_seq = table.Column<long>(type: "bigint", nullable: false)
            },
            constraints: table =>
            {
                table.PrimaryKey("pk_recurrence_rules", x => x.id);
            });

        migrationBuilder.CreateTable(
            name: "user_settings",
            columns: table => new
            {
                user_id = table.Column<Guid>(type: "uuid", nullable: false),
                key = table.Column<string>(type: "text", nullable: false),
                value = table.Column<string>(type: "jsonb", nullable: false),
                created_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                updated_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                deleted_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: true),
                change_seq = table.Column<long>(type: "bigint", nullable: false)
            },
            constraints: table =>
            {
                table.PrimaryKey("pk_user_settings", x => new { x.user_id, x.key });
            });

        migrationBuilder.CreateTable(
            name: "tasks",
            columns: table => new
            {
                id = table.Column<Guid>(type: "uuid", nullable: false),
                title = table.Column<string>(type: "text", nullable: false),
                notes = table.Column<string>(type: "text", nullable: false),
                project_id = table.Column<Guid>(type: "uuid", nullable: true),
                labels = table.Column<string>(type: "jsonb", nullable: false),
                source = table.Column<string>(type: "text", nullable: false),
                status = table.Column<string>(type: "text", nullable: false),
                planned_date = table.Column<DateOnly>(type: "date", nullable: true),
                scheduled_start = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: true),
                scheduled_end = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: true),
                duration_minutes = table.Column<int>(type: "integer", nullable: false),
                actual_time_minutes = table.Column<int>(type: "integer", nullable: false),
                priority = table.Column<int>(type: "integer", nullable: true),
                reminder_minutes = table.Column<int>(type: "integer", nullable: true),
                rank = table.Column<string>(type: "text", nullable: false),
                due_date = table.Column<DateOnly>(type: "date", nullable: true),
                recurrence_rule_id = table.Column<Guid>(type: "uuid", nullable: true),
                is_recurrence_template = table.Column<bool>(type: "boolean", nullable: false),
                recurrence_detached = table.Column<bool>(type: "boolean", nullable: false),
                recurrence_original_date = table.Column<DateOnly>(type: "date", nullable: true),
                completed_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: true),
                user_id = table.Column<Guid>(type: "uuid", nullable: false),
                created_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                updated_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                deleted_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: true),
                change_seq = table.Column<long>(type: "bigint", nullable: false)
            },
            constraints: table =>
            {
                table.PrimaryKey("pk_tasks", x => x.id);
                table.ForeignKey(
                    name: "fk_tasks_projects_project_id",
                    column: x => x.project_id,
                    principalTable: "projects",
                    principalColumn: "id",
                    onDelete: ReferentialAction.Restrict);
            });

        migrationBuilder.CreateTable(
            name: "subtasks",
            columns: table => new
            {
                id = table.Column<Guid>(type: "uuid", nullable: false),
                task_id = table.Column<Guid>(type: "uuid", nullable: false),
                title = table.Column<string>(type: "text", nullable: false),
                completed = table.Column<bool>(type: "boolean", nullable: false),
                sort_order = table.Column<int>(type: "integer", nullable: false),
                user_id = table.Column<Guid>(type: "uuid", nullable: false),
                created_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                updated_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                deleted_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: true),
                change_seq = table.Column<long>(type: "bigint", nullable: false)
            },
            constraints: table =>
            {
                table.PrimaryKey("pk_subtasks", x => x.id);
                table.ForeignKey(
                    name: "fk_subtasks_tasks_task_id",
                    column: x => x.task_id,
                    principalTable: "tasks",
                    principalColumn: "id",
                    onDelete: ReferentialAction.Restrict);
            });

        migrationBuilder.CreateIndex(
            name: "ix_focus_sessions_user_id_date",
            table: "focus_sessions",
            columns: new[] { "user_id", "date" });

        migrationBuilder.CreateIndex(
            name: "ix_note_groups_user_id_project_id",
            table: "note_groups",
            columns: new[] { "user_id", "project_id" });

        migrationBuilder.CreateIndex(
            name: "ix_notes_user_id_group_id",
            table: "notes",
            columns: new[] { "user_id", "group_id" });

        migrationBuilder.CreateIndex(
            name: "ix_notes_user_id_project_id",
            table: "notes",
            columns: new[] { "user_id", "project_id" });

        migrationBuilder.CreateIndex(
            name: "ix_projects_user_id_name",
            table: "projects",
            columns: new[] { "user_id", "name" },
            unique: true,
            filter: "deleted_at IS NULL");

        migrationBuilder.CreateIndex(
            name: "ix_recurrence_exceptions_recurrence_rule_id_exception_date",
            table: "recurrence_exceptions",
            columns: new[] { "recurrence_rule_id", "exception_date" },
            unique: true,
            filter: "deleted_at IS NULL");

        migrationBuilder.CreateIndex(
            name: "ix_subtasks_task_id",
            table: "subtasks",
            column: "task_id");

        migrationBuilder.CreateIndex(
            name: "ix_subtasks_user_id_task_id",
            table: "subtasks",
            columns: new[] { "user_id", "task_id" });

        migrationBuilder.CreateIndex(
            name: "ix_tasks_project_id",
            table: "tasks",
            column: "project_id");

        migrationBuilder.CreateIndex(
            name: "ix_tasks_user_id_planned_date",
            table: "tasks",
            columns: new[] { "user_id", "planned_date" });

        migrationBuilder.CreateIndex(
            name: "ix_tasks_user_id_status",
            table: "tasks",
            columns: new[] { "user_id", "status" });
    }

    /// <inheritdoc />
    protected override void Down(MigrationBuilder migrationBuilder)
    {
        migrationBuilder.DropTable(
            name: "daily_plans");

        migrationBuilder.DropTable(
            name: "focus_sessions");

        migrationBuilder.DropTable(
            name: "note_groups");

        migrationBuilder.DropTable(
            name: "notes");

        migrationBuilder.DropTable(
            name: "recurrence_exceptions");

        migrationBuilder.DropTable(
            name: "recurrence_rules");

        migrationBuilder.DropTable(
            name: "subtasks");

        migrationBuilder.DropTable(
            name: "user_settings");

        migrationBuilder.DropTable(
            name: "tasks");

        migrationBuilder.DropTable(
            name: "projects");
    }
}
