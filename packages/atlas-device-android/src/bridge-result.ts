import type { CaptureImageOptions, Observation, ObservationAnalysis } from '@atlas/core';

export type AndroidBridgeCaptureOptions = CaptureImageOptions & {
  facing?: 'back' | 'front';
  analyze?: boolean;
  analysisMode?: 'ollama' | 'openclaw' | 'none' | string;
  prompt?: string;
  maxWidth?: number;
  quality?: 'low' | 'medium' | 'high';
};

export type AndroidBridgeCaptureResult = {
  path?: string;
  imagePath?: string;
  mediaPath?: string;
  file?: string;
  filename?: string;
  mediaRef?: string;
  summary?: string;
  description?: string;
  analysis?: unknown;
  visionSummary?: string;
  node?: string;
  facing?: string;
  capturedAt?: string;
  [key: string]: unknown;
};

export type NormalizeAndroidBridgeResultOptions = {
  deviceId: string;
  producedBy?: string;
  fallbackCapturedAt?: string;
};

export function normalizeAndroidBridgeCaptureResult(
  result: AndroidBridgeCaptureResult,
  options: NormalizeAndroidBridgeResultOptions
): Observation {
  const mediaRef = firstString(result.mediaRef, result.imagePath, result.mediaPath, result.path, result.file, result.filename);
  const capturedAt = firstString(result.capturedAt) ?? options.fallbackCapturedAt ?? new Date().toISOString();
  const summary = firstString(result.summary, result.visionSummary, result.description) ?? extractAnalysisSummary(result.analysis);
  const confidence = extractAnalysisConfidence(result.analysis);
  const analysis = summary
    ? createBridgeAnalysis({
        observationIdSeed: mediaRef ?? `${options.deviceId}:${capturedAt}`,
        producedBy: options.producedBy ?? 'openclaw/android-camera-bridge',
        summary,
        confidence,
        data: result.analysis
      })
    : undefined;

  const observationId = stableObservationId(mediaRef, capturedAt);

  return {
    id: observationId,
    type: 'image',
    capturedAt,
    deviceId: options.deviceId,
    mediaRef,
    data: {
      bridge: {
        node: result.node,
        facing: result.facing
      }
    },
    quality: {
      confidence
    },
    summary,
    analyses: analysis ? [{ ...analysis, observationId }] : undefined
  };
}

function createBridgeAnalysis(input: {
  observationIdSeed: string;
  producedBy: string;
  summary: string;
  confidence?: number;
  data?: unknown;
}): ObservationAnalysis {
  return {
    id: stableObservationId(`analysis:${input.observationIdSeed}`, input.summary),
    observationId: input.observationIdSeed,
    kind: 'visual-summary',
    producedBy: input.producedBy,
    createdAt: new Date().toISOString(),
    confidence: input.confidence,
    summary: input.summary,
    data: input.data
  };
}

function firstString(...values: unknown[]): string | undefined {
  return values.find((value): value is string => typeof value === 'string' && value.length > 0);
}

function extractAnalysisSummary(analysis: unknown): string | undefined {
  if (typeof analysis === 'string') return analysis;
  if (typeof analysis !== 'object' || analysis === null) return undefined;
  const record = analysis as Record<string, unknown>;
  return firstString(record.summary, record.description, record.text, record.caption, record.result);
}

function extractAnalysisConfidence(analysis: unknown): number | undefined {
  if (typeof analysis !== 'object' || analysis === null) return undefined;
  const record = analysis as Record<string, unknown>;
  const value = record.confidence ?? record.score;
  return typeof value === 'number' ? value : undefined;
}

function stableObservationId(mediaRef: string | undefined, capturedAt: string): string {
  const seed = `${mediaRef ?? 'android-image'}:${capturedAt}`;
  let hash = 0;
  for (let index = 0; index < seed.length; index += 1) {
    hash = (hash * 31 + seed.charCodeAt(index)) >>> 0;
  }
  return `android-image-${hash.toString(16).padStart(8, '0')}`;
}
