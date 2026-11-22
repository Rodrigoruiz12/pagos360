package com.botilleria.pagos.repository;

import com.botilleria.pagos.model.AlertaFraude;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AlertaFraudeRepository extends JpaRepository<AlertaFraude, String> {
    List<AlertaFraude> findAllByOrderByFechaDeteccionDesc();
}
