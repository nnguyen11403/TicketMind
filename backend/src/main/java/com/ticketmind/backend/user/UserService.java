package com.ticketmind.backend.user;

import com.ticketmind.backend.common.exception.LastAdminException;
import com.ticketmind.backend.common.exception.TicketNotFoundException;
import com.ticketmind.backend.security.jwt.JwtPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class UserService {

	private final UserRepository userRepository;

	public UserService(UserRepository userRepository) {
		this.userRepository = userRepository;
	}

	@Transactional(readOnly = true)
	public List<User> listStaff() {
		return userRepository.findByRoleInOrderByDisplayNameAsc(
				List.of(UserRole.AGENT, UserRole.ADMIN));
	}

	@Transactional(readOnly = true)
	public User require(UUID id) {
		// Reuses the ticket 404 rather than adding a user_not_found code: the
		// enumeration-resistance argument is identical, and the client should
		// not learn whether an id exists.
		return userRepository.findById(id).orElseThrow(TicketNotFoundException::new);
	}

	@Transactional
	public User changeRole(JwtPrincipal actor, UUID targetId, UserRole next) {
		User target = userRepository.findById(targetId).orElseThrow(TicketNotFoundException::new);
		if (target.getRole() == next) {
			return target;
		}
		// Losing the last ADMIN means nobody can ever grant the role again -
		// the bootstrap only fires when zero admins exist, so this would be
		// recoverable but only by a restart with config changed. Refuse.
		if (target.getRole() == UserRole.ADMIN && !userRepository.existsByRoleAndIdNot(UserRole.ADMIN, targetId)) {
			throw new LastAdminException();
		}
		target.changeRole(next);
		return userRepository.save(target);
	}
}
