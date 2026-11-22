package com.botilleria.pagos.service;

import com.botilleria.pagos.dto.PagoRequest;
import com.botilleria.pagos.dto.WebpayInitRequest;
import com.botilleria.pagos.model.AlertaFraude;
import com.botilleria.pagos.model.Pago;
import com.botilleria.pagos.repository.AlertaFraudeRepository;
import com.botilleria.pagos.repository.PagoRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PagoService {

    private final AntifraudService antifraudService;
    private final WebpayService webpayService;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private PagoRepository pagoRepository;

    @Autowired(required = false)
    private AlertaFraudeRepository alertaFraudeRepository;

    @Autowired(required = false)
    private KafkaTemplate<String, String> kafkaTemplate;

    @Value("${app.kafka.enabled:true}")
    private boolean kafkaEnabled;

    @Value("${app.kafka.topic-pagos-alertas:pagos.alertas}")
    private String topicPagosAlertas;

    public PagoService(AntifraudService antifraudService, WebpayService webpayService, ObjectMapper objectMapper) {
        this.antifraudService = antifraudService;
        this.webpayService = webpayService;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> procesarPagoConTarjeta(PagoRequest request) {
        if (request.getMonto() == null || request.getMonto() <= 0) {
            throw new IllegalArgumentException("El monto debe ser mayor a 0.");
        }

        String ordenId = (request.getOrdenId() != null && !request.getOrdenId().isBlank())
                ? request.getOrdenId()
                : "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String usuarioId = (request.getUsuarioId() != null && !request.getUsuarioId().isBlank())
                ? request.getUsuarioId()
                : "anonimo";

        AntifraudService.EvaluationResult eval = antifraudService.evaluarIntento(request.getNumeroTarjeta());

        Pago pago = new Pago();
        pago.setId(UUID.randomUUID().toString());
        pago.setOrdenId(ordenId);
        pago.setUsuarioId(usuarioId);
        pago.setMonto(request.getMonto());
        pago.setMetodoPago("TARJETA");
        pago.setTarjetaEnmascarada(eval.tarjetaEnmascarada());
        pago.setTarjetaHash(eval.tarjetaHash());
        pago.setFechaCreacion(LocalDateTime.now());

        Map<String, Object> respuesta = new LinkedHashMap<>();

        if (eval.fraudeDetectado()) {
            String motivo = String.format(
                    "BLOQUEO ANTIFRAUDE: La tarjeta %s registro %d intentos en menos de %d segundos (maximo permitido: %d).",
                    eval.tarjetaEnmascarada(), eval.intentosEnVentana(), eval.ventanaSegundos(), eval.maxPermitidos()
            );
            pago.setEstado("BLOQUEADO_FRAUDE");
            pago.setMotivoRechazo(motivo);
            guardarPagoSeguro(pago);

            AlertaFraude alerta = new AlertaFraude();
            alerta.setId(UUID.randomUUID().toString());
            alerta.setOrdenId(ordenId);
            alerta.setUsuarioId(usuarioId);
            alerta.setTarjetaEnmascarada(eval.tarjetaEnmascarada());
            alerta.setTarjetaHash(eval.tarjetaHash());
            alerta.setIntentosEnVentana(eval.intentosEnVentana());
            alerta.setVentanaSegundos(eval.ventanaSegundos());
            alerta.setSeveridad("CRITICA");
            alerta.setMensaje(motivo);
            alerta.setFechaDeteccion(LocalDateTime.now());
            guardarAlertaSegura(alerta);

            publicarEventoKafka("FRAUDE_BLOQUEADO", pago, alerta);

            respuesta.put("aprobado", false);
            respuesta.put("bloqueadoPorFraude", true);
            respuesta.put("estado", "BLOQUEADO_FRAUDE");
            respuesta.put("intentosEnVentana", eval.intentosEnVentana());
            respuesta.put("ventanaSegundos", eval.ventanaSegundos());
            respuesta.put("mensaje", motivo);
            respuesta.put("pago", pago);
            respuesta.put("alerta", alerta);
            return respuesta;
        }

        pago.setEstado("APROBADO");
        pago.setCodigoAutorizacion("AUTH-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        guardarPagoSeguro(pago);
        publicarEventoKafka("PAGO_APROBADO", pago, null);

        respuesta.put("aprobado", true);
        respuesta.put("bloqueadoPorFraude", false);
        respuesta.put("estado", "APROBADO");
        respuesta.put("intentosEnVentana", eval.intentosEnVentana());
        respuesta.put("mensaje", "Pago aprobado exitosamente.");
        respuesta.put("pago", pago);
        return respuesta;
    }

    public Map<String, Object> iniciarWebpay(WebpayInitRequest request) {
        if (request.getMonto() == null || request.getMonto() <= 0) {
            throw new IllegalArgumentException("El monto para Webpay debe ser mayor a 0.");
        }

        String ordenId = (request.getOrdenId() != null && !request.getOrdenId().isBlank())
                ? request.getOrdenId()
                : "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String usuarioId = (request.getUsuarioId() != null && !request.getUsuarioId().isBlank())
                ? request.getUsuarioId()
                : "anonimo";
        String buyOrder = "BTL-" + System.currentTimeMillis();
        String sessionId = "SES-" + usuarioId + "-" + UUID.randomUUID().toString().substring(0, 6);

        Map<String, Object> tbkResp = webpayService.crearTransaccion(buyOrder, sessionId, request.getMonto(), request.getReturnUrl());
        String token = String.valueOf(tbkResp.get("token"));
        String url = String.valueOf(tbkResp.get("url"));

        Pago pago = new Pago();
        pago.setId(UUID.randomUUID().toString());
        pago.setOrdenId(ordenId);
        pago.setUsuarioId(usuarioId);
        pago.setMonto(request.getMonto());
        pago.setMetodoPago("WEBPAY");
        pago.setEstado("PENDIENTE_WEBPAY");
        pago.setWebpayToken(token);
        pago.setWebpayBuyOrder(buyOrder);
        pago.setFechaCreacion(LocalDateTime.now());
        guardarPagoSeguro(pago);

        Map<String, Object> resultado = new LinkedHashMap<>();
        resultado.put("pagoId", pago.getId());
        resultado.put("ordenId", ordenId);
        resultado.put("buyOrder", buyOrder);
        resultado.put("token", token);
        resultado.put("url", url);
        resultado.put("estado", "PENDIENTE_WEBPAY");
        return resultado;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> confirmarWebpay(String tokenWs) {
        if (tokenWs == null || tokenWs.isBlank()) {
            throw new IllegalArgumentException("El parametro token_ws es obligatorio.");
        }

        Map<String, Object> tbkResp = webpayService.confirmarTransaccion(tokenWs);
        int responseCode = tbkResp.get("response_code") instanceof Number n ? n.intValue() : -1;
        String status = String.valueOf(tbkResp.getOrDefault("status", "FAILED"));
        String authCode = String.valueOf(tbkResp.getOrDefault("authorization_code", ""));

        String cardNumber = "0000";
        Object cardDetailObj = tbkResp.get("card_detail");
        if (cardDetailObj instanceof Map<?, ?> cardDetail && cardDetail.get("card_number") != null) {
            cardNumber = String.valueOf(cardDetail.get("card_number"));
        }

        Pago pago = (pagoRepository != null)
                ? pagoRepository.findByWebpayToken(tokenWs).orElseGet(Pago::new)
                : new Pago();

        if (pago.getId() == null) {
            pago.setId(UUID.randomUUID().toString());
            pago.setOrdenId(String.valueOf(tbkResp.getOrDefault("buy_order", "WEBPAY")));
            pago.setUsuarioId(String.valueOf(tbkResp.getOrDefault("session_id", "webpay")));
            pago.setMonto(tbkResp.get("amount") instanceof Number n ? n.intValue() : 0);
            pago.setMetodoPago("WEBPAY");
            pago.setWebpayToken(tokenWs);
            pago.setWebpayBuyOrder(String.valueOf(tbkResp.getOrDefault("buy_order", "")));
        }

        // Tambien pasamos los ultimos 4 digitos retornados por Webpay por el motor antifraude
        AntifraudService.EvaluationResult eval = antifraudService.evaluarIntento("WEBPAY" + cardNumber);
        pago.setTarjetaEnmascarada(eval.tarjetaEnmascarada());
        pago.setTarjetaHash(eval.tarjetaHash());
        pago.setCodigoAutorizacion(authCode);

        boolean aprobado = responseCode == 0 && "AUTHORIZED".equalsIgnoreCase(status);
        if (eval.fraudeDetectado()) {
            String motivo = "ALERTA ANTIFRAUDE WEBPAY: Tarjeta " + eval.tarjetaEnmascarada()
                    + " supero " + eval.maxPermitidos() + " transacciones en " + eval.ventanaSegundos() + "s.";
            pago.setEstado("BLOQUEADO_FRAUDE");
            pago.setMotivoRechazo(motivo);
            guardarPagoSeguro(pago);

            AlertaFraude alerta = new AlertaFraude();
            alerta.setId(UUID.randomUUID().toString());
            alerta.setOrdenId(pago.getOrdenId());
            alerta.setUsuarioId(pago.getUsuarioId());
            alerta.setTarjetaEnmascarada(eval.tarjetaEnmascarada());
            alerta.setTarjetaHash(eval.tarjetaHash());
            alerta.setIntentosEnVentana(eval.intentosEnVentana());
            alerta.setVentanaSegundos(eval.ventanaSegundos());
            alerta.setSeveridad("CRITICA");
            alerta.setMensaje(motivo);
            alerta.setFechaDeteccion(LocalDateTime.now());
            guardarAlertaSegura(alerta);
            publicarEventoKafka("FRAUDE_BLOQUEADO", pago, alerta);
            aprobado = false;
        } else if (aprobado) {
            pago.setEstado("APROBADO");
            guardarPagoSeguro(pago);
            publicarEventoKafka("WEBPAY_APROBADO", pago, null);
        } else {
            pago.setEstado("RECHAZADO");
            pago.setMotivoRechazo("Transbank rechazo la transaccion (response_code=" + responseCode + ", status=" + status + ")");
            guardarPagoSeguro(pago);
            publicarEventoKafka("WEBPAY_RECHAZADO", pago, null);
        }

        Map<String, Object> respuesta = new LinkedHashMap<>();
        respuesta.put("aprobado", aprobado);
        respuesta.put("bloqueadoPorFraude", eval.fraudeDetectado());
        respuesta.put("estado", pago.getEstado());
        respuesta.put("pago", pago);
        respuesta.put("transbank", tbkResp);
        return respuesta;
    }

    public List<Pago> listarPagos() {
        if (pagoRepository == null) return List.of();
        return pagoRepository.findAllByOrderByFechaCreacionDesc();
    }

    public List<Pago> listarPagosPorOrden(String ordenId) {
        if (pagoRepository == null) return List.of();
        return pagoRepository.findByOrdenIdOrderByFechaCreacionDesc(ordenId);
    }

    public List<AlertaFraude> listarAlertasFraude() {
        if (alertaFraudeRepository == null) return List.of();
        return alertaFraudeRepository.findAllByOrderByFechaDeteccionDesc();
    }

    private void guardarPagoSeguro(Pago pago) {
        if (pagoRepository != null) {
            try {
                pagoRepository.save(pago);
            } catch (Exception e) {
                System.err.println("[ms-pagos] Aviso al persistir pago en Supabase: " + e.getMessage());
            }
        }
    }

    private void guardarAlertaSegura(AlertaFraude alerta) {
        if (alertaFraudeRepository != null) {
            try {
                alertaFraudeRepository.save(alerta);
            } catch (Exception e) {
                System.err.println("[ms-pagos] Aviso al persistir alerta en Supabase: " + e.getMessage());
            }
        }
    }

    private void publicarEventoKafka(String tipoEvento, Pago pago, AlertaFraude alerta) {
        if (!kafkaEnabled || kafkaTemplate == null) {
            return;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("tipoEvento", tipoEvento);
            payload.put("origen", "ms-pagos");
            payload.put("usuarioId", pago.getUsuarioId());
            payload.put("ordenId", pago.getOrdenId());
            payload.put("monto", pago.getMonto());
            payload.put("metodoPago", pago.getMetodoPago());
            payload.put("tarjetaEnmascarada", pago.getTarjetaEnmascarada());
            payload.put("estado", pago.getEstado());
            payload.put("timestamp", Instant.now().toString());
            if (alerta != null) {
                payload.put("mensajeAlerta", alerta.getMensaje());
                payload.put("intentosEnVentana", alerta.getIntentosEnVentana());
                payload.put("severidad", alerta.getSeveridad());
            }
            String json = objectMapper.writeValueAsString(payload);
            kafkaTemplate.send(topicPagosAlertas, pago.getOrdenId(), json);
        } catch (Exception e) {
            System.err.println("[ms-pagos] Aviso Kafka (no bloqueante): " + e.getMessage());
        }
    }
}
