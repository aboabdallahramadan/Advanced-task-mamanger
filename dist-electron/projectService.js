"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.ProjectService = void 0;
const database_1 = require("./database");
const uuid_1 = require("uuid");
class ProjectService {
    db;
    constructor(db) {
        this.db = db;
    }
    mapRow(row) {
        return {
            id: row.id,
            name: row.name,
            color: row.color,
            emoji: row.emoji,
            order: row.sort_order,
            createdAt: row.created_at,
            updatedAt: row.updated_at,
        };
    }
    getAll() {
        const stmt = this.db.prepare('SELECT * FROM projects ORDER BY sort_order ASC, created_at ASC');
        const results = [];
        while (stmt.step()) {
            results.push(this.mapRow(stmt.getAsObject()));
        }
        stmt.free();
        return results;
    }
    getById(id) {
        const stmt = this.db.prepare('SELECT * FROM projects WHERE id = ?');
        stmt.bind([id]);
        if (stmt.step()) {
            const project = this.mapRow(stmt.getAsObject());
            stmt.free();
            return project;
        }
        stmt.free();
        return null;
    }
    create(input) {
        const id = (0, uuid_1.v4)();
        const now = new Date().toISOString();
        this.db.run(`INSERT INTO projects (id, name, color, emoji, sort_order, created_at, updated_at)
             VALUES (?, ?, ?, ?, ?, ?, ?)`, [
            id,
            input.name,
            input.color || '#6366f1',
            input.emoji || '📁',
            0,
            now,
            now,
        ]);
        (0, database_1.saveDatabase)();
        return this.getById(id);
    }
    update(id, updates) {
        const sets = [];
        const values = [];
        if (updates.name !== undefined) {
            sets.push('name = ?');
            values.push(updates.name);
        }
        if (updates.color !== undefined) {
            sets.push('color = ?');
            values.push(updates.color);
        }
        if (updates.emoji !== undefined) {
            sets.push('emoji = ?');
            values.push(updates.emoji);
        }
        if (updates.order !== undefined) {
            sets.push('sort_order = ?');
            values.push(updates.order);
        }
        if (sets.length === 0)
            return this.getById(id);
        sets.push("updated_at = datetime('now')");
        values.push(id);
        this.db.run(`UPDATE projects SET ${sets.join(', ')} WHERE id = ?`, values);
        (0, database_1.saveDatabase)();
        return this.getById(id);
    }
    delete(id) {
        // Also clear the project field from tasks that reference this project name
        const project = this.getById(id);
        if (project) {
            this.db.run("UPDATE tasks SET project = '' WHERE project = ?", [project.name]);
        }
        this.db.run('DELETE FROM projects WHERE id = ?', [id]);
        (0, database_1.saveDatabase)();
        return true;
    }
    reorder(items) {
        for (const item of items) {
            this.db.run('UPDATE projects SET sort_order = ? WHERE id = ?', [item.order, item.id]);
        }
        (0, database_1.saveDatabase)();
    }
}
exports.ProjectService = ProjectService;
