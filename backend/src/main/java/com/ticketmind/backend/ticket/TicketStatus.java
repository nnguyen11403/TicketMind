package com.ticketmind.backend.ticket;

import java.util.EnumSet;
import java.util.Set;

public enum TicketStatus {
	OPEN,
	IN_PROGRESS,
	WAITING,
	RESOLVED,
	CLOSED;

	private static final Set<TicketStatus> TERMINAL = EnumSet.of(RESOLVED, CLOSED);

	public boolean isTerminal() {
		return TERMINAL.contains(this);
	}

	public boolean canTransitionTo(TicketStatus next) {
		if (this == next) {
			return false;
		}
		// CLOSED is final; everything else can flow into any non-equal state.
		// Reopening (e.g. RESOLVED -> OPEN) is allowed; closing-then-reopening
		// is intentionally disallowed so audit history stays meaningful.
		return this != CLOSED;
	}
}
