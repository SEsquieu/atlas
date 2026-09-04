import type { TaskRunState, TaskRunStatus } from '../types.js';

const transitions: Record<TaskRunStatus, TaskRunStatus[]> = {
  pending: ['active', 'cancelled'],
  active: ['blocked', 'completed', 'cancelled'],
  blocked: ['active', 'cancelled'],
  completed: [],
  cancelled: []
};

export function transitionTaskRun(task: TaskRunState, status: TaskRunStatus, at = new Date().toISOString()): TaskRunState {
  if (task.status === status) return task;
  if (!transitions[task.status].includes(status)) throw new Error(`Invalid task-run transition: ${task.status} -> ${status}`);
  return {
    ...task,
    status,
    startedAt: status === 'active' ? task.startedAt ?? at : task.startedAt,
    completedAt: status === 'completed' ? at : task.completedAt
  };
}
