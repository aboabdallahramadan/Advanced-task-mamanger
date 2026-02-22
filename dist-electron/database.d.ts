import { Database as SqlJsDatabase } from 'sql.js';
export declare function initDatabase(filePath: string): Promise<void>;
export declare function getDatabase(): SqlJsDatabase;
export declare function saveDatabase(): void;
