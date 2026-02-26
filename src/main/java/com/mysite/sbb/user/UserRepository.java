package com.mysite.sbb.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.sql.Date;

@Repository
public interface UserRepository extends JpaRepository<SiteUser, Long> {

    SiteUser findByNameAndBirthDateAndEmail(String name, Date birthDate, String email);

    boolean existsByUsername(String id);

    boolean existsByEmail(String email);


    SiteUser findByUsername(String username);


    SiteUser findByNameAndBirthDate(String name, java.sql.Date birthDate);

    SiteUser findByEmail(String email);

    SiteUser findByNameAndBirthDateAndUsernameAndEmail(String name, Date birthDate, String username, String email);

}
