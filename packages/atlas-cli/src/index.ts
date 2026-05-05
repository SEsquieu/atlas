#!/usr/bin/env node
import { runAtlasCli } from './commands.js';

const result = await runAtlasCli(process.argv.slice(2));

if (result.stdout) console.log(result.stdout);
if (result.stderr) console.error(result.stderr);
process.exitCode = result.exitCode;
