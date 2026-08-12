package com.ticketmind.backend.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

	Optional<User> findByEmailIgnoreCase(String email);

	boolean existsByEmailIgnoreCase(String email);

	boolean existsByRole(UserRole role);

	boolean existsByRoleAndIdNot(UserRole role, UUID id);

	java.util.List<User> findByRoleInOrderByDisplayNameAsc(java.util.Collection<UserRole> roles);
}
