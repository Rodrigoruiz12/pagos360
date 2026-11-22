package com.botilleria.pagos.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Regla Antifraude:
 * Detecta si una misma tarjeta se repite mas de 3 veces en una ventana deslizante de 10 segundos.
 */
@Service
public class AntifraudService {

    private final int maxAttempts;
    private final int windowSeconds;
    private final ConcurrentHashMap<String, Deque<Instant>> intentosPorTarjeta = new ConcurrentHashMap<>();

    public AntifraudService(
            @Value("${app.antifraud.max-attempts:3}") int maxAttempts,
            @Value("${app.antifraud.window-seconds:10}") int windowSeconds) {
        this.maxAttempts = maxAttempts;
        this.windowSeconds = windowSeconds;
    }

    public EvaluationResult evaluarIntento(String numeroTarjeta) {
        return evaluarIntento(numeroTarjeta, Instant.now());
    }

    public EvaluationResult evaluarIntento(String numeroTarjeta, Instant ahora) {
        String limpia = normalizarTarjeta(numeroTarjeta);
        String hash = calcularHash(limpia);
        String enmascarada = enmascararTarjeta(limpia);

        Deque<Instant> ventana = intentosPorTarjeta.computeIfAbsent(hash, k -> new ArrayDeque<>());
        int conteoActual;
        synchronized (ventana) {
            Instant limite = ahora.minusSeconds(windowSeconds);
            while (!ventana.isEmpty() && ventana.peekFirst().isBefore(limite)) {
                ventana.pollFirst();
            }
            ventana.addLast(ahora);
            conteoActual = ventana.size();
        }

        boolean esFraude = conteoActual > maxAttempts;
        return new EvaluationResult(esFraude, conteoActual, maxAttempts, windowSeconds, hash, enmascarada);
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

    public String calcularHash(String soloDigitos) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encoded = digest.digest(soloDigitos.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(encoded);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }

    public record EvaluationResult(
            boolean fraudeDetectado,
            int intentosEnVentana,
            int maxPermitidos,
            int ventanaSegundos,
            String tarjetaHash,
            String tarjetaEnmascarada
    ) {}
}
