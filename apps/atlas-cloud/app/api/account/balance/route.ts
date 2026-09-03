import { requireUser, errorResponse } from "@/src/auth";
import { balance } from "@/src/ledger";

export async function GET(request: Request) {
  try { return Response.json(await balance((await requireUser(request)).id), { headers: { "Cache-Control": "no-store" } }); }
  catch (error) { return errorResponse(error); }
}
