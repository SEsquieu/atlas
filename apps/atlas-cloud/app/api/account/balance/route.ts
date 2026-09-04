import { requireAtlasContext, errorResponse } from "@/src/auth";
import { balance } from "@/src/ledger";

export async function GET(request: Request) {
  try { return Response.json(await balance((await requireAtlasContext(request)).organizationId), { headers: { "Cache-Control": "no-store" } }); }
  catch (error) { return errorResponse(error); }
}
