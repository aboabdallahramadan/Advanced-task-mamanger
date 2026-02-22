import { Database as SqlJsDatabase } from 'sql.js';
export interface Project {
    id: string;
    name: string;
    color: string;
    emoji: string;
    order: number;
    createdAt: string;
    updatedAt: string;
}
export declare class ProjectService {
    private db;
    constructor(db: SqlJsDatabase);
    private mapRow;
    getAll(): Project[];
    getById(id: string): Project | null;
    create(input: {
        name: string;
        color?: string;
        emoji?: string;
    }): Project;
    update(id: string, updates: Partial<{
        name: string;
        color: string;
        emoji: string;
        order: number;
    }>): Project;
    delete(id: string): boolean;
    reorder(items: {
        id: string;
        order: number;
    }[]): void;
}
