package com.mysite.sbb.comment;

import com.mysite.sbb.comment.Reaction.ReactionRepository;
import com.mysite.sbb.comment.Reaction.ReactionType;
import com.mysite.sbb.fastapi.FastApiEntity;
import com.mysite.sbb.fastapi.FastApiRepository;
import com.mysite.sbb.user.SiteUser;
import com.mysite.sbb.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CommentService 테스트")
class CommentServiceTest {

    @Mock
    private CommentRepository commentRepository;

    @Mock
    private ReactionRepository reactionRepository;

    @Mock
    private FastApiRepository fastApiRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private CommentService commentService;

    private SiteUser testUser;
    private FastApiEntity testNews;
    private CommentEntity testComment;
    private CommentResponse testCommentResponse;

    @BeforeEach
    void setUp() {
        // 테스트용 사용자 생성
        testUser = new SiteUser();
        testUser.setId(1L);
        testUser.setUsername("testuser");
        testUser.setAdmin(false);

        // 테스트용 뉴스 엔티티 생성
        testNews = new FastApiEntity();
        testNews.setId(1L);
        testNews.setCompany("samsung");

        // 테스트용 댓글 엔티티 생성
        testComment = new CommentEntity();
        testComment.setId(1L);
        testComment.setNews(testNews);
        testComment.setUser(testUser);
        testComment.setContent("테스트 댓글");
        testComment.setCreatedAt(LocalDateTime.now());

        // 테스트용 댓글 응답 생성 (record 시그니처에 맞게 7개)
        testCommentResponse = new CommentResponse(
                1L,
                "테스트 댓글",
                "testuser",
                LocalDateTime.now(),
                0L,
                0L,
                null
        );
    }

    // ============= getCommentByCompany 테스트 =============
    @Test
    @DisplayName("특정 회사의 댓글을 최신순으로 조회한다")
    void testGetCommentByCompany_Success() {
        // Arrange
        List<CommentEntity> mockComments = Arrays.asList(testComment);
        when(commentRepository.findByNews_CompanyOrderByCreatedAtDesc("samsung"))
                .thenReturn(mockComments);

        // Act
        List<CommentEntity> result = commentService.getCommentByCompany("samsung");

        // Assert
        assertThat(result)
                .isNotEmpty()
                .hasSize(1)
                .contains(testComment);

        verify(commentRepository, times(1))
                .findByNews_CompanyOrderByCreatedAtDesc("samsung");
    }

    @Test
    @DisplayName("존재하지 않는 회사의 댓글 조회 시 빈 리스트를 반환한다")
    void testGetCommentByCompany_Empty() {
        // Arrange
        when(commentRepository.findByNews_CompanyOrderByCreatedAtDesc("nonexistent"))
                .thenReturn(Collections.emptyList());

        // Act
        List<CommentEntity> result = commentService.getCommentByCompany("nonexistent");

        // Assert
        assertThat(result).isEmpty();
    }

    // ============= getCommentPageByCompany 테스트 =============
    @Test
    @DisplayName("페이지네이션과 함께 댓글 및 리액션을 조회한다")
    void testGetCommentPageByCompany_WithReactions() {
        // Arrange
        Pageable pageable = PageRequest.of(0, 20);

        Page<CommentEntity> mockPage = new PageImpl<>(
                Arrays.asList(testComment),
                pageable,
                1
        );
        when(commentRepository.findCommentPageByCompany("samsung", pageable))
                .thenReturn(mockPage);

        List<Object[]> reactionRows = Arrays.asList(
                new Object[]{1L, ReactionType.LIKE, 10L},
                new Object[]{1L, ReactionType.DISLIKE, 2L}
        );
        when(reactionRepository.countGroupedByCommentIds(Arrays.asList(1L)))
                .thenReturn(reactionRows);

        // Act
        Page<CommentResponse> result =
                commentService.getCommentPageByCompany("samsung", pageable, null);

        // Assert
        assertThat(result.getContent()).hasSize(1);
        CommentResponse dto = result.getContent().get(0);
        assertThat(dto.likes()).isEqualTo(10L);
        assertThat(dto.dislikes()).isEqualTo(2L);

        verify(commentRepository, times(1))
                .findCommentPageByCompany("samsung", pageable);
        verify(reactionRepository, times(1))
                .countGroupedByCommentIds(Arrays.asList(1L));
    }

