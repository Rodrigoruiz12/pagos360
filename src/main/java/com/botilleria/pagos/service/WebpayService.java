package com.botilleria.pagos.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Integracion real con Transbank Webpay Plus REST API v1.2 (Ambiente de Integracion).
 */
@Service
public class WebpayService {

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${app.webpay.api-url}")
    private String apiUrl;

    @Value("${app.webpay.commerce-code}")
    private String commerceCode;

    @Value("${app.webpay.api-key}")
    private String apiKey;

    @Value("${app.webpay.default-return-url}")
    private String defaultReturnUrl;

    public Map<String, Object> crearTransaccion(String buyOrder, String sessionId, Integer amount, String returnUrl) {
        HttpHeaders headers = crearHeaders();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("buy_order", truncar(buyOrder, 26));
        body.put("session_id", truncar(sessionId, 61));
        body.put("amount", amount);
        body.put("return_url", (returnUrl != null && !returnUrl.isBlank()) ? returnUrl : defaultReturnUrl);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                apiUrl,
                HttpMethod.POST,
                entity,
                new ParameterizedTypeReference<>() {}
        );
        return response.getBody();
    }

    public Map<String, Object> confirmarTransaccion(String tokenWs) {
        HttpHeaders headers = crearHeaders();
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        String url = apiUrl + "/" + tokenWs;

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                url,
                HttpMethod.PUT,
                entity,
                new ParameterizedTypeReference<>() {}
        );
        return response.getBody();
    }

    public Map<String, Object> consultarEstado(String tokenWs) {
        HttpHeaders headers = crearHeaders();
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        String url = apiUrl + "/" + tokenWs;

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                entity,
                new ParameterizedTypeReference<>() {}
        );
        return response.getBody();
    }

    private HttpHeaders crearHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Tbk-Api-Key-Id", commerceCode);
        headers.set("Tbk-Api-Key-Secret", apiKey);
        return headers;
    }

    private String truncar(String valor, int max) {
        if (valor == null) return "BTL-000";
        return valor.length() <= max ? valor : valor.substring(0, max);
    }
}
