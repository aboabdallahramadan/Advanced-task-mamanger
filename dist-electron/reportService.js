"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.ReportService = void 0;
class ReportService {
    db;
    constructor(db) {
        this.db = db;
    }
    query(sql, params = []) {
        const stmt = this.db.prepare(sql);
        stmt.bind(params);
        const rows = [];
        try {
            while (stmt.step())
                rows.push(stmt.getAsObject());
        }
        finally {
            stmt.free();
        }
        return rows;
    }
    getData(start, end) {
        // 'localtime' converts the UTC-stored completed_at to the host's local
        // calendar day, matching focus_sessions.date and the renderer's day buckets.
        const completedTasks = this.query(`SELECT id, project, date(completed_at, 'localtime') AS d
             FROM tasks
             WHERE completed_at IS NOT NULL
               AND date(completed_at, 'localtime') >= ? AND date(completed_at, 'localtime') <= ?`, [start, end]).map((r) => ({ id: r.id, project: r.project || '', date: r.d }));
        const sessions = this.query(`SELECT project, minutes, date FROM focus_sessions WHERE date >= ? AND date <= ?`, [start, end]).map((r) => ({ project: r.project || '', minutes: r.minutes || 0, date: r.date }));
        const dailyPlans = this.query(`SELECT date, committed_at, planned_task_ids, planned_minutes
             FROM daily_plans WHERE date >= ? AND date <= ?`, [start, end]).map((r) => ({
            date: r.date,
            committedAt: r.committed_at,
            plannedTaskIds: JSON.parse(r.planned_task_ids || '[]'),
            plannedMinutes: r.planned_minutes || 0,
        }));
        return { completedTasks, sessions, dailyPlans };
    }
}
exports.ReportService = ReportService;
