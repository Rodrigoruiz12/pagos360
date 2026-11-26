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

    @Value("${app.kafka.topic-pagos-eventos:pagos.eventos}")
    private String topicPagosEventos;

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
        if (request.getNumeroTarjeta() == null || request.getNumeroTarjeta().isBlank()) {
            throw new IllegalArgumentException("El numero de tarjeta es obligatorio.");
        }

        String ordenId = (request.getOrdenId() != null && !request.getOrdenId().isBlank())
                ? request.getOrdenId()
                : "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String usuarioId = (request.getUsuarioId() != null && !request.getUsuarioId().isBlank())
                ? request.getUsuarioId()
                : "anonimo";

        // Revision antifraude por usuarioId ANTES de cobrar
        AntifraudService.EvaluationResult eval = antifraudService.evaluarIntentoPorUsuario(usuarioId, request.getNumeroTarjeta());

        Pago pago = new Pago();
        pago.setId(UUID.randomUUID().toString());
        pago.setOrdenId(ordenId);
        pago.setUsuarioId(usuarioId);
        pago.setMonto(request.getMonto());
        pago.setMetodoPago("TARJETA");
        pago.setTarjetaEnmascarada(eval.tarjetaEnmascarada());
        pago.setTarjetaHash(eval.tarjetaHash());
        pago.setFechaCreacion(LocalDateTime.now());

        if (eval.fraudeDetectado()) {
            return registrarBloqueoFraude(pago, eval);
        }

        pago.setEstado("APROBADO");
        pago.setCodigoAutorizacion("AUTH-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        guardarPagoSeguro(pago);

        // Resultado de pago publicado en pagos.eventos (pagos.alertas queda solo para fraude)
        publicarEnTopico(topicPagosEventos, "PAGO_APROBADO", pago, null, eval);

        Map<String, Object> respuesta = new LinkedHashMap<>();
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

        // Revision antifraude por usuarioId ANTES de iniciar cobro en Transbank
        AntifraudService.EvaluationResult eval = antifraudService.evaluarIntentoPorUsuario(usuarioId, null);

        Pago pago = new Pago();
        pago.setId(UUID.randomUUID().toString());
        pago.setOrdenId(ordenId);
        pago.setUsuarioId(usuarioId);
        pago.setMonto(request.getMonto());
        pago.setMetodoPago("WEBPAY");
        pago.setTarjetaEnmascarada("WEBPAY");
        pago.setTarjetaHash(eval.tarjetaHash());
        pago.setFechaCreacion(LocalDateTime.now());

        if (eval.fraudeDetectado()) {
            return registrarBloqueoFraude(pago, eval);
        }

        String buyOrder = "BTL-" + System.currentTimeMillis();
        String sessionId = "SES-" + usuarioId + "-" + UUID.randomUUID().toString().substring(0, 6);

        Map<String, Object> tbkResp = webpayService.crearTransaccion(buyOrder, sessionId, request.getMonto(), request.getReturnUrl());
        String token = String.valueOf(tbkResp.get("token"));
        String url = String.valueOf(tbkResp.get("url"));

        pago.setEstado("PENDIENTE_WEBPAY");
        pago.setWebpayToken(token);
        pago.setWebpayBuyOrder(buyOrder);
        guardarPagoSeguro(pago);

        publicarEnTopico(topicPagosEventos, "WEBPAY_INICIADO", pago, null, eval);

        Map<String, Object> resultado = new LinkedHashMap<>();
        resultado.put("aprobado", true);
        resultado.put("bloqueadoPorFraude", false);
        resultado.put("pagoId", pago.getId());
        resultado.put("ordenId", ordenId);
        resultado.put("buyOrder", buyOrder);
        resultado.put("token", token);
        resultado.put("url", url);
        resultado.put("estado", "PENDIENTE_WEBPAY");
        resultado.put("intentosEnVentana", eval.intentosEnVentana());
        return resultado;
    }

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

        String digitos = cardNumber.replaceAll("[^0-9]", "");
        if (digitos.length() >= 4) {
            pago.setTarjetaEnmascarada(antifraudService.enmascararTarjeta(digitos));
            pago.setTarjetaHash(antifraudService.calcularHash(digitos));
        }
        pago.setCodigoAutorizacion(authCode);

        // Sin revision antifraude en confirmar (el fraude se valida antes del cobro en /webpay/iniciar)
        boolean aprobado = responseCode == 0 && "AUTHORIZED".equalsIgnoreCase(status);
        if (aprobado) {
            pago.setEstado("APROBADO");
            guardarPagoSeguro(pago);
            publicarEnTopico(topicPagosEventos, "WEBPAY_APROBADO", pago, null, null);
        } else {
            pago.setEstado("RECHAZADO");
            pago.setMotivoRechazo("Transbank rechazo la transaccion (response_code=" + responseCode + ", status=" + status + ")");
            guardarPagoSeguro(pago);
            publicarEnTopico(topicPagosEventos, "WEBPAY_RECHAZADO", pago, null, null);
        }

        Map<String, Object> respuesta = new LinkedHashMap<>();
        respuesta.put("aprobado", aprobado);
        respuesta.put("bloqueadoPorFraude", false);
        respuesta.put("estado", pago.getEstado());
        respuesta.put("pago", pago);
        respuesta.put("transbank", tbkResp);
        return respuesta;
    }

    public boolean desbloquearUsuario(String usuarioId) {
        return antifraudService.desbloquearUsuario(usuarioId);
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

    private Map<String, Object> registrarBloqueoFraude(Pago pago, AntifraudService.EvaluationResult eval) {
        long minBloqueo = Math.max(1L, Math.round(eval.bloqueoDuracionSegundos() / 60.0));
        long minRest = eval.segundosRestantesBloqueo() / 60;
        long segRest = eval.segundosRestantesBloqueo() % 60;
        String tiempoRestanteTxt = String.format("%d min %02d s", minRest, segRest);

        String motivo = eval.yaEstabaBloqueado()
                ? String.format(
                        "BLOQUEO ANTIFRAUDE ACTIVO (%s): El usuario '%s' permanece bloqueado por seguridad. Podra reintentar en %s.",
                        pago.getMetodoPago(), pago.getUsuarioId(), tiempoRestanteTxt)
                : String.format(
                        "BLOQUEO ANTIFRAUDE (%s): El usuario '%s' registro %d intentos en menos de %d segundos (maximo permitido: %d). Cuenta bloqueada preventivamente por %d minutos.",
                        pago.getMetodoPago(), pago.getUsuarioId(), eval.intentosEnVentana(),
                        eval.ventanaSegundos(), eval.maxPermitidos(), minBloqueo);

        pago.setEstado("BLOQUEADO_FRAUDE");
        pago.setMotivoRechazo(motivo);
        guardarPagoSeguro(pago);

        AlertaFraude alerta = new AlertaFraude();
        alerta.setId(UUID.randomUUID().toString());
        alerta.setOrdenId(pago.getOrdenId());
        alerta.setUsuarioId(pago.getUsuarioId());
        alerta.setTarjetaEnmascarada(pago.getTarjetaEnmascarada());
        alerta.setTarjetaHash(pago.getTarjetaHash());
        alerta.setIntentosEnVentana(eval.intentosEnVentana());
        alerta.setVentanaSegundos(eval.ventanaSegundos());
        alerta.setSeveridad("CRITICA");
        alerta.setMensaje(motivo);
        alerta.setFechaDeteccion(LocalDateTime.now());
        guardarAlertaSegura(alerta);

        // Fraude se publica tanto en pagos.alertas (exclusivo alertas) como en pagos.eventos (todos los resultados)
        publicarEnTopico(topicPagosAlertas, "FRAUDE_BLOQUEADO", pago, alerta, eval);
        publicarEnTopico(topicPagosEventos, "FRAUDE_BLOQUEADO", pago, alerta, eval);

        Map<String, Object> respuesta = new LinkedHashMap<>();
        respuesta.put("aprobado", false);
        respuesta.put("bloqueadoPorFraude", true);
        respuesta.put("estado", "BLOQUEADO_FRAUDE");
        respuesta.put("metodoPago", pago.getMetodoPago());
        respuesta.put("usuarioId", pago.getUsuarioId());
        respuesta.put("intentosEnVentana", eval.intentosEnVentana());
        respuesta.put("ventanaSegundos", eval.ventanaSegundos());
        respuesta.put("bloqueoMinutos", minBloqueo);
        respuesta.put("segundosRestantesBloqueo", eval.segundosRestantesBloqueo());
        respuesta.put("bloqueadoHasta", eval.bloqueadoHasta() != null ? eval.bloqueadoHasta().toString() : null);
        respuesta.put("mensaje", motivo);
        respuesta.put("pago", pago);
        respuesta.put("alerta", alerta);
        return respuesta;
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

    private void publicarEnTopico(
            String topico,
            String tipoEvento,
            Pago pago,
            AlertaFraude alerta,
            AntifraudService.EvaluationResult eval) {
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
            if (eval != null && eval.bloqueadoHasta() != null) {
                payload.put("segundosRestantesBloqueo", eval.segundosRestantesBloqueo());
                payload.put("bloqueadoHasta", eval.bloqueadoHasta().toString());
            }
            String json = objectMapper.writeValueAsString(payload);
            kafkaTemplate.send(topico, pago.getUsuarioId(), json);
        } catch (Exception e) {
            System.err.println("[ms-pagos] Aviso Kafka en " + topico + " (no bloqueante): " + e.getMessage());
        }
    }
}
