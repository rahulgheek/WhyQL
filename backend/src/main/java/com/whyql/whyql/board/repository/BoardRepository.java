package com.whyql.whyql.board.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.whyql.whyql.board.domain.Board;

public interface BoardRepository extends JpaRepository<Board, UUID> {

  List<Board> findByOwnerUserIdOrderByCreatedAtDesc(String ownerUserId);

  Optional<Board> findByIdAndOwnerUserId(UUID id, String ownerUserId);
}
