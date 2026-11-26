package com.botilleria.pagos.controller;

import com.botilleria.pagos.dto.PagoRequest;
import com.botilleria.pagos.dto.WebpayInitRequest;
import com.botilleria.pagos.model.AlertaFraude;
import com.botilleria.pagos.model.Pago;
import com.botilleria.pagos.service.PagoService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/pagos")
public class PagoController {

    private final PagoService pagoService;

    public PagoController(PagoService pagoService) {
        this.pagoService = pagoService;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "servicio", "ms-pagos",
                "estado", "UP",
                "webpay", "Integracion Transbank v1.2",
                "antifraude", "Max 3 intentos cada 10s por usuarioId (bloqueo preventivo 5 minutos)",
                "topicosKafka", List.of("pagos.eventos", "pagos.alertas"),
                "timestamp", Instant.now().toString()
        );
    }

    @PostMapping("/procesar")
    public ResponseEntity<Map<String, Object>> procesarPago(@RequestBody PagoRequest request) {
        Map<String, Object> resultado = pagoService.procesarPagoConTarjeta(request);
        boolean bloqueado = Boolean.TRUE.equals(resultado.get("bloqueadoPorFraude"));
        if (bloqueado) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(resultado);
        }
        return ResponseEntity.ok(resultado);
    }

    @PostMapping("/webpay/iniciar")
    public ResponseEntity<Map<String, Object>> iniciarWebpay(@RequestBody WebpayInitRequest request) {
        Map<String, Object> resultado = pagoService.iniciarWebpay(request);
        boolean bloqueado = Boolean.TRUE.equals(resultado.get("bloqueadoPorFraude"));
        if (bloqueado) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(resultado);
        }
        return ResponseEntity.ok(resultado);
    }

    @PostMapping("/webpay/confirmar")
    public ResponseEntity<Map<String, Object>> confirmarWebpay(@RequestBody Map<String, String> body) {
        String tokenWs = body.getOrDefault("token_ws", body.get("token"));
        return ResponseEntity.ok(pagoService.confirmarWebpay(tokenWs));
    }

    @GetMapping("/webpay/retorno")
    public ResponseEntity<Map<String, Object>> retornoWebpay(@RequestParam(name = "token_ws", required = false) String tokenWs) {
        if (tokenWs == null || tokenWs.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "aprobado", false,
                    "mensaje", "Pago anulado por el usuario en Webpay."
            ));
        }
        return ResponseEntity.ok(pagoService.confirmarWebpay(tokenWs));
    }

    @DeleteMapping("/antifraude/{usuarioId}")
    public ResponseEntity<Map<String, Object>> desbloquearUsuario(@PathVariable String usuarioId) {
        boolean estabaBloqueado = pagoService.desbloquearUsuario(usuarioId);
        return ResponseEntity.ok(Map.of(
                "usuarioId", usuarioId,
                "desbloqueado", true,
                "teniaBloqueoActivo", estabaBloqueado
        ));
    }

    @GetMapping
    public List<Pago> listarPagos() {
        return pagoService.listarPagos();
    }

    @GetMapping("/orden/{ordenId}")
    public List<Pago> listarPorOrden(@PathVariable String ordenId) {
        return pagoService.listarPagosPorOrden(ordenId);
    }

    @GetMapping("/alertas")
    public List<AlertaFraude> listarAlertasFraude() {
        return pagoService.listarAlertasFraude();
    }
}
