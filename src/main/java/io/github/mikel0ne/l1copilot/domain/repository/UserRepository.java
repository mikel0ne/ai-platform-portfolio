package io.github.mikel0ne.l1copilot.domain.repository;

import io.github.mikel0ne.l1copilot.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, String> {
}
