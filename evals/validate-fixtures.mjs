import assert from 'node:assert/strict';
import { readdir, readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.dirname(fileURLToPath(import.meta.url));
const directory = path.join(root, 'fixtures');
const names = (await readdir(directory)).filter((name) => name.endsWith('.json')).sort();
assert.ok(names.length > 0, 'At least one eval fixture is required.');

for (const name of names) {
  const fixture = JSON.parse(await readFile(path.join(directory, name), 'utf8'));
  assert.match(fixture.id, /^[a-z0-9_]+$/, `${name}: invalid id`);
  assert.equal(path.basename(name, '.json'), fixture.id, `${name}: filename must match id`);
  assert.ok(['synthetic', 'anonymized_real_session'].includes(fixture.origin), `${name}: invalid origin`);
  assertUnique(fixture.dimensions, `${name}: dimensions`);

  const facts = new Map();
  for (const fact of fixture.facts ?? []) {
    assert.ok(fact.id && fact.claim && fact.source, `${name}: incomplete fact`);
    assert.ok(!facts.has(fact.id), `${name}: duplicate fact ${fact.id}`);
    facts.set(fact.id, fact);
  }
  for (const fact of facts.values()) {
    if (fact.supersedes) assert.ok(facts.has(fact.supersedes), `${name}: unknown superseded fact ${fact.supersedes}`);
  }

  const turns = new Map();
  for (const turn of fixture.turns ?? []) {
    assert.ok(turn.id && ['user', 'assistant'].includes(turn.role) && turn.text, `${name}: incomplete turn`);
    assert.ok(!turns.has(turn.id), `${name}: duplicate turn ${turn.id}`);
    turns.set(turn.id, turn);
  }

  const expectationIds = new Set();
  for (const expectation of fixture.expectations ?? []) {
    assert.ok(expectation.id && expectation.assertion, `${name}: incomplete expectation`);
    assert.ok(!expectationIds.has(expectation.id), `${name}: duplicate expectation ${expectation.id}`);
    expectationIds.add(expectation.id);
    assert.ok(turns.has(expectation.at_turn), `${name}: expectation references unknown turn ${expectation.at_turn}`);
    assert.ok(fixture.dimensions.includes(expectation.dimension), `${name}: undeclared dimension ${expectation.dimension}`);
    assert.ok(['info', 'quality', 'safety'].includes(expectation.severity), `${name}: invalid severity`);
    for (const factId of expectation.fact_ids ?? []) assert.ok(facts.has(factId), `${name}: unknown fact ${factId}`);
  }
  assert.ok(expectationIds.size > 0, `${name}: at least one expectation is required`);
}

console.log(`Validated ${names.length} Atlas behavioral regression fixture${names.length === 1 ? '' : 's'}.`);

function assertUnique(values, label) {
  assert.ok(Array.isArray(values) && values.length > 0, `${label} must be non-empty`);
  assert.equal(new Set(values).size, values.length, `${label} must be unique`);
}
