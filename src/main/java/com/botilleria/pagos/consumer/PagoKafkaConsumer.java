package com.botilleria.pagos.consumer;

import com.botilleria.pagos.dto.OrdenEventoRecibidoDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumidor Kafka en ms-pagos: correlaciona ordenes confirmadas en ordenes.eventos con las transacciones de pago.
 */
@Component
public class PagoKafkaConsumer {

    private final ObjectMapper objectMapper;

    public PagoKafkaConsumer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${app.kafka.topic-ordenes:ordenes.eventos}",
            groupId = "${spring.kafka.consumer.group-id:ms-pagos-group}",
            autoStartup = "${app.kafka.enabled:true}"
    )
    public void consumirEventoOrden(String payload) {
        OrdenEventoRecibidoDto evento;
        try {
            evento = objectMapper.readValue(payload, OrdenEventoRecibidoDto.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Payload no es JSON valido en ordenes.eventos", e);
        }
        System.out.println("[MS-PAGOS][KAFKA] Orden correlacionada: " + evento.getTipoEvento()
                + " | ordenId=" + evento.getOrdenId()
                + " | total=$" + evento.getTotal());
    }
}
