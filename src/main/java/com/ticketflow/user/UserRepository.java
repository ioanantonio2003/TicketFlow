package com.ticketflow.user;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface UserRepository extends JpaRepository<AppUser, Long> {

    // lower(...) matches the functional unique index uk_users_email_lower; callers pass a normalized email
    @Query("select u from AppUser u where lower(u.email) = :email")
    Optional<AppUser> findByEmail(@Param("email") String email);

    @Query("select count(u) > 0 from AppUser u where lower(u.email) = :email")
    boolean existsByEmail(@Param("email") String email);

    boolean existsByRole(Role role);
}