    @Test
    @DisplayName("페이지에 댓글이 없으면 리액션 조회를 생략한다")
    void testGetCommentPageByCompany_EmptyPage() {
        // Arrange
        Pageable pageable = PageRequest.of(0, 20);
        Page<CommentEntity> emptyPage = new PageImpl<>(
                Collections.emptyList(),
                pageable,
                0
        );

        when(commentRepository.findCommentPageByCompany("samsung", pageable))
                .thenReturn(emptyPage);

        // Act
        Page<CommentResponse> result =
                commentService.getCommentPageByCompany("samsung", pageable, null);

        // Assert
        assertThat(result.getContent()).isEmpty();
        verify(reactionRepository, never())
                .countGroupedByCommentIds(anyList());
    }


    @Test
    @DisplayName("여러 댓글의 리액션을 정확하게 매핑한다")
    void testGetCommentPageByCompany_MultipleComments() {
        // Arrange
        Pageable pageable = PageRequest.of(0, 20);

        // Entity 2, 3 만들어주기 (record 아님)
        CommentEntity comment2 = new CommentEntity();
        comment2.setId(2L);
        comment2.setNews(testNews);
        comment2.setUser(testUser);
        comment2.setContent("댓글2");
        comment2.setCreatedAt(LocalDateTime.now());

        CommentEntity comment3 = new CommentEntity();
        comment3.setId(3L);
        comment3.setNews(testNews);
        comment3.setUser(testUser);
        comment3.setContent("댓글3");
        comment3.setCreatedAt(LocalDateTime.now());

        // 리포지토리는 Entity 페이지를 리턴
        Page<CommentEntity> mockPage = new PageImpl<>(
                Arrays.asList(testComment, comment2, comment3),
                pageable,
                3
        );

        when(commentRepository.findCommentPageByCompany("samsung", pageable))
                .thenReturn(mockPage);

        List<Object[]> reactionRows = Arrays.asList(
                new Object[]{1L, ReactionType.LIKE, 5L},
                new Object[]{1L, ReactionType.DISLIKE, 1L},
                new Object[]{2L, ReactionType.LIKE, 0L},
                new Object[]{2L, ReactionType.DISLIKE, 3L},
                new Object[]{3L, ReactionType.LIKE, 8L}
        );

        when(reactionRepository.countGroupedByCommentIds(Arrays.asList(1L, 2L, 3L)))
                .thenReturn(reactionRows);

        // Act
        Page<CommentResponse> result =
                commentService.getCommentPageByCompany("samsung", pageable, null);

        // Assert
        assertThat(result.getContent()).hasSize(3);

        CommentResponse r1 = result.getContent().get(0);
        CommentResponse r2 = result.getContent().get(1);
        CommentResponse r3 = result.getContent().get(2);

        // record 는 getter 가 getLikes() 가 아니라 likes()
        assertThat(r1.likes()).isEqualTo(5L);
        assertThat(r1.dislikes()).isEqualTo(1L);

        assertThat(r2.likes()).isEqualTo(0L);
        assertThat(r2.dislikes()).isEqualTo(3L);

        assertThat(r3.likes()).isEqualTo(8L);
        assertThat(r3.dislikes()).isEqualTo(0L);
    }

    // ============= getCommentsByUsername 테스트 =============
    @Test
    @DisplayName("사용자 이름으로 댓글을 조회한다")
    void testGetCommentsByUsername_Success() {
        // Arrange
        List<CommentEntity> mockComments = Arrays.asList(testComment);
        when(commentRepository.findByUser_UsernameOrderByCreatedAtDesc("testuser"))
                .thenReturn(mockComments);

        // Act
        List<CommentEntity> result = commentService.getCommentsByUsername("testuser");

        // Assert
        assertThat(result)
                .isNotEmpty()
                .hasSize(1)
                .contains(testComment);

        verify(commentRepository, times(1))
                .findByUser_UsernameOrderByCreatedAtDesc("testuser");
    }

