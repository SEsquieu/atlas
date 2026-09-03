import { adminDb } from "./clients";
import { ZodError } from "zod";

export async function requireUser(request: Request) {
  const authorization = request.headers.get("authorization");
  if (!authorization?.startsWith("Bearer ")) throw new HttpError(401, "Atlas account authentication required");
  const token = authorization.slice(7);
  const { data, error } = await adminDb().auth.getUser(token);
  if (error || !data.user) throw new HttpError(401, "Atlas account session is invalid or expired");
  return data.user;
}

export class HttpError extends Error {
  constructor(public status: number, message: string, public code = "request_failed") { super(message); }
}

export function errorResponse(error: unknown) {
  if (error instanceof HttpError) return Response.json({ error: { code: error.code, message: error.message } }, { status: error.status });
  if (error instanceof ZodError) return Response.json({ error: { code: "invalid_request", message: "Atlas Cloud rejected the request shape" } }, { status: 400 });
  console.error(error);
  return Response.json({ error: { code: "internal_error", message: "Atlas Cloud could not complete the request" } }, { status: 500 });
}
