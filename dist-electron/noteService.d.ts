import { Database as SqlJsDatabase } from 'sql.js';
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
export declare class NoteService {
    private db;
    constructor(db: SqlJsDatabase);
    private mapGroupRow;
    getAllGroups(): NoteGroup[];
    getGroupById(id: string): NoteGroup | null;
    getGroupsByProject(projectId: string): NoteGroup[];
    createGroup(input: {
        name: string;
        emoji?: string;
        projectId?: string;
    }): NoteGroup;
    updateGroup(id: string, updates: Partial<{
        name: string;
        emoji: string;
        projectId: string | null;
        order: number;
    }>): NoteGroup;
    deleteGroup(id: string): boolean;
    reorderGroups(items: {
        id: string;
        order: number;
    }[]): void;
    private mapNoteRow;
    getAllNotes(): Note[];
    getNotesByGroup(groupId: string): Note[];
    getNotesByProject(projectId: string): Note[];
    getNoteById(id: string): Note | null;
    createNote(input: {
        groupId?: string;
        projectId?: string;
        title?: string;
        content?: string;
    }): Note;
    updateNote(id: string, updates: Partial<{
        title: string;
        content: string;
        groupId: string;
        projectId: string;
        order: number;
    }>): Note;
    deleteNote(id: string): boolean;
    reorderNotes(items: {
        id: string;
        order: number;
    }[]): void;
    getNoteCountByGroup(groupId: string): number;
}
