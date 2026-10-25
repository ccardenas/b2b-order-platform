import { Controller, Get, Param } from '@nestjs/common';
import { ClientsService } from './clients.service';
import { ClientIdParams } from './dto/client-id.params';
import { ClientResponse, toClientResponse } from './dto/client.response';

// Solo transporte: el ValidationPipe global valida el path param y el filtro
// global traduce los errores de dominio al contrato de error.
@Controller('clients')
export class ClientsController {
  constructor(private readonly clientsService: ClientsService) {}

  @Get(':clientId')
  async getClient(@Param() params: ClientIdParams): Promise<ClientResponse> {
    const client = await this.clientsService.getById(params.clientId);
    return toClientResponse(client);
  }
}
