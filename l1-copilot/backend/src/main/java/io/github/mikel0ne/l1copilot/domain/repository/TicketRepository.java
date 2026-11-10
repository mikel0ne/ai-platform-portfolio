package io.github.mikel0ne.l1copilot.domain.repository;

import io.github.mikel0ne.l1copilot.domain.Ticket;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

    List<Ticket> findByUserIdOrderByCreatedAtDesc(String userId);

    Optional<Ticket> findByIdAndUserId(Long id, String userId);
}
