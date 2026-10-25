import { randomBytes } from 'node:crypto';
import { NextFunction, Request, Response } from 'express';

// Igual que products-api: el llamador puede propagar su traceId; si no viene,
// se genera uno por request y se devuelve en el mismo header.
export const TRACE_HEADER = 'X-Trace-Id';

export function traceMiddleware(req: Request, res: Response, next: NextFunction): void {
  const traceId = req.get(TRACE_HEADER) || randomBytes(16).toString('hex');
  res.locals.traceId = traceId;
  res.setHeader(TRACE_HEADER, traceId);
  next();
}

export function getTraceId(res: Response): string {
  const traceId: unknown = res.locals.traceId;
  return typeof traceId === 'string' ? traceId : 'unknown';
}
