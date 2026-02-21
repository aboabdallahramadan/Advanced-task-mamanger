import { TaskService } from './taskService';
import { format, addDays } from 'date-fns';

export function seedDemoData(taskService: TaskService): void {
    const today = format(new Date(), 'yyyy-MM-dd');
    const tomorrow = format(addDays(new Date(), 1), 'yyyy-MM-dd');

    const demoTasks = [
        // Today's planned tasks
        {
            title: 'Review Q1 product roadmap',
            notes: 'Focus on mobile features and API improvements',
            project: 'Product',
            labels: ['review', 'planning'],
            status: 'planned' as const,
            plannedDate: today,
            durationMinutes: 45,
        },
        {
            title: 'Write API documentation for auth endpoints',
            notes: 'Cover OAuth2 flow, token refresh, and error codes',
            project: 'Engineering',
            labels: ['docs', 'api'],
            status: 'planned' as const,
            plannedDate: today,
            durationMinutes: 60,
        },
        {
            title: 'Design review: new dashboard layout',
            notes: 'Review Figma mockups with the design team',
            project: 'Design',
            labels: ['design', 'meeting'],
            status: 'planned' as const,
            plannedDate: today,
            durationMinutes: 30,
        },
        {
            title: 'Fix pagination bug in user list',
            notes: 'Off-by-one error when filtering by role',
            project: 'Engineering',
            labels: ['bug', 'frontend'],
            status: 'planned' as const,
            plannedDate: today,
            durationMinutes: 45,
        },
        {
            title: 'Team standup',
            notes: 'Daily sync with engineering team',
            project: 'Engineering',
            labels: ['meeting'],
            status: 'scheduled' as const,
            plannedDate: today,
            durationMinutes: 15,
            scheduledStart: `${today}T09:00:00`,
            scheduledEnd: `${today}T09:15:00`,
        },
        {
            title: 'Prepare sprint retrospective slides',
            notes: 'Summarize wins, blockers, and action items',
            project: 'Engineering',
            labels: ['meeting', 'planning'],
            status: 'planned' as const,
            plannedDate: today,
            durationMinutes: 30,
        },
        {
            title: 'Code review: PR #234 - payment flow',
            notes: 'Review Stripe integration changes',
            project: 'Engineering',
            labels: ['review', 'code-review'],
            status: 'scheduled' as const,
            plannedDate: today,
            durationMinutes: 30,
            scheduledStart: `${today}T14:00:00`,
            scheduledEnd: `${today}T14:30:00`,
        },

        // Inbox tasks
        {
            title: 'Research state management solutions',
            notes: 'Compare Zustand vs Jotai vs Redux Toolkit for the new project',
            project: 'Research',
            labels: ['research'],
            status: 'inbox' as const,
            durationMinutes: 60,
        },
        {
            title: 'Update CI/CD pipeline for staging',
            notes: 'Add staging deploy step and environment variables',
            project: 'DevOps',
            labels: ['infra'],
            status: 'inbox' as const,
            durationMinutes: 90,
        },
        {
            title: 'Reply to client feedback email',
            notes: 'Address concerns about delivery timeline',
            project: 'Communication',
            labels: ['email', 'client'],
            status: 'inbox' as const,
            durationMinutes: 15,
        },

        // Backlog tasks
        {
            title: 'Implement dark mode toggle',
            notes: 'Add system preference detection and manual toggle',
            project: 'Design',
            labels: ['feature', 'ui'],
            status: 'backlog' as const,
            durationMinutes: 120,
        },
        {
            title: 'Set up monitoring dashboard',
            notes: 'Grafana + Prometheus for API metrics',
            project: 'DevOps',
            labels: ['infra', 'monitoring'],
            status: 'backlog' as const,
            durationMinutes: 180,
        },
        {
            title: 'Write unit tests for billing module',
            notes: 'Target 80% coverage',
            project: 'Engineering',
            labels: ['testing'],
            status: 'backlog' as const,
            durationMinutes: 90,
        },

        // Tomorrow's tasks
        {
            title: 'Sprint planning meeting',
            notes: 'Review backlog and prioritize for next sprint',
            project: 'Engineering',
            labels: ['meeting', 'planning'],
            status: 'planned' as const,
            plannedDate: tomorrow,
            durationMinutes: 60,
        },
        {
            title: 'Onboard new team member',
            notes: 'Walk through codebase, dev setup, and team processes',
            project: 'Engineering',
            labels: ['onboarding'],
            status: 'planned' as const,
            plannedDate: tomorrow,
            durationMinutes: 120,
        },

        // Completed tasks
        {
            title: 'Deploy v2.1.0 to production',
            notes: 'Successful deployment with zero-downtime',
            project: 'DevOps',
            labels: ['deployment'],
            status: 'done' as const,
            plannedDate: today,
            durationMinutes: 30,
        },
        {
            title: 'Update dependencies to latest versions',
            notes: 'All packages updated, no breaking changes found',
            project: 'Engineering',
            labels: ['maintenance'],
            status: 'done' as const,
            plannedDate: today,
            durationMinutes: 45,
        },
    ];

    for (const task of demoTasks) {
        taskService.create(task);
    }

    console.log(`Seeded ${demoTasks.length} demo tasks`);
}
