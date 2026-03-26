import { Database as SqlJsDatabase } from 'sql.js';
import { saveDatabase } from './database';
import { v4 as uuidv4 } from 'uuid';

export interface NoteGroup {
    id: string;
    name: string;
    emoji: string;
    projectId: string | null;
    order: number;
    createdAt: string;
    updatedAt: string;
}

export interface Note {
    id: string;
    groupId: string | null;
    projectId: string | null;
    title: string;
    content: string;
    order: number;
    createdAt: string;
    updatedAt: string;
}

export class NoteService {
    constructor(private db: SqlJsDatabase) {}

    // ── NoteGroup methods ──────────────────────────────────────────

    private mapGroupRow(row: any): NoteGroup {
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

    getAllGroups(): NoteGroup[] {
        const stmt = this.db.prepare(
            'SELECT * FROM note_groups ORDER BY sort_order ASC, created_at ASC',
        );
        const results: NoteGroup[] = [];
        while (stmt.step()) {
            results.push(this.mapGroupRow(stmt.getAsObject()));
        }
        stmt.free();
        return results;
    }

    getGroupById(id: string): NoteGroup | null {
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

    getGroupsByProject(projectId: string): NoteGroup[] {
        const stmt = this.db.prepare(
            'SELECT * FROM note_groups WHERE project_id = ? ORDER BY sort_order ASC, created_at ASC',
        );
        stmt.bind([projectId]);
        const results: NoteGroup[] = [];
        while (stmt.step()) {
            results.push(this.mapGroupRow(stmt.getAsObject()));
        }
        stmt.free();
        return results;
    }

    createGroup(input: { name: string; emoji?: string; projectId?: string }): NoteGroup {
        const id = uuidv4();
        const now = new Date().toISOString();

        this.db.run(
            `INSERT INTO note_groups (id, name, emoji, project_id, sort_order, created_at, updated_at)
             VALUES (?, ?, ?, ?, ?, ?, ?)`,
            [id, input.name, input.emoji || '📝', input.projectId || null, 0, now, now],
        );

        saveDatabase();
        return this.getGroupById(id)!;
    }

    updateGroup(
        id: string,
        updates: Partial<{ name: string; emoji: string; projectId: string | null; order: number }>,
    ): NoteGroup {
        const sets: string[] = [];
        const values: any[] = [];

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

        if (sets.length === 0) return this.getGroupById(id)!;

        sets.push("updated_at = datetime('now')");
        values.push(id);

        this.db.run(`UPDATE note_groups SET ${sets.join(', ')} WHERE id = ?`, values);

        saveDatabase();
        return this.getGroupById(id)!;
    }

    deleteGroup(id: string): boolean {
        this.db.run('DELETE FROM note_groups WHERE id = ?', [id]);
        saveDatabase();
        return true;
    }

    reorderGroups(items: { id: string; order: number }[]): void {
        for (const item of items) {
            this.db.run('UPDATE note_groups SET sort_order = ? WHERE id = ?', [
                item.order,
                item.id,
            ]);
        }
        saveDatabase();
    }

    // ── Note methods ───────────────────────────────────────────────

    private mapNoteRow(row: any): Note {
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

    getNotesByGroup(groupId: string): Note[] {
        const stmt = this.db.prepare(
            'SELECT * FROM notes WHERE group_id = ? ORDER BY sort_order ASC, created_at ASC',
        );
        stmt.bind([groupId]);
        const results: Note[] = [];
        while (stmt.step()) {
            results.push(this.mapNoteRow(stmt.getAsObject()));
        }
        stmt.free();
        return results;
    }

    getNotesByProject(projectId: string): Note[] {
        const stmt = this.db.prepare(
            'SELECT * FROM notes WHERE project_id = ? ORDER BY sort_order ASC, created_at ASC',
        );
        stmt.bind([projectId]);
        const results: Note[] = [];
        while (stmt.step()) {
            results.push(this.mapNoteRow(stmt.getAsObject()));
        }
        stmt.free();
        return results;
    }

    getNoteById(id: string): Note | null {
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

    createNote(input: { groupId?: string; projectId?: string; title?: string; content?: string }): Note {
        const id = uuidv4();
        const now = new Date().toISOString();

        this.db.run(
            `INSERT INTO notes (id, group_id, project_id, title, content, sort_order, created_at, updated_at)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
            [id, input.groupId || null, input.projectId || null, input.title || 'Untitled', input.content || '', 0, now, now],
        );

        saveDatabase();
        return this.getNoteById(id)!;
    }

    updateNote(
        id: string,
        updates: Partial<{ title: string; content: string; groupId: string; projectId: string; order: number }>,
    ): Note {
        const sets: string[] = [];
        const values: any[] = [];

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

        if (sets.length === 0) return this.getNoteById(id)!;

        sets.push("updated_at = datetime('now')");
        values.push(id);

        this.db.run(`UPDATE notes SET ${sets.join(', ')} WHERE id = ?`, values);

        saveDatabase();
        return this.getNoteById(id)!;
    }

    deleteNote(id: string): boolean {
        this.db.run('DELETE FROM notes WHERE id = ?', [id]);
        saveDatabase();
        return true;
    }

    reorderNotes(items: { id: string; order: number }[]): void {
        for (const item of items) {
            this.db.run('UPDATE notes SET sort_order = ? WHERE id = ?', [item.order, item.id]);
        }
        saveDatabase();
    }

    getNoteCountByGroup(groupId: string): number {
        const stmt = this.db.prepare('SELECT COUNT(*) as count FROM notes WHERE group_id = ?');
        stmt.bind([groupId]);
        let count = 0;
        if (stmt.step()) {
            count = (stmt.getAsObject() as any).count;
        }
        stmt.free();
        return count;
    }
}
