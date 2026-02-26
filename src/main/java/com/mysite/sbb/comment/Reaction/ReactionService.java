package com.mysite.sbb.comment.Reaction;

import com.mysite.sbb.comment.CommentEntity;
import com.mysite.sbb.comment.CommentRepository;
import com.mysite.sbb.user.SiteUser;
import com.mysite.sbb.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class ReactionService {

    private final ReactionRepository reactionRepository;
    private final CommentRepository commentRepository;
    private final UserRepository userRepository;
    private final StringRedisTemplate redis;

    /**
     * Redis에서 댓글별 좋아요/싫어요를 저장할 Key를 생성
     * 예) comment:reaction:15
     */
    private String reactionKey(Long commentId) {
        return "comment:reaction:" + commentId;
    }

    /**
     * 사용자가 댓글에 좋아요/싫어요를 눌렀을 때 실행되는 핵심 비즈니스 로직
     * <p>
     * 흐름:
     * 1. 사용자 및 댓글 검증
     * 2. DB에서 기존 반응 조회
     * 3. 없으면 생성, 있으면 토글(삭제 또는 변경)
     * 4. 변경된 값만 Redis에 반영
     * <p>
     * Redis는 조회 성능 최적화용 캐시이며, DB가 최종 정합성의 기준
     */
    @Transactional
    public void reactToComment(Long commentId, String username, ReactionType type) {
        SiteUser user = userRepository.findByUsername(username);
        if (user == null) throw new RuntimeException("사용자 없음");

        CommentEntity comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new RuntimeException("댓글 없음"));

        ReactionDelta delta;
        try {
            // 정상적인 경우: 기존 반응을 조회하여 생성/변경/삭제 처리
            delta = toggleInDbAndGetDelta(comment, user, type);
        } catch (DataIntegrityViolationException e) {
            // 동시에 두 요청이 들어온 경우(동시성 충돌) → 다시 조회해서 처리
            delta = toggleAfterConflictAndGetDelta(comment, user, type);
        }

        // 실제 변경된 수치만 Redis에 반영
        applyDeltaToRedis(commentId, delta);
    }

    /**
     * DB 기준으로 현재 댓글의 좋아요/싫어요 수를 조회
     * (관리자 페이지, 캐시 미스 복구 등에 사용 가능)
     */
    @Transactional(readOnly = true)
    public Map<String, Long> getReactionCount(Long commentId) {
        long likes = reactionRepository.countByComment_IdAndType(commentId, ReactionType.LIKE);
        long dislikes = reactionRepository.countByComment_IdAndType(commentId, ReactionType.DISLIKE);
        return Map.of("likes", likes, "dislikes", dislikes);
    }

    /**
     * DB에서 현재 사용자의 반응 상태를 조회한 뒤,
     * - 같은 타입이면 → 삭제 (취소)
     * - 다른 타입이면 → 변경
     * - 없으면 → 새로 생성
     * <p>
     * 그리고 Redis에 반영할 변화량(Delta)을 계산해서 반환
     */
    private ReactionDelta toggleInDbAndGetDelta(CommentEntity comment, SiteUser user, ReactionType targetType) {
        Long commentId = comment.getId();

        return reactionRepository.findByComment_IdAndUser_Id(commentId, user.getId())
                .map(existing -> {
                    ReactionType existingType = existing.getType();

                    // 이미 같은 반응이면 취소
                    if (existingType == targetType) {
                        reactionRepository.delete(existing);
                        return ReactionDelta.deleted(existingType);
                    }
                    // 다른 반응이면 변경
                    else {
                        existing.setType(targetType);
                        return ReactionDelta.changed(existingType, targetType);
                    }
                })
                // 기존 반응이 없으면 새로 생성
                .orElseGet(() -> {
                    ReactionEntity reaction = new ReactionEntity();
                    reaction.setComment(comment);
                    reaction.setUser(user);
                    reaction.setType(targetType);
                    reactionRepository.save(reaction);
                    return ReactionDelta.created(targetType);
                });
    }

    /**
     * 동시성 충돌(DataIntegrityViolationException) 발생 시
     * 다시 DB를 조회해서 동일한 로직을 재실행
     * <p>
     * → 중복 좋아요, 이중 반영 방지
     */
    private ReactionDelta toggleAfterConflictAndGetDelta(CommentEntity comment, SiteUser user, ReactionType targetType) {
        Long commentId = comment.getId();

        ReactionEntity existing = reactionRepository.findByComment_IdAndUser_Id(commentId, user.getId())
                .orElseThrow(() -> new RuntimeException("충돌 후 재조회 실패"));

        ReactionType existingType = existing.getType();

        if (existingType == targetType) {
            reactionRepository.delete(existing);
            return ReactionDelta.deleted(existingType);
        } else {
            existing.setType(targetType);
            return ReactionDelta.changed(existingType, targetType);
        }
    }

    /**
     * DB에서 계산된 변화량만 Redis 캐시에 반영
     * → 전체 재계산 없이 O(1)로 실시간 집계 가능
     */
    private void applyDeltaToRedis(Long commentId, ReactionDelta delta) {
        if (delta.likeDelta() == 0 && delta.dislikeDelta() == 0) return;

        String key = reactionKey(commentId);

        if (delta.likeDelta() != 0) {
            redis.opsForHash().increment(key, "likes", delta.likeDelta());
        }
        if (delta.dislikeDelta() != 0) {
            redis.opsForHash().increment(key, "dislikes", delta.dislikeDelta());
        }
    }
}
