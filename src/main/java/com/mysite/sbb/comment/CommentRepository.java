package com.mysite.sbb.comment;

import io.lettuce.core.dynamic.annotation.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CommentRepository extends JpaRepository<CommentEntity, Long> {

    /**
     * 특정 회사에 달린 댓글 전체 조회
     * → 최신순 정렬
     */
    List<CommentEntity> findByNews_CompanyOrderByCreatedAtDesc(String company);

    /**
     * 마이페이지에서 내가 쓴 댓글 목록 조회용
     */
    List<CommentEntity> findByUser_UsernameOrderByCreatedAtDesc(String username);

    /**
     * 회사별 댓글을 페이지네이션으로 조회
     * <p>
     * 이 쿼리를 JPQL로 직접 작성한 이유:
     * - News, User를 명시적으로 JOIN
     * - 프론트에서 댓글 목록 + 작성자 + 뉴스 정보 동시에 필요
     * - Lazy 로딩으로 인한 N+1 문제 방지
     * <p>
     * 실전 서비스용 조회 쿼리
     */
    @Query("""
                select c
                from CommentEntity c
                join c.news n
                join c.user u
                where n.company = :company
                order by c.createdAt desc
            """)
    Page<CommentEntity> findCommentPageByCompany(@Param("company") String company, Pageable pageable);
}
