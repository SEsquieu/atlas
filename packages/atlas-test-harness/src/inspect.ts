import { resolve } from 'node:path';
import { FileSessionStore, inspectSession } from '@atlas/core';

const sessionId = process.argv[2] ?? 'demo-session';
const repoRoot = process.env.ATLAS_ROOT ?? process.env.INIT_CWD ?? process.cwd();
const store = new FileSessionStore({ rootDir: resolve(repoRoot, '.atlas-cache/sessions') });

const inspection = await inspectSession(store, sessionId);
if (!inspection) {
  console.error(`Session not found: ${sessionId}`);
  process.exitCode = 1;
} else {
  console.log(JSON.stringify(inspection, null, 2));
}
