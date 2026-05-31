"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.FocusSessionService = void 0;
const uuid_1 = require("uuid");
const database_1 = require("./database");
class FocusSessionService {
    db;
    constructor(db) {
        this.db = db;
    }
    add(input) {
        const id = (0, uuid_1.v4)();
        const now = new Date().toISOString();
        try {
            this.db.run(`INSERT INTO focus_sessions (id, task_id, project, started_at, ended_at, minutes, date, created_at)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?)`, [
                id,
                input.taskId,
                input.project,
                input.startedAt,
                input.endedAt,
                input.minutes,
                input.date,
                now,
            ]);
            (0, database_1.saveDatabase)();
        }
        catch (e) {
            console.error('FocusSessionService.add failed:', e);
            throw e;
        }
        return { id, ...input, createdAt: now };
    }
}
exports.FocusSessionService = FocusSessionService;
