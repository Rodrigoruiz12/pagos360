package com.botilleria.pagos;

import com.botilleria.pagos.service.AntifraudService;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class AntifraudServiceTest {

    @Test
    void debeBloquearPorUsuarioEnCuartoIntentoYMantenerBloqueoPorCincoMinutos() {
        AntifraudService service = new AntifraudService(3, 10, 300);
        Instant t0 = Instant.parse("2026-11-26T12:00:00Z");

        // 3 intentos permitidos (mezclando tarjeta y Webpay del mismo usuarioId)
        assertFalse(service.evaluarIntentoPorUsuario("rod.ruiz@duocuc.cl", "4532-1111-2222-4242", t0).fraudeDetectado());
        assertFalse(service.evaluarIntentoPorUsuario("rod.ruiz@duocuc.cl", null, t0.plusSeconds(2)).fraudeDetectado());
        assertFalse(service.evaluarIntentoPorUsuario("rod.ruiz@duocuc.cl", "4111-2222-3333-4444", t0.plusSeconds(5)).fraudeDetectado());

        // 4to intento en menos de 10s -> BLOQUEADO por 5 minutos (300s)
        AntifraudService.EvaluationResult cuarto = service.evaluarIntentoPorUsuario("rod.ruiz@duocuc.cl", null, t0.plusSeconds(8));
        assertTrue(cuarto.fraudeDetectado());
        assertFalse(cuarto.yaEstabaBloqueado());
        assertEquals(4, cuarto.intentosEnVentana());
        assertEquals(300L, cuarto.segundosRestantesBloqueo());

        // 2 minutos despues (t0 + 128s): aunque pasaron >10s, debe SEGUIR bloqueado por la sancion de 5 min
        AntifraudService.EvaluationResult duranteBloqueo = service.evaluarIntentoPorUsuario("rod.ruiz@duocuc.cl", "4532-1111-2222-4242", t0.plusSeconds(128));
        assertTrue(duranteBloqueo.fraudeDetectado());
        assertTrue(duranteBloqueo.yaEstabaBloqueado());
        assertEquals(180L, duranteBloqueo.segundosRestantesBloqueo());

        // Otro usuario NO se ve afectado
        assertFalse(service.evaluarIntentoPorUsuario("otro@duocuc.cl", null, t0.plusSeconds(129)).fraudeDetectado());

        // Despues de los 5 minutos (t0 + 8s + 301s = t0 + 309s): se levanta el bloqueo
        AntifraudService.EvaluationResult trasCincoMinutos = service.evaluarIntentoPorUsuario("rod.ruiz@duocuc.cl", "4532-1111-2222-4242", t0.plusSeconds(309));
        assertFalse(trasCincoMinutos.fraudeDetectado());
        assertEquals(1, trasCincoMinutos.intentosEnVentana());
    }

    @Test
    void debePermitirNuevamenteSiNoSuperoElLimiteEnDiezSegundos() {
        AntifraudService service = new AntifraudService(3, 10, 300);
        Instant t0 = Instant.parse("2026-11-26T12:00:00Z");

        service.evaluarIntentoPorUsuario("cliente@test.cl", "4111-1111-1111-1111", t0);
        service.evaluarIntentoPorUsuario("cliente@test.cl", "4111-1111-1111-1111", t0.plusSeconds(1));
        service.evaluarIntentoPorUsuario("cliente@test.cl", "4111-1111-1111-1111", t0.plusSeconds(2));

        // Pasan 15 segundos sin haber disparado el 4to intento: la ventana se reinicia sin bloqueo
        AntifraudService.EvaluationResult despuesDeVentana = service.evaluarIntentoPorUsuario("cliente@test.cl", "4111-1111-1111-1111", t0.plusSeconds(15));
        assertFalse(despuesDeVentana.fraudeDetectado());
        assertEquals(1, despuesDeVentana.intentosEnVentana());
    }
}
