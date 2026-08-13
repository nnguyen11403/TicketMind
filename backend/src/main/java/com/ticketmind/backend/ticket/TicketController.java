package com.ticketmind.backend.ticket;

import com.ticketmind.backend.rag.TicketTriageService;
import com.ticketmind.backend.security.jwt.JwtPrincipal;
import com.ticketmind.backend.ticket.dto.AddCommentRequest;
import com.ticketmind.backend.ticket.dto.AssignTicketRequest;
import com.ticketmind.backend.ticket.dto.ChangeStatusRequest;
import com.ticketmind.backend.ticket.dto.CreateTicketRequest;
import com.ticketmind.backend.ticket.dto.PagedResponse;
import com.ticketmind.backend.ticket.dto.TicketHistoryResponse;
import com.ticketmind.backend.ticket.dto.TicketResponse;
import com.ticketmind.backend.ticket.dto.TicketSummaryResponse;
import com.ticketmind.backend.ticket.dto.UpdateTicketRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/tickets")
public class TicketController {

	private final TicketService ticketService;
	private final TicketTriageService triageService;

	public TicketController(TicketService ticketService, TicketTriageService triageService) {
		this.ticketService = ticketService;
		this.triageService = triageService;
	}

	@PostMapping
	public ResponseEntity<TicketResponse> create(
			@AuthenticationPrincipal JwtPrincipal principal,
			@Valid @RequestBody CreateTicketRequest request) {
		Ticket created = ticketService.create(principal, request);
		return ResponseEntity.status(HttpStatus.CREATED).body(TicketResponse.from(created));
	}

	@GetMapping("/{id}")
	public TicketResponse get(
			@AuthenticationPrincipal JwtPrincipal principal,
			@PathVariable UUID id) {
		return TicketResponse.from(ticketService.get(principal, id));
	}

	@GetMapping
	public PagedResponse<TicketSummaryResponse> list(
			@AuthenticationPrincipal JwtPrincipal principal,
			@RequestParam(required = false) TicketStatus status,
			@RequestParam(defaultValue = "NEWEST") TicketSort sort,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return PagedResponse.of(
				ticketService.list(principal, status, sort, page, size),
				TicketSummaryResponse::from);
	}

	@PatchMapping("/{id}")
	public TicketResponse update(
			@AuthenticationPrincipal JwtPrincipal principal,
			@PathVariable UUID id,
			@Valid @RequestBody UpdateTicketRequest request) {
		return TicketResponse.from(ticketService.update(principal, id, request));
	}

	@PostMapping("/{id}/status")
	public TicketResponse changeStatus(
			@AuthenticationPrincipal JwtPrincipal principal,
			@PathVariable UUID id,
			@Valid @RequestBody ChangeStatusRequest request) {
		return TicketResponse.from(ticketService.changeStatus(principal, id, request));
	}

	@PostMapping("/{id}/assign")
	public TicketResponse assign(
			@AuthenticationPrincipal JwtPrincipal principal,
			@PathVariable UUID id,
			@RequestBody AssignTicketRequest request) {
		return TicketResponse.from(ticketService.assign(principal, id, request));
	}

	/**
	 * Re-run triage on demand. Staff only, and synchronous: the caller clicked
	 * a button and needs the answer, so this reports 502 triage_failed rather
	 * than degrading quietly the way the automatic post-commit path does.
	 */
	@PostMapping("/{id}/triage")
	public TicketResponse retriage(
			@AuthenticationPrincipal JwtPrincipal principal,
			@PathVariable UUID id) {
		triageService.retriage(principal, id);
		return TicketResponse.from(ticketService.get(principal, id));
	}

	@PostMapping("/{id}/comments")
	public ResponseEntity<TicketHistoryResponse> comment(
			@AuthenticationPrincipal JwtPrincipal principal,
			@PathVariable UUID id,
			@Valid @RequestBody AddCommentRequest request) {
		TicketHistory event = ticketService.comment(principal, id, request);
		return ResponseEntity.status(HttpStatus.CREATED).body(TicketHistoryResponse.from(event));
	}

	@GetMapping("/{id}/history")
	public List<TicketHistoryResponse> history(
			@AuthenticationPrincipal JwtPrincipal principal,
			@PathVariable UUID id) {
		return ticketService.history(principal, id).stream()
				.map(TicketHistoryResponse::from)
				.toList();
	}
}
