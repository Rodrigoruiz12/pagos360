package com.botilleria.pagos.repository;

import com.botilleria.pagos.model.Pago;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PagoRepository extends JpaRepository<Pago, String> {
    List<Pago> findByOrdenIdOrderByFechaCreacionDesc(String ordenId);
    List<Pago> findAllByOrderByFechaCreacionDesc();
    Optional<Pago> findByWebpayToken(String webpayToken);
}
