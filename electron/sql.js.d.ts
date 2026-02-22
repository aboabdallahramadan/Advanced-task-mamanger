declare module 'sql.js' {
    export interface Database {
        run(sql: string, params?: any[]): void;
        exec(sql: string, params?: any[]): any[];
        prepare(sql: string): Statement;
        close(): void;
        getRowsModified(): number;
        export(): Uint8Array;
    }

    export interface Statement {
        bind(params?: any[]): boolean;
        step(): boolean;
        getAsObject(): Record<string, any>;
        free(): void;
    }

    export interface SqlJsStatic {
        Database: new (data?: ArrayLike<number>) => Database;
    }

    export default function initSqlJs(config?: {
        locateFile?: (file: string) => string;
    }): Promise<SqlJsStatic>;
}
