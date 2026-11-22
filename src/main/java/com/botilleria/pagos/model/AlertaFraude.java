package com.botilleria.pagos.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "alertas_fraude")
public class AlertaFraude {

    @Id
    private String id;

    private String ordenId;
    private String usuarioId;
    private String tarjetaEnmascarada;
    private String tarjetaHash;
    private Integer intentosEnVentana;
    private Integer ventanaSegundos;
    private String severidad;
    private String mensaje;
    private LocalDateTime fechaDeteccion;

    public AlertaFraude() {
        this.fechaDeteccion = LocalDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getOrdenId() { return ordenId; }
    public void setOrdenId(String ordenId) { this.ordenId = ordenId; }

    public String getUsuarioId() { return usuarioId; }
    public void setUsuarioId(String usuarioId) { this.usuarioId = usuarioId; }

    public String getTarjetaEnmascarada() { return tarjetaEnmascarada; }
    public void setTarjetaEnmascarada(String tarjetaEnmascarada) { this.tarjetaEnmascarada = tarjetaEnmascarada; }

    public String getTarjetaHash() { return tarjetaHash; }
    public void setTarjetaHash(String tarjetaHash) { this.tarjetaHash = tarjetaHash; }

    public Integer getIntentosEnVentana() { return intentosEnVentana; }
    public void setIntentosEnVentana(Integer intentosEnVentana) { this.intentosEnVentana = intentosEnVentana; }

    public Integer getVentanaSegundos() { return ventanaSegundos; }
    public void setVentanaSegundos(Integer ventanaSegundos) { this.ventanaSegundos = ventanaSegundos; }

    public String getSeveridad() { return severidad; }
    public void setSeveridad(String severidad) { this.severidad = severidad; }

    public String getMensaje() { return mensaje; }
    public void setMensaje(String mensaje) { this.mensaje = mensaje; }

    public LocalDateTime getFechaDeteccion() { return fechaDeteccion; }
    public void setFechaDeteccion(LocalDateTime fechaDeteccion) { this.fechaDeteccion = fechaDeteccion; }
}
