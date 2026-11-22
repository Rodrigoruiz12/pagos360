package com.botilleria.pagos;

import com.botilleria.pagos.service.AntifraudService;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class AntifraudServiceTest {

    @Test
    void debeBloquearCuandoTarjetaSuperaTresIntentosEnDiezSegundos() {
        AntifraudService service = new AntifraudService(3, 10);
        Instant t0 = Instant.parse("2026-11-22T12:00:00Z");

        assertFalse(service.evaluarIntento("4532-1111-2222-4242", t0).fraudeDetectado());
        assertFalse(service.evaluarIntento("4532 1111 2222 4242", t0.plusSeconds(2)).fraudeDetectado());
        assertFalse(service.evaluarIntento("4532111122224242", t0.plusSeconds(5)).fraudeDetectado());

        AntifraudService.EvaluationResult cuartoIntento = service.evaluarIntento("4532111122224242", t0.plusSeconds(8));
        assertTrue(cuartoIntento.fraudeDetectado());
        assertEquals(4, cuartoIntento.intentosEnVentana());
        assertEquals("****-****-****-4242", cuartoIntento.tarjetaEnmascarada());
    }

    @Test
    void debePermitirNuevamenteDespuesDeExpirarLaVentanaDeDiezSegundos() {
        AntifraudService service = new AntifraudService(3, 10);
        Instant t0 = Instant.parse("2026-11-22T12:00:00Z");

        service.evaluarIntento("4111-1111-1111-1111", t0);
        service.evaluarIntento("4111-1111-1111-1111", t0.plusSeconds(1));
        service.evaluarIntento("4111-1111-1111-1111", t0.plusSeconds(2));

        AntifraudService.EvaluationResult despuesDeVentana = service.evaluarIntento("4111-1111-1111-1111", t0.plusSeconds(15));
        assertFalse(despuesDeVentana.fraudeDetectado());
        assertEquals(1, despuesDeVentana.intentosEnVentana());
    }
}
