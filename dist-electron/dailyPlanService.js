"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.DailyPlanService = void 0;
const database_1 = require("./database");
class DailyPlanService {
    db;
    constructor(db) {
        this.db = db;
    }
    upsert(input) {
        const now = new Date().toISOString();
        this.db.run(`INSERT INTO daily_plans (date, committed_at, planned_task_ids, planned_minutes)
             VALUES (?, ?, ?, ?)
             ON CONFLICT(date) DO UPDATE SET
               committed_at = excluded.committed_at,
               planned_task_ids = excluded.planned_task_ids,
               planned_minutes = excluded.planned_minutes`, [input.date, now, JSON.stringify(input.plannedTaskIds), input.plannedMinutes]);
        (0, database_1.saveDatabase)();
        return {
            date: input.date,
            committedAt: now,
            plannedTaskIds: input.plannedTaskIds,
            plannedMinutes: input.plannedMinutes,
        };
    }
    get(date) {
        const stmt = this.db.prepare('SELECT * FROM daily_plans WHERE date = ?');
        stmt.bind([date]);
        let row = null;
        if (stmt.step())
            row = stmt.getAsObject();
        stmt.free();
        if (!row)
            return null;
        return {
            date: row.date,
            committedAt: row.committed_at,
            plannedTaskIds: JSON.parse(row.planned_task_ids || '[]'),
            plannedMinutes: row.planned_minutes || 0,
        };
    }
}
exports.DailyPlanService = DailyPlanService;
