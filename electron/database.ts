import initSqlJs, { Database as SqlJsDatabase } from 'sql.js';
import fs from 'fs';
import path from 'path';

let db: SqlJsDatabase;
let dbPath: string;

export async function initDatabase(filePath: string): Promise<void> {
    dbPath = filePath;
    const SQL = await initSqlJs();

    if (fs.existsSync(dbPath)) {
        const fileBuffer = fs.readFileSync(dbPath);
        db = new SQL.Database(fileBuffer);
    } else {
        db = new SQL.Database();
    }

    db.run('PRAGMA journal_mode = WAL;');
    db.run('PRAGMA foreign_keys = ON;');
    runMigrations();
    saveDatabase();
}

export function getDatabase(): SqlJsDatabase {
    if (!db) throw new Error('Database not initialized. Call initDatabase first.');
    return db;
}

export function saveDatabase(): void {
    if (!db || !dbPath) return;
    const data = db.export();
    const buffer = Buffer.from(data);
    const dir = path.dirname(dbPath);
    if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(dbPath, buffer);
}

function runMigrations(): void {
    db.run(`
    CREATE TABLE IF NOT EXISTS migrations (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      name TEXT NOT NULL UNIQUE,
      applied_at TEXT NOT NULL DEFAULT (datetime('now'))
    );
  `);

    const applied = new Set<string>();
    const result = db.exec('SELECT name FROM migrations');
    if (result.length > 0) {
        for (const row of result[0].values) {
            applied.add(row[0] as string);
        }
    }

    for (const migration of migrations) {
        if (!applied.has(migration.name)) {
            db.run('BEGIN TRANSACTION;');
            try {
                // sql.js doesn't support multiple statements in one run, split them
                const statements = migration.sql
                    .split(';')
                    .map((s) => s.trim())
                    .filter((s) => s.length > 0);
                for (const stmt of statements) {
                    db.run(stmt + ';');
                }
                db.run('INSERT INTO migrations (name) VALUES (?);', [migration.name]);
                db.run('COMMIT;');
                console.log(`Applied migration: ${migration.name}`);
            } catch (e) {
                db.run('ROLLBACK;');
                throw e;
            }
        }
    }
}

const migrations = [
    {
        name: '001_create_tasks',
        sql: `
      CREATE TABLE IF NOT EXISTS tasks (
        id TEXT PRIMARY KEY,
        title TEXT NOT NULL,
        notes TEXT DEFAULT '',
        project TEXT DEFAULT '',
        labels TEXT DEFAULT '[]',
        source TEXT DEFAULT 'local',
        status TEXT NOT NULL DEFAULT 'inbox'
          CHECK (status IN ('inbox', 'backlog', 'planned', 'scheduled', 'done', 'archived')),
        due_date TEXT,
        planned_date TEXT,
        scheduled_start TEXT,
        scheduled_end TEXT,
        duration_minutes INTEGER DEFAULT 30,
        sort_order INTEGER DEFAULT 0,
        created_at TEXT NOT NULL DEFAULT (datetime('now')),
        updated_at TEXT NOT NULL DEFAULT (datetime('now'))
      );
      CREATE INDEX IF NOT EXISTS idx_tasks_status ON tasks(status);
      CREATE INDEX IF NOT EXISTS idx_tasks_planned_date ON tasks(planned_date);
      CREATE INDEX IF NOT EXISTS idx_tasks_scheduled ON tasks(scheduled_start, scheduled_end)
    `,
    },
];
