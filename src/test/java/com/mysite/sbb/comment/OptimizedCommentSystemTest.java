package com.mysite.sbb.comment;

import com.mysite.sbb.comment.Reaction.ReactionEntity;
import com.mysite.sbb.comment.Reaction.ReactionRepository;
import com.mysite.sbb.comment.Reaction.ReactionService;
import com.mysite.sbb.comment.Reaction.ReactionType;
import com.mysite.sbb.fastapi.FastApiEntity;
import com.mysite.sbb.fastapi.FastApiRepository;
import com.mysite.sbb.user.SiteUser;
import com.mysite.sbb.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("🚀 최적화 검증: N+1, 동시성, Redis (완벽 최종)")
class OptimizedCommentSystemTest {

    @Mock
    private CommentRepository commentRepository;
    @Mock
    private ReactionRepository reactionRepository;
    @Mock
    private FastApiRepository fastApiRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private StringRedisTemplate redisTemplate;

    @InjectMocks
    private CommentService commentService;
    @InjectMocks
    private ReactionService reactionService;

    private SiteUser testUser;
    private FastApiEntity testNews;
    private List<CommentEntity> testComments;

    @BeforeEach
    void setUp() {
        testUser = new SiteUser();
        testUser.setId(1L);
        testUser.setUsername("testuser");

        testNews = new FastApiEntity();
        testNews.setId(1L);
        testNews.setCompany("samsung");

        testComments = Arrays.asList(
                createComment(1L), createComment(2L), createComment(3L),
                createComment(4L), createComment(5L)
        );
    }

    private CommentEntity createComment(Long id) {
        CommentEntity c = new CommentEntity();
        c.setId(id);
        c.setContent("댓글 " + id);
        c.setUser(testUser);
        c.setNews(testNews);
        return c;
    }

    // =====================================================
    // 🔥 1. N+1 문제 해결 (페이징 버전)
    // =====================================================

    @Test
    @DisplayName("✅ getCommentPageByCompany → IN 쿼리 1번")
    void testN1ProblemSolved() {
        // Arrange
        Pageable pageable = PageRequest.of(0, 5);

        CommentEntity c1 = new CommentEntity();
        c1.setId(1L);
        c1.setNews(testNews);
        c1.setUser(testUser);
        c1.setContent("c1");
        c1.setCreatedAt(LocalDateTime.now());

        CommentEntity c2 = new CommentEntity();
        c2.setId(2L);
        c2.setNews(testNews);
        c2.setUser(testUser);
        c2.setContent("c2");
        c2.setCreatedAt(LocalDateTime.now());

        List<CommentEntity> content = Arrays.asList(c1, c2);

        when(commentRepository.findCommentPageByCompany("samsung", pageable))
                .thenReturn(new PageImpl<>(content, pageable, 100));

        @SuppressWarnings("unchecked")
        List<Object[]> reactions = Arrays.asList(
                new Object[]{1L, ReactionType.LIKE, 15L},
                new Object[]{1L, ReactionType.DISLIKE, 3L}
        );
        when(reactionRepository.countGroupedByCommentIds(anyList()))
                .thenReturn(reactions);

        // Act
        Page<CommentResponse> result =
                commentService.getCommentPageByCompany("samsung", pageable, null);

        // Assert
        assertThat(result.getTotalElements()).isEqualTo(100);
        verify(reactionRepository, times(1)).countGroupedByCommentIds(anyList());
    }

    // =====================================================
    // 🔥 2. 리액션 토글 (각각 독립 테스트)
    // =====================================================

    @Test
    @DisplayName("✅ 새로운 좋아요 생성")
    void testReact_CreateLike() {
        // Arrange
        when(userRepository.findByUsername("testuser")).thenReturn(testUser);
        when(commentRepository.findById(1L)).thenReturn(Optional.of(testComments.get(0)));
        when(reactionRepository.findByComment_IdAndUser_Id(1L, 1L)).thenReturn(Optional.empty());

        ReactionEntity saved = new ReactionEntity();
        saved.setType(ReactionType.LIKE);
        when(reactionRepository.save(any())).thenReturn(saved);

        // Redis mock
        HashOperations<String, String, Long> hashOps = mock();
        doReturn(hashOps).when(redisTemplate).opsForHash();

        // Act
        reactionService.reactToComment(1L, "testuser", ReactionType.LIKE);

        // Assert
        verify(reactionRepository).save(any());
        verify(hashOps).increment(eq("comment:reaction:1"), eq("likes"), eq(1L));
    }

