import { requireUser, errorResponse } from "@/src/auth";
import { runInference } from "@/src/inference";

export const runtime = "nodejs";
export const maxDuration = 300;

export async function POST(request: Request) {
  try { return await runInference(request, (await requireUser(request)).id); }
  catch (error) { return errorResponse(error); }
}
