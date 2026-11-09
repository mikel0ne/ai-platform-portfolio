package io.github.mikel0ne.l1copilot.domain.repository;

import io.github.mikel0ne.l1copilot.domain.Order;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    List<Order> findTop3ByUserIdOrderByOrderDateDesc(String userId);

    Optional<Order> findByIdAndUserId(Long id, String userId);
}
