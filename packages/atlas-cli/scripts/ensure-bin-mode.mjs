import { chmod } from 'node:fs/promises';

try {
  await chmod(new URL('../dist/index.js', import.meta.url), 0o755);
} catch {
  // Best effort. Windows does not need executable mode for npm bin shims.
}
