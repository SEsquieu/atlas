import type { AtlasSessionState, MemoryScopeKind, ScopedMemoryEntry } from '../types.js';

export type MemoryQuery = {
  scopes?: MemoryScopeKind[];
  scopeIds?: string[];
  kinds?: string[];
  now?: string;
};

export function visibleMemory(session: AtlasSessionState, query: MemoryQuery = {}): ScopedMemoryEntry[] {
  const now = Date.parse(query.now ?? new Date().toISOString());
  const permittedScopeIds = new Set([
    session.sessionId,
    session.workspace.workspaceId,
    session.actor?.principalId,
    session.taskRun?.taskRunId,
    session.placement?.stationId,
    ...(query.scopeIds ?? [])
  ].filter((value): value is string => Boolean(value)));

  return session.memory.scoped.filter((entry) => {
    if (!permittedScopeIds.has(entry.scopeId)) return false;
    if (query.scopes && !query.scopes.includes(entry.scope)) return false;
    if (query.kinds && !query.kinds.includes(entry.kind)) return false;
    return !entry.expiresAt || Date.parse(entry.expiresAt) > now;
  });
}

export function upsertScopedMemory(entries: ScopedMemoryEntry[], incoming: ScopedMemoryEntry): ScopedMemoryEntry[] {
  const index = entries.findIndex((entry) => entry.id === incoming.id);
  if (index < 0) return [...entries, incoming];
  const next = [...entries];
  next[index] = incoming;
  return next;
}
