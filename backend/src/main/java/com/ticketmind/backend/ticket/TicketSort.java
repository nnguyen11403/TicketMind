package com.ticketmind.backend.ticket;

import org.springframework.data.domain.Sort;

public enum TicketSort {

	NEWEST(Sort.by(Sort.Direction.DESC, "createdAt")),
	OLDEST(Sort.by(Sort.Direction.ASC, "createdAt")),
	RECENTLY_UPDATED(Sort.by(Sort.Direction.DESC, "updatedAt")),

	// priorityRank is a derived column, not a stored one: the priority column
	// holds enum names, so ordering by it alphabetically would put CRITICAL
	// before HIGH before LOW. Untriaged tickets rank 0 and therefore sort last
	// when the most urgent are first, which is where they belong.
	PRIORITY_HIGH_FIRST(Sort.by(Sort.Direction.DESC, "priorityRank")
			.and(Sort.by(Sort.Direction.DESC, "createdAt"))),
	PRIORITY_LOW_FIRST(Sort.by(Sort.Direction.ASC, "priorityRank")
			.and(Sort.by(Sort.Direction.DESC, "createdAt")));

	private final Sort sort;

	TicketSort(Sort sort) {
		this.sort = sort;
	}

	public Sort sort() {
		return sort;
	}
}
