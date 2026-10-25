// clients-api expone el maestro de clientes por mercado.
//
// Variables de entorno:
//   PORT                     puerto HTTP (default 3000)
//   FAULT_INJECTION_ENABLED  "true" registra la inyeccion de fallos (default desactivado)
import 'reflect-metadata';
import { ConsoleLogger, Logger } from '@nestjs/common';
import { NestFactory } from '@nestjs/core';
import { AppModule } from './app.module';
import { configureApp } from './app.setup';

async function bootstrap(): Promise<void> {
  // Una linea JSON por evento, como products-api, para poder agregar logs
  // de ambas APIs con la misma herramienta.
  const app = await NestFactory.create(AppModule, { logger: new ConsoleLogger({ json: true }) });

  const faultInjectionEnabled = process.env.FAULT_INJECTION_ENABLED === 'true';
  configureApp(app, { faultInjectionEnabled });
  if (faultInjectionEnabled) {
    Logger.warn('fault injection enabled', 'Bootstrap');
  }

  // SIGTERM/SIGINT cierran el servidor HTTP y esperan a los requests en curso.
  app.enableShutdownHooks();
  await app.listen(process.env.PORT ?? 3000);
}

void bootstrap();
