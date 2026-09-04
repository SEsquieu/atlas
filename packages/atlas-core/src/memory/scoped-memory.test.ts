import assert from 'node:assert/strict';
import test from 'node:test';
import { createSessionState } from '../session/index.js';
import { visibleMemory } from './scoped-memory.js';

test('memory visibility follows session ownership and task scope', () => {
  const session = createSessionState({
    sessionId: 'session-1', provider: { id: 'fake', adapter: 'fake' },
    workspace: { workspaceId: 'org-1', organizationId: 'org-1', kind: 'organization' },
    actor: { principalId: 'user-1', kind: 'user' },
    taskRun: { taskRunId: 'run-1', status: 'active' }
  });
  session.memory.scoped = [
    memory('workspace-note', 'workspace', 'org-1'),
    memory('task-note', 'task', 'run-1'),
    memory('other-org-note', 'workspace', 'org-2')
  ];
  assert.deepEqual(visibleMemory(session).map((entry) => entry.id), ['workspace-note', 'task-note']);
});

function memory(id: string, scope: 'workspace' | 'task', scopeId: string) {
  return { id, scope, scopeId, kind: 'note', content: id, createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z' } as const;
}
