import { mkdir, readFile, readdir, writeFile, appendFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import type { AtlasSessionState } from '../types.js';
import type { AuditEvent } from '../audit/event-log.js';
import type { SessionStore, StoredSessionRecord } from './types.js';

export type FileSessionStoreOptions = {
  rootDir: string;
};

export class FileSessionStore implements SessionStore {
  readonly rootDir: string;

  constructor(options: FileSessionStoreOptions) {
    this.rootDir = options.rootDir;
  }

  async create(state: AtlasSessionState): Promise<void> {
    await this.ensureSessionDir(state.sessionId);
    await this.saveState(state);
    await writeFile(this.eventsPath(state.sessionId), '', { flag: 'a' });
  }

  async saveState(state: AtlasSessionState): Promise<void> {
    const path = this.statePath(state.sessionId);
    await mkdir(dirname(path), { recursive: true });
    await writeFile(path, `${JSON.stringify(state, null, 2)}\n`, 'utf8');
  }

  async loadState(sessionId: string): Promise<AtlasSessionState | undefined> {
    try {
      return JSON.parse(await readFile(this.statePath(sessionId), 'utf8')) as AtlasSessionState;
    } catch (error) {
      if (isNotFound(error)) return undefined;
      throw error;
    }
  }

  async appendEvent(
    sessionId: string,
    event: Omit<AuditEvent, 'id' | 'at'> & Partial<Pick<AuditEvent, 'id' | 'at'>>
  ): Promise<AuditEvent> {
    await this.ensureSessionDir(sessionId);
    const state = await this.loadState(sessionId);
    const normalized: AuditEvent = {
      id: event.id ?? crypto.randomUUID(),
      // A fresh checkpoint has no event cursor yet. Ensure its first implicit event is
      // strictly newer than updatedAt so millisecond clock collisions cannot hide it.
      at: event.at ?? nextEventTime(state?.updatedAt),
      type: event.type,
      workspaceId: event.workspaceId ?? state?.workspace?.workspaceId,
      organizationId: event.organizationId ?? state?.workspace?.organizationId,
      principalId: event.principalId ?? state?.actor?.principalId,
      siteId: event.siteId ?? state?.placement?.siteId,
      stationId: event.stationId ?? state?.placement?.stationId,
      sessionId: event.sessionId ?? sessionId,
      taskRunId: event.taskRunId ?? state?.taskRun?.taskRunId,
      procedureRevisionId: event.procedureRevisionId ?? state?.taskRun?.procedure?.revisionId,
      correlationId: event.correlationId,
      causationId: event.causationId,
      data: event.data
    };
    await appendFile(this.eventsPath(sessionId), `${JSON.stringify(normalized)}\n`, 'utf8');
    return normalized;
  }

  async loadEvents(sessionId: string): Promise<AuditEvent[]> {
    try {
      const text = await readFile(this.eventsPath(sessionId), 'utf8');
      return text
        .split(/\r?\n/)
        .filter((line) => line.trim().length > 0)
        .map((line) => JSON.parse(line) as AuditEvent);
    } catch (error) {
      if (isNotFound(error)) return [];
      throw error;
    }
  }

  async load(sessionId: string): Promise<StoredSessionRecord | undefined> {
    const state = await this.loadState(sessionId);
    if (!state) return undefined;
    return {
      state,
      events: await this.loadEvents(sessionId)
    };
  }

  async listSessionIds(): Promise<string[]> {
    try {
      const entries = await readdir(this.rootDir, { withFileTypes: true });
      return entries.filter((entry) => entry.isDirectory()).map((entry) => entry.name).sort();
    } catch (error) {
      if (isNotFound(error)) return [];
      throw error;
    }
  }

  private async ensureSessionDir(sessionId: string): Promise<void> {
    await mkdir(this.sessionDir(sessionId), { recursive: true });
  }

  private sessionDir(sessionId: string): string {
    assertSafeSessionId(sessionId);
    return join(this.rootDir, sessionId);
  }

  private statePath(sessionId: string): string {
    return join(this.sessionDir(sessionId), 'state.json');
  }

  private eventsPath(sessionId: string): string {
    return join(this.sessionDir(sessionId), 'events.jsonl');
  }
}

function nextEventTime(checkpointAt?: string): string {
  const now = Date.now();
  const checkpointMs = checkpointAt ? Date.parse(checkpointAt) : Number.NaN;
  return new Date(Number.isFinite(checkpointMs) ? Math.max(now, checkpointMs + 1) : now).toISOString();
}

function isNotFound(error: unknown): boolean {
  return typeof error === 'object' && error !== null && 'code' in error && error.code === 'ENOENT';
}

function assertSafeSessionId(sessionId: string): void {
  if (!/^[a-zA-Z0-9._-]+$/.test(sessionId)) {
    throw new Error(`Unsafe session id: ${sessionId}`);
  }
}
