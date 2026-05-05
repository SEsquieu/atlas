import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createStaticAdapterRegistry } from './registry.js';
import { createFakeCameraDevice, createFakeProvider, createFakeVisualAnalyzer } from '../testing/fakes.js';

test('createStaticAdapterRegistry resolves provider, devices, and analyzers by id or adapter/name', () => {
  const provider = createFakeProvider({ id: 'provider-id', name: 'provider-name' });
  const device = createFakeCameraDevice({ id: 'device-id', name: 'device-name' });
  const analyzer = createFakeVisualAnalyzer({ id: 'analyzer-id', name: 'analyzer-name' });
  const registry = createStaticAdapterRegistry({ providers: [provider], devices: [device], analyzers: [analyzer] });

  assert.equal(registry.resolveProvider({ id: 'provider-id', adapter: 'missing' })?.id, 'provider-id');
  assert.equal(registry.resolveProvider({ id: 'missing', adapter: 'provider-name' })?.id, 'provider-id');
  assert.equal(registry.resolveDevices([{ id: 'missing', adapter: 'device-name', capabilities: ['camera.capture'] }])[0]?.id, 'device-id');
  assert.equal(registry.resolveAnalyzers?.(['analyzer-name'])[0]?.id, 'analyzer-id');
});
