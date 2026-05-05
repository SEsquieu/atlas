export type AuditEvent = {
  id: string;
  type: string;
  at: string;
  data?: unknown;
};

export type EventLog = {
  append(event: Omit<AuditEvent, 'id' | 'at'> & Partial<Pick<AuditEvent, 'id' | 'at'>>): AuditEvent;
  all(): AuditEvent[];
};

export function createInMemoryEventLog(): EventLog {
  const events: AuditEvent[] = [];

  return {
    append(event) {
      const normalized: AuditEvent = {
        id: event.id ?? crypto.randomUUID(),
        at: event.at ?? new Date().toISOString(),
        type: event.type,
        data: event.data
      };
      events.push(normalized);
      return normalized;
    },
    all() {
      return [...events];
    }
  };
}
