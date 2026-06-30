package com.ticketmind.backend.user;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

public enum UserRole {
	USER, AGENT, ADMIN;

	public GrantedAuthority asAuthority() {
		return new SimpleGrantedAuthority("ROLE_" + name());
	}
}
