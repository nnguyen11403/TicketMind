package com.ticketmind.backend.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
		@NotBlank @Email @Size(max = 254) String email,
		@NotBlank @Size(max = 256) String password) {

	// A record's generated toString() prints every component, and Spring's
	// RequestResponseBodyMethodProcessor logs the deserialised body at DEBUG -
	// which the dev profile enables. Without this override every login writes
	// the user's plaintext password into the application log.
	@Override
	public String toString() {
		return "LoginRequest[email=" + email + ", password=***]";
	}
}
