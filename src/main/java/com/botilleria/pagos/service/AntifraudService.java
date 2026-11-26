package com.botilleria.pagos.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Regla Antifraude (por usuarioId, aplica antes de cobrar en Tarjeta y Webpay):
 * - Si un mismo usuarioId supera 3 intentos de pago en una ventana deslizante de 10 segundos,
 *   se bloquea preventivamente durante 5 minutos (300 segundos).
 * - Mientras dure el bloqueo de 5 minutos, cualquier nuevo intento es rechazado de inmediato (HTTP 429).
 */
@Service
public class AntifraudService {

    private final int maxAttempts;
    private final int windowSeconds;
    private final int blockDurationSeconds;

    private final ConcurrentHashMap<String, Deque<Instant>> intentosPorUsuario = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> bloqueosActivos = new ConcurrentHashMap<>();

    public AntifraudService(
            @Value("${app.antifraud.max-attempts:3}") int maxAttempts,
            @Value("${app.antifraud.window-seconds:10}") int windowSeconds,
            @Value("${app.antifraud.block-duration-seconds:300}") int blockDurationSeconds) {
        this.maxAttempts = maxAttempts;
        this.windowSeconds = windowSeconds;
        this.blockDurationSeconds = blockDurationSeconds;
    }

    public EvaluationResult evaluarIntentoPorUsuario(String usuarioId, String numeroTarjetaOpcional) {
        return evaluarIntentoPorUsuario(usuarioId, numeroTarjetaOpcional, Instant.now());
    }

    public EvaluationResult evaluarIntentoPorUsuario(String usuarioId, String numeroTarjetaOpcional, Instant ahora) {
        String usuarioNormalizado = normalizarUsuario(usuarioId);
        String tarjetaEnmascarada = "WEBPAY";
        String tarjetaHash = calcularHash("USER:" + usuarioNormalizado);

        if (numeroTarjetaOpcional != null && !numeroTarjetaOpcional.isBlank()) {
            String limpia = normalizarTarjeta(numeroTarjetaOpcional);
            tarjetaEnmascarada = enmascararTarjeta(limpia);
            tarjetaHash = calcularHash(limpia);
        }

        Deque<Instant> ventana = intentosPorUsuario.computeIfAbsent(usuarioNormalizado, k -> new ArrayDeque<>());
        synchronized (ventana) {
            // 1. Verificar si el usuario ya esta en bloqueo activo de 5 minutos
            Instant bloqueadoHasta = bloqueosActivos.get(usuarioNormalizado);
            if (bloqueadoHasta != null) {
                if (ahora.isBefore(bloqueadoHasta)) {
                    long restantes = Math.max(1L, Duration.between(ahora, bloqueadoHasta).getSeconds());
                    return new EvaluationResult(
                            true,
                            true,
                            Math.max(ventana.size(), maxAttempts + 1),
                            maxAttempts,
                            windowSeconds,
                            blockDurationSeconds,
                            restantes,
                            bloqueadoHasta,
                            usuarioNormalizado,
                            tarjetaHash,
                            tarjetaEnmascarada
                    );
                } else {
                    // El bloqueo de 5 minutos ya expiro: limpiar estado anterior
                    bloqueosActivos.remove(usuarioNormalizado);
                    ventana.clear();
                }
            }

            // 2. Evaluar ventana deslizante de 10 segundos
            Instant limite = ahora.minusSeconds(windowSeconds);
            while (!ventana.isEmpty() && ventana.peekFirst().isBefore(limite)) {
                ventana.pollFirst();
            }
            ventana.addLast(ahora);
            int conteoActual = ventana.size();

            // 3. Si supera el limite (>3 en 10s), activar bloqueo de 5 minutos
            if (conteoActual > maxAttempts) {
                Instant nuevoBloqueoHasta = ahora.plusSeconds(blockDurationSeconds);
                bloqueosActivos.put(usuarioNormalizado, nuevoBloqueoHasta);
                return new EvaluationResult(
                        true,
                        false,
                        conteoActual,
                        maxAttempts,
                        windowSeconds,
                        blockDurationSeconds,
                        blockDurationSeconds,
                        nuevoBloqueoHasta,
                        usuarioNormalizado,
                        tarjetaHash,
                        tarjetaEnmascarada
                );
            }

            return new EvaluationResult(
                    false,
                    false,
                    conteoActual,
                    maxAttempts,
                    windowSeconds,
                    blockDurationSeconds,
                    0L,
                    null,
                    usuarioNormalizado,
                    tarjetaHash,
                    tarjetaEnmascarada
            );
        }
    }

    public boolean desbloquearUsuario(String usuarioId) {
        String key = normalizarUsuario(usuarioId);
        Instant removed = bloqueosActivos.remove(key);
        Deque<Instant> ventana = intentosPorUsuario.get(key);
        if (ventana != null) {
            synchronized (ventana) {
                ventana.clear();
            }
        }
        return removed != null;
    }

    public String normalizarUsuario(String usuarioId) {
        if (usuarioId == null || usuarioId.isBlank()) {
            return "anonimo";
        }
        return usuarioId.trim().toLowerCase(Locale.ROOT);
    }

    public String normalizarTarjeta(String numeroTarjeta) {
        if (numeroTarjeta == null || numeroTarjeta.isBlank()) {
            throw new IllegalArgumentException("El numero de tarjeta es obligatorio.");
        }
        String soloDigitos = numeroTarjeta.replaceAll("[^0-9]", "");
        if (soloDigitos.length() < 4) {
            throw new IllegalArgumentException("El numero de tarjeta debe tener al menos 4 digitos.");
        }
        return soloDigitos;
    }

    public String enmascararTarjeta(String soloDigitos) {
        String ultimos4 = soloDigitos.substring(soloDigitos.length() - 4);
        return "****-****-****-" + ultimos4;
    }

    public String calcularHash(String valor) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encoded = digest.digest(valor.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(encoded);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }

    public record EvaluationResult(
            boolean fraudeDetectado,
            boolean yaEstabaBloqueado,
            int intentosEnVentana,
            int maxPermitidos,
            int ventanaSegundos,
            int bloqueoDuracionSegundos,
            long segundosRestantesBloqueo,
            Instant bloqueadoHasta,
            String usuarioNormalizado,
            String tarjetaHash,
            String tarjetaEnmascarada
    ) {}
}
