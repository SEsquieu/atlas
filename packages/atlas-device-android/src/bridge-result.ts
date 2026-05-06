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
  sourceTempPath?: string;
  workspaceImagePath?: string;
  workspaceLatestPath?: string;
  summary?: string;
  description?: string;
  analysisText?: string;
  analysisState?: string;
  analysisMode?: string;
  analysis?: unknown;
  visionSummary?: string;
  node?: string;
  facing?: string;
  /** Physical sample time, not analysis-completion time. */
  capturedAt?: string;
  observedAt?: string;
  /** Time the staged/analyzed observation became available to Atlas. */
  availableAt?: string;
  timings?: AndroidBridgeTimings;
  timestamps?: AndroidBridgeTimestamps;
  details?: AndroidBridgeCaptureResult;
  [key: string]: unknown;
};

export type AndroidBridgeTimings = {
  totalMs?: number;
  captureMs?: number;
  stageMs?: number;
  analysisMs?: number;
  [key: string]: number | undefined;
};

export type AndroidBridgeTimestamps = {
  startedAt?: string;
  captureCompletedAt?: string;
  sourceImageModifiedAt?: string;
  stagedAt?: string;
  analysisStartedAt?: string;
  analysisCompletedAt?: string;
  availableAt?: string;
  [key: string]: string | undefined;
};

export class AndroidBridgeCaptureError extends Error {
  readonly cause?: unknown;

  constructor(message: string, options?: { cause?: unknown }) {
    super(message);
    this.name = 'AndroidBridgeCaptureError';
    this.cause = options?.cause;
  }
}

export type NormalizeAndroidBridgeResultOptions = {
  deviceId: string;
  producedBy?: string;
  fallbackCapturedAt?: string;
};

export function normalizeAndroidBridgeError(error: unknown): AndroidBridgeCaptureError {
  if (error instanceof AndroidBridgeCaptureError) return error;
  if (error instanceof Error) return new AndroidBridgeCaptureError(error.message, { cause: error });
  if (typeof error === 'string') return new AndroidBridgeCaptureError(error);
  return new AndroidBridgeCaptureError('Android bridge capture failed.', { cause: error });
}

export function normalizeAndroidBridgeCaptureResult(
  result: AndroidBridgeCaptureResult,
  options: NormalizeAndroidBridgeResultOptions
): Observation {
  if (typeof result !== 'object' || result === null) {
    throw new AndroidBridgeCaptureError('Android bridge returned an invalid capture result.');
  }

  const details = extractBridgeDetails(result);
  const mediaRef = firstString(
    details.mediaRef,
    details.workspaceImagePath,
    details.imagePath,
    details.mediaPath,
    details.path,
    details.file,
    details.filename,
    details.workspaceLatestPath
  );
  const timestamps = normalizeBridgeTimestamps(details.timestamps);
  const capturedAt = firstString(details.observedAt, details.capturedAt, timestamps?.sourceImageModifiedAt) ?? options.fallbackCapturedAt ?? new Date().toISOString();
  const availableAt = firstString(details.availableAt, timestamps?.availableAt, timestamps?.analysisCompletedAt) ?? capturedAt;
  const summary =
    firstString(details.summary, details.visionSummary, details.description, details.analysisText) ??
    extractAnalysisSummary(details.analysis) ??
    extractContentSummary(result.content);
  const confidence = extractAnalysisConfidence(details.analysis);
  const analysis = summary
    ? createBridgeAnalysis({
        observationIdSeed: mediaRef ?? `${options.deviceId}:${capturedAt}`,
        producedBy: options.producedBy ?? 'openclaw/android-camera-bridge',
        summary,
        confidence,
        data: details.analysis
      })
    : undefined;

  const observationId = stableObservationId(mediaRef, capturedAt);
  const timings = normalizeBridgeTimings(details.timings);

  return {
    id: observationId,
    type: 'image',
    capturedAt,
    deviceId: options.deviceId,
    mediaRef,
    telemetry: {
      observedAt: capturedAt,
      availableAt,
      latencyMs: timings
        ? {
            total: timings.totalMs,
            capture: timings.captureMs,
            stage: timings.stageMs,
            analysis: timings.analysisMs
          }
        : undefined,
      source: options.producedBy ?? 'openclaw/android-camera-bridge'
    },
    data: {
      bridge: {
        node: details.node,
        facing: details.facing,
        sourceTempPath: details.sourceTempPath,
        workspaceImagePath: details.workspaceImagePath,
        workspaceLatestPath: details.workspaceLatestPath,
        analysisMode: details.analysisMode,
        analysisState: details.analysisState,
        timings,
        timestamps
      }
    },
    quality: {
      confidence
    },
    summary,
    analyses: analysis ? [{ ...analysis, observationId }] : undefined
  };
}

function extractBridgeDetails(result: AndroidBridgeCaptureResult): AndroidBridgeCaptureResult {
  const nested = result.details;
  if (nested && typeof nested === 'object') {
    return { ...result, ...nested };
  }
  return result;
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

function extractContentSummary(content: unknown): string | undefined {
  if (!Array.isArray(content)) return undefined;
  const text = content
    .map((entry) => {
      if (typeof entry === 'string') return entry;
      if (typeof entry === 'object' && entry !== null && typeof (entry as Record<string, unknown>).text === 'string') {
        return (entry as Record<string, string>).text;
      }
      return undefined;
    })
    .filter((entry): entry is string => Boolean(entry))
    .join('\n');
  const match = text.match(/Vision summary:\s*(.+)$/im);
  return match?.[1]?.trim() || undefined;
}

function normalizeBridgeTimings(value: unknown): AndroidBridgeTimings | undefined {
  if (typeof value !== 'object' || value === null) return undefined;
  const timings: AndroidBridgeTimings = {};
  for (const [key, raw] of Object.entries(value)) {
    if (typeof raw === 'number' && Number.isFinite(raw)) timings[key] = raw;
  }
  return Object.keys(timings).length > 0 ? timings : undefined;
}

function normalizeBridgeTimestamps(value: unknown): AndroidBridgeTimestamps | undefined {
  if (typeof value !== 'object' || value === null) return undefined;
  const timestamps: AndroidBridgeTimestamps = {};
  for (const [key, raw] of Object.entries(value)) {
    if (typeof raw === 'string' && raw.length > 0) timestamps[key] = raw;
  }
  return Object.keys(timestamps).length > 0 ? timestamps : undefined;
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
