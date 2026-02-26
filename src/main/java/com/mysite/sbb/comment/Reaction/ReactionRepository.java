package com.mysite.sbb.comment.Reaction;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReactionRepository extends JpaRepository<ReactionEntity, Long> {

    /**
     * 특정 댓글에 대해 특정 사용자가 이미 반응했는지 조회
     * → 좋아요/싫어요 토글 로직의 핵심 쿼리
     */
    Optional<ReactionEntity> findByComment_IdAndUser_Id(Long commentId, Long userId);

    /**
     * 특정 댓글의 좋아요 또는 싫어요 개수를 DB 기준으로 조회
     * → Redis 캐시 복구, 관리자 페이지, 정합성 검증용
     */
    long countByComment_IdAndType(Long commentId, ReactionType type);

    /**
     * 여러 댓글의 좋아요/싫어요 개수를 한 번에 집계
     * <p>
     * 목적:
     * - 댓글 리스트 조회 시 N+1 문제 방지
     * - 댓글 100개면 100번 쿼리 날리는 대신, 단 1번의 GROUP BY로 처리
     * <p>
     * 반환 형식:
     * [commentId, reactionType, count]
     */
    @Query("""
                select r.comment.id, r.type, count(r)
                from ReactionEntity r
                where r.comment.id in :commentIds
                group by r.comment.id, r.type
            """)
    List<Object[]> countGroupedByCommentIds(@Param("commentIds") List<Long> commentIds);

    /**
     * 로그인한 사용자가 각 댓글에 어떤 반응을 눌렀는지 한 번에 조회
     * <p>
     * 목적:
     * - 댓글 리스트 화면에서
     * "이 사용자가 이미 좋아요 눌렀는지" 표시하기 위함
     * - 댓글 개수만큼 쿼리 날리는 N+1 문제 방지
     * <p>
     * 반환 형식:
     * [commentId, reactionType]
     */
    @Query("""
                select r.comment.id, r.type
                from ReactionEntity r
                where r.comment.id in :commentIds
                    and r.user.username = :username
            """)
    List<Object[]> findUserReactionsByCommentIds(
            @Param("commentIds") List<Long> commentIds,
            @Param("username") String username
    );
}
