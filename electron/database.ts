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
    {
        name: '002_add_actual_time',
        sql: `
      ALTER TABLE tasks ADD COLUMN actual_time_minutes INTEGER DEFAULT 0;
        `,
    },
    {
        name: '003_create_projects',
        sql: `
      CREATE TABLE IF NOT EXISTS projects (
        id TEXT PRIMARY KEY,
        name TEXT NOT NULL,
        color TEXT DEFAULT '#6366f1',
        emoji TEXT DEFAULT '📁',
        sort_order INTEGER DEFAULT 0,
        created_at TEXT NOT NULL DEFAULT (datetime('now')),
        updated_at TEXT NOT NULL DEFAULT (datetime('now'))
      );
      CREATE INDEX IF NOT EXISTS idx_projects_name ON projects(name)
        `,
    },
    {
        name: '004_add_priority',
        sql: `
      ALTER TABLE tasks ADD COLUMN priority INTEGER DEFAULT NULL CHECK (priority IS NULL OR priority IN (1, 2, 3, 4))
        `,
    },
    {
        name: '005_create_subtasks',
        sql: `
      CREATE TABLE IF NOT EXISTS subtasks (
        id TEXT PRIMARY KEY,
        task_id TEXT NOT NULL,
        title TEXT NOT NULL,
        completed INTEGER NOT NULL DEFAULT 0,
        sort_order INTEGER NOT NULL DEFAULT 0,
        created_at TEXT NOT NULL DEFAULT (datetime('now')),
        FOREIGN KEY (task_id) REFERENCES tasks(id) ON DELETE CASCADE
      );
      CREATE INDEX IF NOT EXISTS idx_subtasks_task_id ON subtasks(task_id)
        `,
    },
    {
        name: '006_add_reminder_minutes',
        sql: `ALTER TABLE tasks ADD COLUMN reminder_minutes INTEGER DEFAULT 0`,
    },
    {
        name: '007_add_recurrence',
        sql: `
      CREATE TABLE IF NOT EXISTS recurrence_rules (
        id TEXT PRIMARY KEY,
        frequency TEXT NOT NULL CHECK (frequency IN ('daily', 'weekly')),
        interval_value INTEGER NOT NULL DEFAULT 1,
        days_of_week TEXT DEFAULT '[]',
        end_type TEXT NOT NULL DEFAULT 'never' CHECK (end_type IN ('never', 'count', 'date')),
        end_count INTEGER,
        end_date TEXT,
        generated_until TEXT,
        created_at TEXT NOT NULL DEFAULT (datetime('now')),
        updated_at TEXT NOT NULL DEFAULT (datetime('now'))
      );
      CREATE TABLE IF NOT EXISTS recurrence_exceptions (
        id TEXT PRIMARY KEY,
        recurrence_rule_id TEXT NOT NULL,
        exception_date TEXT NOT NULL,
        created_at TEXT NOT NULL DEFAULT (datetime('now')),
        FOREIGN KEY (recurrence_rule_id) REFERENCES recurrence_rules(id) ON DELETE CASCADE
      );
      CREATE UNIQUE INDEX IF NOT EXISTS idx_recurrence_exceptions_unique ON recurrence_exceptions(recurrence_rule_id, exception_date);
      ALTER TABLE tasks ADD COLUMN recurrence_rule_id TEXT REFERENCES recurrence_rules(id) ON DELETE SET NULL;
      ALTER TABLE tasks ADD COLUMN is_recurrence_template INTEGER DEFAULT 0;
      ALTER TABLE tasks ADD COLUMN recurrence_detached INTEGER DEFAULT 0;
      ALTER TABLE tasks ADD COLUMN recurrence_original_date TEXT;
      CREATE INDEX IF NOT EXISTS idx_tasks_recurrence_rule ON tasks(recurrence_rule_id)
        `,
    },
    {
        name: '008_create_notes',
        sql: `
      CREATE TABLE IF NOT EXISTS note_groups (
        id TEXT PRIMARY KEY,
        name TEXT NOT NULL,
        emoji TEXT DEFAULT '📝',
        project_id TEXT DEFAULT NULL,
        sort_order INTEGER DEFAULT 0,
        created_at TEXT NOT NULL DEFAULT (datetime('now')),
        updated_at TEXT NOT NULL DEFAULT (datetime('now')),
        FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE SET NULL
      );
      CREATE INDEX IF NOT EXISTS idx_note_groups_project ON note_groups(project_id);
      CREATE TABLE IF NOT EXISTS notes (
        id TEXT PRIMARY KEY,
        group_id TEXT NOT NULL,
        title TEXT NOT NULL DEFAULT 'Untitled',
        content TEXT DEFAULT '',
        sort_order INTEGER DEFAULT 0,
        created_at TEXT NOT NULL DEFAULT (datetime('now')),
        updated_at TEXT NOT NULL DEFAULT (datetime('now')),
        FOREIGN KEY (group_id) REFERENCES note_groups(id) ON DELETE CASCADE
      );
      CREATE INDEX IF NOT EXISTS idx_notes_group ON notes(group_id)
        `,
    },
    {
        name: '009_notes_project_id',
        sql: `
      CREATE TABLE IF NOT EXISTS notes_new (
        id TEXT PRIMARY KEY,
        group_id TEXT DEFAULT NULL,
        project_id TEXT DEFAULT NULL,
        title TEXT NOT NULL DEFAULT 'Untitled',
        content TEXT DEFAULT '',
        sort_order INTEGER DEFAULT 0,
        created_at TEXT NOT NULL DEFAULT (datetime('now')),
        updated_at TEXT NOT NULL DEFAULT (datetime('now')),
        FOREIGN KEY (group_id) REFERENCES note_groups(id) ON DELETE CASCADE,
        FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE
      );
      INSERT INTO notes_new (id, group_id, title, content, sort_order, created_at, updated_at)
        SELECT id, group_id, title, content, sort_order, created_at, updated_at FROM notes;
      DROP TABLE notes;
      ALTER TABLE notes_new RENAME TO notes;
      CREATE INDEX IF NOT EXISTS idx_notes_group ON notes(group_id);
      CREATE INDEX IF NOT EXISTS idx_notes_project ON notes(project_id)
        `,
    },
];