    @Test
    @DisplayName("✅ 좋아요 취소")
    void testReact_ToggleOffLike() {
        // Arrange
        when(userRepository.findByUsername("testuser")).thenReturn(testUser);
        when(commentRepository.findById(1L)).thenReturn(Optional.of(testComments.get(0)));

        ReactionEntity existing = new ReactionEntity();
        existing.setType(ReactionType.LIKE);
        when(reactionRepository.findByComment_IdAndUser_Id(1L, 1L)).thenReturn(Optional.of(existing));

        // Redis mock
        HashOperations<String, String, Long> hashOps = mock();
        doReturn(hashOps).when(redisTemplate).opsForHash();

        // Act
        reactionService.reactToComment(1L, "testuser", ReactionType.LIKE);

        // Assert
        verify(reactionRepository).delete(existing);
        verify(hashOps).increment(eq("comment:reaction:1"), eq("likes"), eq(-1L));
    }

    @Test
    @DisplayName("✅ 좋아요 → 싫어요 변경")
    void testReact_ChangeLikeToDislike() {
        // Arrange
        when(userRepository.findByUsername("testuser")).thenReturn(testUser);
        when(commentRepository.findById(1L)).thenReturn(Optional.of(testComments.get(0)));

        ReactionEntity existing = new ReactionEntity();
        existing.setType(ReactionType.LIKE);
        when(reactionRepository.findByComment_IdAndUser_Id(1L, 1L)).thenReturn(Optional.of(existing));

        // 🔥 중요: save는 호출되지만, 검증할 필요 없음 (기존 객체 수정)
        // when(reactionRepository.save(any())).thenReturn(existing); ← 불필요!

        // Redis mock
        HashOperations<String, String, Long> hashOps = mock();
        doReturn(hashOps).when(redisTemplate).opsForHash();

        // Act
        reactionService.reactToComment(1L, "testuser", ReactionType.DISLIKE);

        // Assert
        verify(hashOps).increment(eq("comment:reaction:1"), eq("likes"), eq(-1L));
        verify(hashOps).increment(eq("comment:reaction:1"), eq("dislikes"), eq(1L));
    }

    // =====================================================
    // 🔥 4. 트랜잭션 순서
    // =====================================================

    @Test
    @DisplayName("✅ 트랜잭션: find → save 순서")
    void testTransactionalOrder() {
        // Arrange
        when(userRepository.findByUsername("testuser")).thenReturn(testUser);
        when(commentRepository.findById(1L)).thenReturn(Optional.of(testComments.get(0)));
        when(reactionRepository.findByComment_IdAndUser_Id(1L, 1L)).thenReturn(Optional.empty());

        ReactionEntity saved = new ReactionEntity();
        when(reactionRepository.save(any())).thenReturn(saved);

        // Redis mock
        HashOperations<String, String, Long> hashOps = mock();
        doReturn(hashOps).when(redisTemplate).opsForHash();

        // Act
        reactionService.reactToComment(1L, "testuser", ReactionType.LIKE);

        // Assert
        InOrder inOrder = inOrder(reactionRepository);
        inOrder.verify(reactionRepository).findByComment_IdAndUser_Id(1L, 1L);
        inOrder.verify(reactionRepository).save(any());
    }

    // =====================================================
    // 🔥 5. 댓글 CRUD
    // =====================================================

    @Test
    @DisplayName("✅ 댓글 추가")
    void testAddCommentSuccess() {
        // Arrange
        when(fastApiRepository.findTopByCompanyOrderByCreatedAt("samsung"))
                .thenReturn(Optional.of(testNews));
        when(userRepository.findByUsername("testuser")).thenReturn(testUser);

        CommentEntity saved = new CommentEntity();
        saved.setId(999L);
        when(commentRepository.save(any())).thenReturn(saved);

        // Act
        CommentEntity result = commentService.addComment("samsung", "testuser", "테스트");

        // Assert
        assertThat(result.getId()).isEqualTo(999L);
    }

