import { LoggerService } from '@nestjs/common';
import { NextFunction, Request, Response } from 'express';
import { getTraceId } from './trace';

// Convencion de nginx para "el cliente cerro la conexion antes de recibir
// respuesta". Solo se usa en el log.
export const STATUS_CLIENT_CLOSED = 499;

// Una linea por request, con los mismos campos que el access log de
// products-api. 'close' se emite siempre, tambien despues de 'finish': si la
// respuesta no termino de escribirse, el cliente se fue y no vale statusCode
// (seguiria en el 200 por defecto).
export function accessLog(logger: LoggerService) {
  return (req: Request, res: Response, next: NextFunction): void => {
    const start = performance.now();
    res.once('close', () => {
      const queryStart = req.originalUrl.indexOf('?');
      logger.log({
        method: req.method,
        path: req.path,
        query: queryStart === -1 ? '' : req.originalUrl.slice(queryStart + 1),
        status: res.writableFinished ? res.statusCode : STATUS_CLIENT_CLOSED,
        durationMs: Math.round(performance.now() - start),
        traceId: getTraceId(res),
      });
    });
    next();
  };
}
