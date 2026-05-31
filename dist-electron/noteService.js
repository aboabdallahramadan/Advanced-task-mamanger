"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.NoteService = void 0;
const database_1 = require("./database");
const uuid_1 = require("uuid");
class NoteService {
    db;
    constructor(db) {
        this.db = db;
    }
    // ── NoteGroup methods ──────────────────────────────────────────
    mapGroupRow(row) {
        return {
            id: row.id,
            name: row.name,
            emoji: row.emoji,
            projectId: row.project_id || null,
            order: row.sort_order,
            createdAt: row.created_at,
            updatedAt: row.updated_at,
        };
    }
    getAllGroups() {
        const stmt = this.db.prepare('SELECT * FROM note_groups ORDER BY sort_order ASC, created_at ASC');
        const results = [];
        while (stmt.step()) {
            results.push(this.mapGroupRow(stmt.getAsObject()));
        }
        stmt.free();
        return results;
    }
    getGroupById(id) {
        const stmt = this.db.prepare('SELECT * FROM note_groups WHERE id = ?');
        stmt.bind([id]);
        if (stmt.step()) {
            const group = this.mapGroupRow(stmt.getAsObject());
            stmt.free();
            return group;
        }
        stmt.free();
        return null;
    }
    getGroupsByProject(projectId) {
        const stmt = this.db.prepare('SELECT * FROM note_groups WHERE project_id = ? ORDER BY sort_order ASC, created_at ASC');
        stmt.bind([projectId]);
        const results = [];
        while (stmt.step()) {
            results.push(this.mapGroupRow(stmt.getAsObject()));
        }
        stmt.free();
        return results;
    }
    createGroup(input) {
        const id = (0, uuid_1.v4)();
        const now = new Date().toISOString();
        this.db.run(`INSERT INTO note_groups (id, name, emoji, project_id, sort_order, created_at, updated_at)
             VALUES (?, ?, ?, ?, ?, ?, ?)`, [id, input.name, input.emoji || '📝', input.projectId || null, 0, now, now]);
        (0, database_1.saveDatabase)();
        return this.getGroupById(id);
    }
    updateGroup(id, updates) {
        const sets = [];
        const values = [];
        if (updates.name !== undefined) {
            sets.push('name = ?');
            values.push(updates.name);
        }
        if (updates.emoji !== undefined) {
            sets.push('emoji = ?');
            values.push(updates.emoji);
        }
        if (updates.projectId !== undefined) {
            sets.push('project_id = ?');
            values.push(updates.projectId);
        }
        if (updates.order !== undefined) {
            sets.push('sort_order = ?');
            values.push(updates.order);
        }
        if (sets.length === 0)
            return this.getGroupById(id);
        sets.push("updated_at = datetime('now')");
        values.push(id);
        this.db.run(`UPDATE note_groups SET ${sets.join(', ')} WHERE id = ?`, values);
        (0, database_1.saveDatabase)();
        return this.getGroupById(id);
    }
    deleteGroup(id) {
        this.db.run('DELETE FROM note_groups WHERE id = ?', [id]);
        (0, database_1.saveDatabase)();
        return true;
    }
    reorderGroups(items) {
        for (const item of items) {
            this.db.run('UPDATE note_groups SET sort_order = ? WHERE id = ?', [item.order, item.id]);
        }
        (0, database_1.saveDatabase)();
    }
    // ── Note methods ───────────────────────────────────────────────
    mapNoteRow(row) {
        return {
            id: row.id,
            groupId: row.group_id || null,
            projectId: row.project_id || null,
            title: row.title,
            content: row.content,
            order: row.sort_order,
            createdAt: row.created_at,
            updatedAt: row.updated_at,
        };
    }
    getAllNotes() {
        const stmt = this.db.prepare('SELECT * FROM notes ORDER BY updated_at DESC');
        const results = [];
        while (stmt.step()) {
            results.push(this.mapNoteRow(stmt.getAsObject()));
        }
        stmt.free();
        return results;
    }
    getNotesByGroup(groupId) {
        const stmt = this.db.prepare('SELECT * FROM notes WHERE group_id = ? ORDER BY sort_order ASC, created_at ASC');
        stmt.bind([groupId]);
        const results = [];
        while (stmt.step()) {
            results.push(this.mapNoteRow(stmt.getAsObject()));
        }
        stmt.free();
        return results;
    }
    getNotesByProject(projectId) {
        const stmt = this.db.prepare('SELECT * FROM notes WHERE project_id = ? ORDER BY sort_order ASC, created_at ASC');
        stmt.bind([projectId]);
        const results = [];
        while (stmt.step()) {
            results.push(this.mapNoteRow(stmt.getAsObject()));
        }
        stmt.free();
        return results;
    }
    getNoteById(id) {
        const stmt = this.db.prepare('SELECT * FROM notes WHERE id = ?');
        stmt.bind([id]);
        if (stmt.step()) {
            const note = this.mapNoteRow(stmt.getAsObject());
            stmt.free();
            return note;
        }
        stmt.free();
        return null;
    }
    createNote(input) {
        const id = (0, uuid_1.v4)();
        const now = new Date().toISOString();
        this.db.run(`INSERT INTO notes (id, group_id, project_id, title, content, sort_order, created_at, updated_at)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?)`, [
            id,
            input.groupId || null,
            input.projectId || null,
            input.title || 'Untitled',
            input.content || '',
            0,
            now,
            now,
        ]);
        (0, database_1.saveDatabase)();
        return this.getNoteById(id);
    }
    updateNote(id, updates) {
        const sets = [];
        const values = [];
        if (updates.title !== undefined) {
            sets.push('title = ?');
            values.push(updates.title);
        }
        if (updates.content !== undefined) {
            sets.push('content = ?');
            values.push(updates.content);
        }
        if (updates.groupId !== undefined) {
            sets.push('group_id = ?');
            values.push(updates.groupId);
        }
        if (updates.projectId !== undefined) {
            sets.push('project_id = ?');
            values.push(updates.projectId);
        }
        if (updates.order !== undefined) {
            sets.push('sort_order = ?');
            values.push(updates.order);
        }
        if (sets.length === 0)
            return this.getNoteById(id);
        sets.push("updated_at = datetime('now')");
        values.push(id);
        this.db.run(`UPDATE notes SET ${sets.join(', ')} WHERE id = ?`, values);
        (0, database_1.saveDatabase)();
        return this.getNoteById(id);
    }
    deleteNote(id) {
        this.db.run('DELETE FROM notes WHERE id = ?', [id]);
        (0, database_1.saveDatabase)();
        return true;
    }
    reorderNotes(items) {
        for (const item of items) {
            this.db.run('UPDATE notes SET sort_order = ? WHERE id = ?', [item.order, item.id]);
        }
        (0, database_1.saveDatabase)();
    }
    getNoteCountByGroup(groupId) {
        const stmt = this.db.prepare('SELECT COUNT(*) as count FROM notes WHERE group_id = ?');
        stmt.bind([groupId]);
        let count = 0;
        if (stmt.step()) {
            count = stmt.getAsObject().count;
        }
        stmt.free();
        return count;
    }
}
exports.NoteService = NoteService;
