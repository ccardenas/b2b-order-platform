import { Controller, Get } from '@nestjs/common';

// Liveness simple: el repositorio es en memoria, no hay dependencias que
// chequear. Si se agrega una base de datos, este es el lugar para verificarla.
@Controller('health')
export class HealthController {
  @Get()
  check(): { status: 'UP' } {
    return { status: 'UP' };
  }
}
