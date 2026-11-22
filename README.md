# pagos360 (`ms-pagos`) — Pedidos360 EV3

Microservicio de Pagos, Integración **Webpay Plus (Transbank)** y **Motor Antifraude** para Pedidos360.

## Funcionalidades
- **Regla Antifraude:** Detecta si una misma tarjeta se utiliza **más de 3 veces en una ventana deslizante de 10 segundos**, bloquea la transacción preventivamente (`HTTP 429`, estado `BLOQUEADO_FRAUDE`), persiste la alerta en Supabase PostgreSQL (`alertas_fraude`) y emite el evento al tópico Kafka `pagos.alertas`.
- **Integración Webpay Plus (+5 pts):** Conecta con el ambiente de integración oficial de Transbank REST API v1.2 (`/webpay/iniciar` y `/webpay/confirmar`).
- **Seguridad JWT Multi-Proveedor:** Valida tokens de **Azure Entra ID** y **AWS Cognito**.

## Endpoints Principales (Puerto `8088`)
- `GET  /api/pagos/health` — Estado del servicio (público).
- `POST /api/pagos/procesar` — Pago directo con tarjeta + evaluación antifraude.
- `POST /api/pagos/webpay/iniciar` — Inicia transacción en Webpay Plus (retorna `url` y `token`).
- `POST /api/pagos/webpay/confirmar` — Confirma transacción Webpay con `token_ws`.
- `GET  /api/pagos` — Historial de pagos.
- `GET  /api/pagos/orden/{ordenId}` — Pagos asociados a una orden.
- `GET  /api/pagos/alertas` — Listado de alertas de fraude detectadas.

## Docker en EC2
```bash
docker build -t pagos360 .
docker run -d --name ms-pagos -p 8088:8088 \
  -e KAFKA_BOOTSTRAP_SERVERS=<IP_PRIVADA_KAFKA>:9092 \
  pagos360
```
