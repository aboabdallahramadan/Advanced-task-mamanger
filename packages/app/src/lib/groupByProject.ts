import { Task, Project } from '../types';

export interface TaskProjectGroup {
  projectId: string | null; // null = the "No Project" bucket
  name: string; // project name, or "No Project"
  color: string | null; // project color, or null for "No Project"
  tasks: Task[];
}

/**
 * Bucket `tasks` by project for display in the planning "Everything else" section.
 *
 * Group order follows the `projects` array (already maintained in the user's project sort order);
 * projects with no matching task are skipped. Tasks with no project — null `projectId`, or a
 * `projectId` whose project is absent from `projects` (e.g. deleted) — collect into a single
 * "No Project" group appended last, only when it has tasks. Task order within each group is
 * preserved (input order).
 */
export function groupByProject(tasks: Task[], projects: Project[]): TaskProjectGroup[] {
  const known = new Set(projects.map((p) => p.id));
  const byProject = new Map<string, Task[]>();
  const noProject: Task[] = [];

  for (const task of tasks) {
    if (task.projectId && known.has(task.projectId)) {
      const bucket = byProject.get(task.projectId);
      if (bucket) bucket.push(task);
      else byProject.set(task.projectId, [task]);
    } else {
      noProject.push(task);
    }
  }

  const groups: TaskProjectGroup[] = [];
  for (const p of projects) {
    const bucket = byProject.get(p.id);
    if (bucket && bucket.length > 0) {
      groups.push({ projectId: p.id, name: p.name, color: p.color, tasks: bucket });
    }
  }
  if (noProject.length > 0) {
    groups.push({ projectId: null, name: 'No Project', color: null, tasks: noProject });
  }
  return groups;
}
