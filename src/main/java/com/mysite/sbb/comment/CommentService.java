package com.mysite.sbb.comment;

import com.mysite.sbb.comment.Reaction.ReactionRepository;
import com.mysite.sbb.comment.Reaction.ReactionType;
import com.mysite.sbb.fastapi.FastApiEntity;
import com.mysite.sbb.fastapi.FastApiRepository;
import com.mysite.sbb.user.SiteUser;
import com.mysite.sbb.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * 댓글 + 좋아요/싫어요 + 사용자 반응을
 * 페이지 단위로 집계 조회하기 위한 서비스.
 * <p>
 * N+1 쿼리 문제를 방지하기 위해
 * 댓글 목록, 반응 집계, 사용자 반응을
 * 각각 bulk 쿼리로 분리하여 조회한다.
 */
@Service
@RequiredArgsConstructor
public class CommentService {
    private final CommentRepository commentRepository;
    private final ReactionRepository reactionRepository;
    private final FastApiRepository fastApiRepository; // 뉴스 엔티티용 Repo
    private final UserRepository userRepository; // 유저 엔티티용 Repo

    /**
     * 회사별 댓글을 최신순으로 조회한다.
     * (관리자 화면, 전체 로드 등 페이지네이션이 필요 없는 경우 사용)
     */
    public List<CommentEntity> getCommentByCompany(String company) {
        return commentRepository.findByNews_CompanyOrderByCreatedAtDesc(company);
    }

    /**
     * 회사별 댓글 페이지 조회.
     * <p>
     * 댓글 Entity를 그대로 반환하지 않고,
     * 좋아요/싫어요 개수와
     * 현재 로그인 사용자의 반응(LIKE/DISLIKE)을
     * 함께 포함한 DTO로 변환해 반환한다.
     * <p>
     * 성능 최적화를 위해
     * 1) 댓글 목록
     * 2) 댓글별 좋아요/싫어요 집계
     * 3) 사용자 반응
     * 을 각각 한 번의 쿼리로 조회한다.
     */
    public Page<CommentResponse> getCommentPageByCompany(
            String company,
            Pageable pageable,
            String username
    ) {
        Page<CommentEntity> page = commentRepository.findCommentPageByCompany(company, pageable);

        List<Long> commentIds = page.getContent().stream()
                .map(CommentEntity::getId)
                .toList();

        if (commentIds.isEmpty()) {
            return Page.empty(pageable);
        }

        // 댓글 ID 목록을 기준으로
        // 좋아요/싫어요를 GROUP BY 하여 한 번에 집계
        List<Object[]> rows = reactionRepository.countGroupedByCommentIds(commentIds);
        Map<Long, long[]> countMap = new HashMap<>();
        for (Object[] row : rows) {
            Long commentId = (Long) row[0];
            ReactionType type = (ReactionType) row[1];
            Long cnt = (Long) row[2];

            long[] arr = countMap.computeIfAbsent(commentId, k -> new long[]{0L, 0L});
            if (type == ReactionType.LIKE) arr[0] = cnt;
            else if (type == ReactionType.DISLIKE) arr[1] = cnt;
        }

        // 유저 리액션
        Map<Long, ReactionType> myMap = new HashMap<>();
        if (username != null && !username.isBlank()) {
            // 현재 로그인한 사용자가
            // 각 댓글에 어떤 반응(LIKE/DISLIKE)을 했는지
            // IN 쿼리로 한 번에 조회
            List<Object[]> myRows = reactionRepository.findUserReactionsByCommentIds(commentIds, username);
            for (Object[] row : myRows) {
                Long commentId = (Long) row[0];
                ReactionType type = (ReactionType) row[1];
                myMap.put(commentId, type);
            }
        }

        // Entity + 집계 결과 + 사용자 반응을 합쳐
        // API 전용 응답 DTO로 변환
        List<CommentResponse> dtoList = page.getContent().stream()
                .map(comment -> {
                    long[] arr = countMap.getOrDefault(comment.getId(), new long[]{0L, 0L});
                    ReactionType userReaction = myMap.get(comment.getId());
                    return CommentResponse.fromEntity(
                            comment,
                            arr[0],
                            arr[1],
                            userReaction
                    );
                })
                .toList();

        return new PageImpl<>(dtoList, pageable, page.getTotalElements());
    }


    /**
     * 특정 사용자가 작성한 댓글 목록을 최신순으로 조회한다.
     * 마이페이지, 활동 내역 화면 등에 사용된다.
     */
    public List<CommentEntity> getCommentsByUsername(String username) {
        return commentRepository.findByUser_UsernameOrderByCreatedAtDesc(username);
    }

    /**
     * 특정 회사(뉴스)에 댓글을 등록한다.
     * <p>
     * FastAPI 서버에서 수집·분석한 뉴스 엔티티와
     * 로그인 사용자를 연결하여
     * 댓글이 어느 뉴스·어느 사용자에 속하는지
     * 명확하게 관계를 유지하도록 설계했다.
     */
    public CommentEntity addComment(String company, String username, String content) {
        FastApiEntity news = fastApiRepository.findTopByCompanyOrderByCreatedAt(company)
                .orElseThrow(() -> new IllegalArgumentException("관련 회사가 존재하지 않습니다."));

        SiteUser user = userRepository.findByUsername(username);
        if (user == null) {
            throw new IllegalArgumentException("사용자가 존재하지 않습니다.");
        }

        CommentEntity comment = new CommentEntity();
        comment.setNews(news);
        comment.setUser(user);
        comment.setContent(content);

        return commentRepository.save(comment);
    }

    /**
     * 댓글 삭제.
     * <p>
     * 일반 사용자는 본인 댓글만 삭제 가능하고,
     * 관리자는 모든 댓글을 삭제할 수 있도록
     * 권한 로직을 서비스 레벨에서 한 번 더 검증한다.
     */
    public void deleteComment(Long commentId, String username, boolean isAdmin) { // isAdmin 파라미터 추가
        CommentEntity comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new IllegalArgumentException("댓글이 존재하지 않습니다."));

        // (작성자가 아니면서) 동시에 (관리자도 아니면) 삭제 불가
        if (!comment.getUser().getUsername().equals(username) && !isAdmin) {
            throw new RuntimeException("삭제 권한이 없습니다.");
        }

        commentRepository.delete(comment);
    }


}
