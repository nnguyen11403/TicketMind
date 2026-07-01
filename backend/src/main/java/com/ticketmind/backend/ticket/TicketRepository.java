package com.ticketmind.backend.ticket;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface TicketRepository extends JpaRepository<Ticket, UUID> {

	@EntityGraph(attributePaths = {"submitter", "assignee"})
	Optional<Ticket> findWithUsersById(UUID id);

	@Query("""
			SELECT t FROM Ticket t
			LEFT JOIN FETCH t.submitter
			LEFT JOIN FETCH t.assignee
			WHERE (:status IS NULL OR t.status = :status)
			  AND (:submitterId IS NULL OR t.submitter.id = :submitterId)
			""")
	Page<Ticket> search(
			@Param("status") TicketStatus status,
			@Param("submitterId") UUID submitterId,
			Pageable pageable);
}