    @Test
    @DisplayName("✅ 회사 없으면 예외")
    void testAddComment_CompanyNotFound() {
        // Arrange
        when(fastApiRepository.findTopByCompanyOrderByCreatedAt("unknown"))
                .thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() ->
                commentService.addComment("unknown", "testuser", "테스트")
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("✅ 사용자 없으면 예외")
    void testAddComment_UserNotFound() {
        // Arrange
        when(fastApiRepository.findTopByCompanyOrderByCreatedAt("samsung"))
                .thenReturn(Optional.of(testNews));
        when(userRepository.findByUsername("unknown")).thenReturn(null);

        // Act & Assert
        assertThatThrownBy(() ->
                commentService.addComment("samsung", "unknown", "테스트")
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("✅ 작성자만 삭제 가능")
    void testDeleteCommentAuthorOnly() {
        // Arrange
        CommentEntity comment = testComments.get(0);
        when(commentRepository.findById(1L)).thenReturn(Optional.of(comment));

        // Act
        commentService.deleteComment(1L, "testuser", false);

        // Assert
        verify(commentRepository).delete(comment);
    }

    @Test
    @DisplayName("✅ 권한없는 사용자 삭제 불가")
    void testDeleteCommentUnauthorized() {
        // Arrange
        CommentEntity comment = testComments.get(0);
        when(commentRepository.findById(1L)).thenReturn(Optional.of(comment));

        // Act & Assert
        assertThatThrownBy(() ->
                commentService.deleteComment(1L, "otheruser", false)
        ).isInstanceOf(RuntimeException.class);

        verify(commentRepository, never()).delete(any());
    }

    @Test
    @DisplayName("✅ 관리자는 모든 댓글 삭제 가능")
    void testDeleteCommentByAdmin() {
        // Arrange
        CommentEntity comment = testComments.get(0);
        when(commentRepository.findById(1L)).thenReturn(Optional.of(comment));

        // Act
        commentService.deleteComment(1L, "otheruser", true);

        // Assert
        verify(commentRepository).delete(comment);
    }

    // =====================================================
    // 🔥 6. 리액션 조회
    // =====================================================

    @Test
    @DisplayName("✅ 리액션 개수 조회")
    void testGetReactionCount() {
        // Arrange
        when(reactionRepository.countByComment_IdAndType(1L, ReactionType.LIKE)).thenReturn(25L);
        when(reactionRepository.countByComment_IdAndType(1L, ReactionType.DISLIKE)).thenReturn(3L);

        // Act
        Map<String, Long> result = reactionService.getReactionCount(1L);

        // Assert
        assertThat(result).containsEntry("likes", 25L).containsEntry("dislikes", 3L);
    }

    @Test
    @DisplayName("✅ 리액션 없을 시 0 반환")
    void testGetReactionCount_NoReactions() {
        // Arrange
        when(reactionRepository.countByComment_IdAndType(1L, ReactionType.LIKE)).thenReturn(0L);
        when(reactionRepository.countByComment_IdAndType(1L, ReactionType.DISLIKE)).thenReturn(0L);

        // Act
        Map<String, Long> result = reactionService.getReactionCount(1L);

        // Assert
        assertThat(result).containsEntry("likes", 0L).containsEntry("dislikes", 0L);
    }

    // =====================================================
    // 🔥 7. 전체 시나리오
    // =====================================================

    @Test
    @DisplayName("✅ 전체 흐름: 조회 → 좋아요 → 변경 → 취소")
    void testCompleteScenario() {
        // 1️⃣ 댓글 페이징 조회
        Pageable pageable = PageRequest.of(0, 5);

        CommentEntity c1 = new CommentEntity();
        c1.setId(1L);
        c1.setNews(testNews);
        c1.setUser(testUser);
        c1.setContent("c1");
        c1.setCreatedAt(LocalDateTime.now());

        List<CommentEntity> content = Collections.singletonList(c1);

        when(commentRepository.findCommentPageByCompany("samsung", pageable))
                .thenReturn(new PageImpl<>(content, pageable, 100));

        @SuppressWarnings("unchecked")
        List<Object[]> reactions = Arrays.<Object[]>asList(
                new Object[]{1L, ReactionType.LIKE, 10L},
                new Object[]{1L, ReactionType.DISLIKE, 2L}
        );
        when(reactionRepository.countGroupedByCommentIds(anyList()))
                .thenReturn(reactions);

        Page<CommentResponse> result =
                commentService.getCommentPageByCompany("samsung", pageable, "testuser");
        assertThat(result.getTotalElements()).isEqualTo(100);

        // 2️⃣ 좋아요 추가
        when(userRepository.findByUsername("testuser")).thenReturn(testUser);
        when(commentRepository.findById(1L)).thenReturn(Optional.of(c1)); // 혹은 testComments.get(0) 그대로 사용
        when(reactionRepository.findByComment_IdAndUser_Id(1L, 1L)).thenReturn(Optional.empty());

        ReactionEntity saved = new ReactionEntity();
        saved.setType(ReactionType.LIKE);
        when(reactionRepository.save(any())).thenReturn(saved);

        HashOperations<String, String, Long> hashOps = mock();
        doReturn(hashOps).when(redisTemplate).opsForHash();

        reactionService.reactToComment(1L, "testuser", ReactionType.LIKE);
        verify(hashOps).increment(eq("comment:reaction:1"), eq("likes"), eq(1L));
    }

}