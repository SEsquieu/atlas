import type { AtlasToolSchema } from '../types.js';

export const CORE_PHYSICAL_TOOLS: AtlasToolSchema[] = [
  {
    name: 'capture_current_view',
    description: 'Capture a fresh image from the bound device when current visual context is stale, unstable, or insufficient.',
    parameters: {
      reason: 'string',
      quality: 'low | medium | high'
    }
  },
  {
    name: 'get_current_location',
    description: 'Fetch current location from the bound device.',
    parameters: {
      reason: 'string'
    }
  },
  {
    name: 'speak_to_user',
    description: 'Speak a short response through the bound device.',
    parameters: {
      text: 'string'
    }
  }
];
