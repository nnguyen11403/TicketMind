package com.ticketmind.backend.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
		@NotBlank @Email @Size(max = 254) String email,
		@NotBlank @Size(min = 12, max = 256) String password,
		@NotBlank @Size(max = 120) String displayName) {

	// See LoginRequest: the generated toString() would leak the plaintext
	// password into the DEBUG log that the dev profile turns on.
	@Override
	public String toString() {
		return "RegisterRequest[email=" + email + ", password=***, displayName=" + displayName + "]";
	}
}
