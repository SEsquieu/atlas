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

export type AtlasRequestContext = {
  user: Awaited<ReturnType<typeof requireUser>>;
  organizationId: string;
  organizationRole: string;
};

export async function requireAtlasContext(request: Request): Promise<AtlasRequestContext> {
  const user = await requireUser(request);
  const requested = request.headers.get("x-atlas-organization-id");
  if (requested && !UUID_PATTERN.test(requested)) throw new HttpError(400, "Atlas organization id is invalid", "invalid_organization");
  const { data: organizationId, error } = await adminDb().rpc("atlas_resolve_organization", {
    p_user_id: user.id,
    p_requested_organization_id: requested || null,
  });
  if (error || !organizationId) {
    if (error?.code === "42501") throw new HttpError(403, "You do not have access to that Atlas organization", "organization_forbidden");
    throw new Error(`Could not resolve Atlas organization: ${error?.message ?? "missing organization"}`);
  }
  const { data: membership, error: membershipError } = await adminDb().from("atlas_organization_memberships")
    .select("role").eq("organization_id", organizationId).eq("user_id", user.id).eq("status", "active").single();
  if (membershipError || !membership) throw new HttpError(403, "Atlas organization membership is not active", "organization_forbidden");
  return { user, organizationId, organizationRole: membership.role as string };
}

export function requireOrganizationRole(context: AtlasRequestContext, allowed: string[]) {
  if (!allowed.includes(context.organizationRole)) throw new HttpError(403, "Your Atlas organization role cannot manage billing", "organization_role_forbidden");
}

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export class HttpError extends Error {
  constructor(public status: number, message: string, public code = "request_failed") { super(message); }
}

export function errorResponse(error: unknown) {
  if (error instanceof HttpError) return Response.json({ error: { code: error.code, message: error.message } }, { status: error.status });
  if (error instanceof ZodError) return Response.json({ error: { code: "invalid_request", message: "Atlas Cloud rejected the request shape" } }, { status: 400 });
  console.error(error);
  return Response.json({ error: { code: "internal_error", message: "Atlas Cloud could not complete the request" } }, { status: 500 });
}
