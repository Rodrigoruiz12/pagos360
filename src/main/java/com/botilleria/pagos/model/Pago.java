package com.botilleria.pagos.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "pagos")
public class Pago {

    @Id
    private String id;

    private String ordenId;
    private String usuarioId;
    private Integer monto;
    private String metodoPago;         // TARJETA | WEBPAY
    private String tarjetaEnmascarada; // Ej: ****-****-****-4242
    private String tarjetaHash;        // SHA-256 para antifraude (sin almacenar PAN)
    private String estado;             // APROBADO | BLOQUEADO_FRAUDE | PENDIENTE_WEBPAY | RECHAZADO
    private String webpayToken;
    private String webpayBuyOrder;
    private String codigoAutorizacion;
    private String motivoRechazo;
    private LocalDateTime fechaCreacion;

    public Pago() {
        this.fechaCreacion = LocalDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getOrdenId() { return ordenId; }
    public void setOrdenId(String ordenId) { this.ordenId = ordenId; }

    public String getUsuarioId() { return usuarioId; }
    public void setUsuarioId(String usuarioId) { this.usuarioId = usuarioId; }

    public Integer getMonto() { return monto; }
    public void setMonto(Integer monto) { this.monto = monto; }

    public String getMetodoPago() { return metodoPago; }
    public void setMetodoPago(String metodoPago) { this.metodoPago = metodoPago; }

    public String getTarjetaEnmascarada() { return tarjetaEnmascarada; }
    public void setTarjetaEnmascarada(String tarjetaEnmascarada) { this.tarjetaEnmascarada = tarjetaEnmascarada; }

    public String getTarjetaHash() { return tarjetaHash; }
    public void setTarjetaHash(String tarjetaHash) { this.tarjetaHash = tarjetaHash; }

    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }

    public String getWebpayToken() { return webpayToken; }
    public void setWebpayToken(String webpayToken) { this.webpayToken = webpayToken; }

    public String getWebpayBuyOrder() { return webpayBuyOrder; }
    public void setWebpayBuyOrder(String webpayBuyOrder) { this.webpayBuyOrder = webpayBuyOrder; }

    public String getCodigoAutorizacion() { return codigoAutorizacion; }
    public void setCodigoAutorizacion(String codigoAutorizacion) { this.codigoAutorizacion = codigoAutorizacion; }

    public String getMotivoRechazo() { return motivoRechazo; }
    public void setMotivoRechazo(String motivoRechazo) { this.motivoRechazo = motivoRechazo; }

    public LocalDateTime getFechaCreacion() { return fechaCreacion; }
    public void setFechaCreacion(LocalDateTime fechaCreacion) { this.fechaCreacion = fechaCreacion; }
}
