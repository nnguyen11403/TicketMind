package com.ticketmind.backend.user;

import com.ticketmind.backend.security.jwt.JwtPrincipal;
import com.ticketmind.backend.user.dto.ChangeRoleRequest;
import com.ticketmind.backend.user.dto.UserResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/users")
public class UserController {

	private final UserService userService;

	public UserController(UserService userService) {
		this.userService = userService;
	}

	/**
	 * Staff roster. Exists so an assignee picker has something to call — the
	 * ticket UI could previously only self-assign because no endpoint listed
	 * agents. Staff-only: a submitter has no business enumerating accounts.
	 */
	@GetMapping
	@PreAuthorize("hasAnyRole('AGENT','ADMIN')")
	public List<UserResponse> listStaff() {
		return userService.listStaff().stream().map(UserResponse::from).toList();
	}

	@GetMapping("/me")
	public UserResponse me(@AuthenticationPrincipal JwtPrincipal principal) {
		return UserResponse.from(userService.require(principal.id()));
	}

	@PatchMapping("/{id}/role")
	@PreAuthorize("hasRole('ADMIN')")
	public UserResponse changeRole(
			@AuthenticationPrincipal JwtPrincipal principal,
			@PathVariable UUID id,
			@Valid @RequestBody ChangeRoleRequest request) {
		return UserResponse.from(userService.changeRole(principal, id, request.role()));
	}
}
