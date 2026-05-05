import type { AtlasSessionState } from '../types.js';
import type { AuditEvent } from '../audit/event-log.js';

export type StoredSessionRecord = {
  state: AtlasSessionState;
  events: AuditEvent[];
};

export interface SessionStore {
  create(state: AtlasSessionState): Promise<void>;
  saveState(state: AtlasSessionState): Promise<void>;
  loadState(sessionId: string): Promise<AtlasSessionState | undefined>;
  appendEvent(sessionId: string, event: Omit<AuditEvent, 'id' | 'at'> & Partial<Pick<AuditEvent, 'id' | 'at'>>): Promise<AuditEvent>;
  loadEvents(sessionId: string): Promise<AuditEvent[]>;
  load(sessionId: string): Promise<StoredSessionRecord | undefined>;
  listSessionIds(): Promise<string[]>;
}
