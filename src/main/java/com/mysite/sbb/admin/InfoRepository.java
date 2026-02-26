package com.mysite.sbb.admin;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface InfoRepository extends JpaRepository<InfoEntity, Long> {
    Optional<InfoEntity> findTopByOrderByIdAsc();
}
