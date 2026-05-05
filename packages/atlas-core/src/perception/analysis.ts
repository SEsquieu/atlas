import type { AnalyzeObservationOptions, Observation, ObservationAnalysis, PerceptionAnalyzerAdapter } from '../types.js';

export type AnalyzeWithPipelineOptions = AnalyzeObservationOptions & {
  analyzers: PerceptionAnalyzerAdapter[];
};

export async function analyzeObservationWithPipeline(
  observation: Observation,
  options: AnalyzeWithPipelineOptions
): Promise<Observation> {
  if (options.analyzers.length === 0) return observation;

  const producedAnalyses: ObservationAnalysis[] = [];
  for (const analyzer of options.analyzers) {
    const analyses = await analyzer.analyze(observation, {
      reason: options.reason,
      kinds: options.kinds
    });
    producedAnalyses.push(...analyses);
  }

  return attachAnalyses(observation, producedAnalyses);
}

export function attachAnalyses(observation: Observation, analyses: ObservationAnalysis[]): Observation {
  if (analyses.length === 0) return observation;

  const allAnalyses = [...(observation.analyses ?? []), ...analyses];
  const summary = observation.summary ?? firstUsefulSummary(allAnalyses);
  const confidence = observation.quality?.confidence ?? firstUsefulConfidence(allAnalyses);

  return {
    ...observation,
    summary,
    quality: {
      ...observation.quality,
      confidence
    },
    analyses: allAnalyses
  };
}

export function firstUsefulSummary(analyses: ObservationAnalysis[]): string | undefined {
  return analyses.find((analysis) => analysis.summary?.trim())?.summary;
}

export function firstUsefulConfidence(analyses: ObservationAnalysis[]): number | undefined {
  return analyses.find((analysis) => typeof analysis.confidence === 'number')?.confidence;
}