    @Test
    @DisplayName("존재하지 않는 사용자의 댓글 조회 시 빈 리스트를 반환한다")
    void testGetCommentsByUsername_Empty() {
        // Arrange
        when(commentRepository.findByUser_UsernameOrderByCreatedAtDesc("nonexistent"))
                .thenReturn(Collections.emptyList());

        // Act
        List<CommentEntity> result = commentService.getCommentsByUsername("nonexistent");

        // Assert
        assertThat(result).isEmpty();
    }

    // ============= addComment 테스트 =============
    @Test
    @DisplayName("댓글을 성공적으로 작성한다")
    void testAddComment_Success() {
        // Arrange
        String content = "새로운 댓글";
        when(fastApiRepository.findTopByCompanyOrderByCreatedAt("samsung"))
                .thenReturn(Optional.of(testNews));
        when(userRepository.findByUsername("testuser"))
                .thenReturn(testUser);
        when(commentRepository.save(any(CommentEntity.class)))
                .thenReturn(testComment);

        // Act
        CommentEntity result = commentService.addComment("samsung", "testuser", content);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getContent()).isEqualTo("테스트 댓글");
        assertThat(result.getUser().getUsername()).isEqualTo("testuser");

        verify(fastApiRepository, times(1))
                .findTopByCompanyOrderByCreatedAt("samsung");
        verify(userRepository, times(1))
                .findByUsername("testuser");
        verify(commentRepository, times(1))
                .save(any(CommentEntity.class));
    }

    @Test
    @DisplayName("회사가 존재하지 않으면 예외를 발생시킨다")
    void testAddComment_CompanyNotFound() {
        // Arrange
        when(fastApiRepository.findTopByCompanyOrderByCreatedAt("nonexistent"))
                .thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() ->
                commentService.addComment("nonexistent", "testuser", "내용")
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("관련 회사가 존재하지 않습니다.");
    }

    @Test
    @DisplayName("사용자가 존재하지 않으면 예외를 발생시킨다")
    void testAddComment_UserNotFound() {
        // Arrange
        when(fastApiRepository.findTopByCompanyOrderByCreatedAt("samsung"))
                .thenReturn(Optional.of(testNews));
        when(userRepository.findByUsername("nonexistent"))
                .thenReturn(null);

        // Act & Assert
        assertThatThrownBy(() ->
                commentService.addComment("samsung", "nonexistent", "내용")
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("사용자가 존재하지 않습니다.");
    }

    // ============= deleteComment 테스트 =============
    @Test
    @DisplayName("댓글 작성자가 자신의 댓글을 삭제한다")
    void testDeleteComment_ByAuthor() {
        // Arrange
        when(commentRepository.findById(1L))
                .thenReturn(Optional.of(testComment));

        // Act
        commentService.deleteComment(1L, "testuser", false);

        // Assert
        verify(commentRepository, times(1)).delete(testComment);
    }

    @Test
    @DisplayName("관리자가 다른 사용자의 댓글을 삭제한다")
    void testDeleteComment_ByAdmin() {
        // Arrange
        when(commentRepository.findById(1L))
                .thenReturn(Optional.of(testComment));

        // Act
        commentService.deleteComment(1L, "otheruser", true);

        // Assert
        verify(commentRepository, times(1)).delete(testComment);
    }

    @Test
    @DisplayName("다른 사용자가 댓글을 삭제하려고 하면 예외를 발생시킨다")
    void testDeleteComment_Unauthorized() {
        // Arrange
        when(commentRepository.findById(1L))
                .thenReturn(Optional.of(testComment));

        // Act & Assert
        assertThatThrownBy(() ->
                commentService.deleteComment(1L, "otheruser", false)
        )
                .isInstanceOf(RuntimeException.class)
                .hasMessage("삭제 권한이 없습니다.");

        verify(commentRepository, never()).delete(any());
    }

    @Test
    @DisplayName("존재하지 않는 댓글을 삭제하려고 하면 예외를 발생시킨다")
    void testDeleteComment_NotFound() {
        // Arrange
        when(commentRepository.findById(999L))
                .thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() ->
                commentService.deleteComment(999L, "testuser", false)
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("댓글이 존재하지 않습니다.");
    }
}