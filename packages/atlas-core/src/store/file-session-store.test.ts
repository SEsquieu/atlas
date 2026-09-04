import assert from 'node:assert/strict';
import test from 'node:test';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createSessionState } from '../session/index.js';
import { FileSessionStore } from './file-session-store.js';

test('file event log enriches events from authoritative session scope', async () => {
  const root = await mkdtemp(join(tmpdir(), 'atlas-scope-'));
  try {
    const store = new FileSessionStore({ rootDir: root });
    const session = createSessionState({
      sessionId: 'session-1', provider: { id: 'fake', adapter: 'fake' },
      workspace: { workspaceId: 'org-1', organizationId: 'org-1', kind: 'organization' },
      actor: { principalId: 'user-1', kind: 'user' },
      placement: { siteId: 'site-1', stationId: 'station-1' },
      taskRun: { taskRunId: 'run-1', status: 'active', procedure: { procedureId: 'procedure-1', revisionId: 'revision-3', version: '3' } }
    });
    await store.create(session);
    const event = await store.appendEvent(session.sessionId, { type: 'test.event' });
    assert.deepEqual(
      { workspaceId: event.workspaceId, organizationId: event.organizationId, principalId: event.principalId, siteId: event.siteId, stationId: event.stationId, sessionId: event.sessionId, taskRunId: event.taskRunId, procedureRevisionId: event.procedureRevisionId },
      { workspaceId: 'org-1', organizationId: 'org-1', principalId: 'user-1', siteId: 'site-1', stationId: 'station-1', sessionId: 'session-1', taskRunId: 'run-1', procedureRevisionId: 'revision-3' }
    );
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
