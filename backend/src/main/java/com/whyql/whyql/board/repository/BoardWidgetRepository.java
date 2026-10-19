package com.whyql.whyql.board.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.whyql.whyql.board.domain.BoardWidget;

public interface BoardWidgetRepository extends JpaRepository<BoardWidget, UUID> {

  List<BoardWidget> findByBoardIdAndOwnerUserIdOrderByOrdinalAsc(UUID boardId, String ownerUserId);

  long countByBoardIdAndOwnerUserId(UUID boardId, String ownerUserId);

  Optional<BoardWidget> findByIdAndBoardIdAndOwnerUserId(UUID id, UUID boardId, String ownerUserId);

  void deleteByBoardIdAndOwnerUserId(UUID boardId, String ownerUserId);
}
