package com.steven.assets.repository;

import com.steven.assets.model.AppUser;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByEmail(String email);

    @Query(value = "SELECT * FROM app_user WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<AppUser> findByIdForUpdate(@Param("id") Long id);

    List<AppUser> findByStatus(String status);

    List<AppUser> findByRole(String role);

    List<AppUser> findAllByOrderByCreatedAtAsc();
}
