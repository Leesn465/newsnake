package com.mysite.sbb.comment;

import com.mysite.sbb.comment.Reaction.ReactionEntity;
import com.mysite.sbb.fastapi.FastApiEntity;
import com.mysite.sbb.user.SiteUser;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Entity
@Getter @Setter
public class CommentEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 어떤 뉴스에 달린 댓글인지 (N:1)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "news_id")
    private FastApiEntity news;

    // 누가 작성했는지 (N:1)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private SiteUser user;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "created_at", updatable = false, nullable = false)
    @CreationTimestamp
    private LocalDateTime createdAt;

    // 댓글 ↔ 리액션 (1:N)
    @OneToMany(mappedBy = "comment", cascade = CascadeType.REMOVE, orphanRemoval = true)
    private Set<ReactionEntity> reactions = new HashSet<>();

}
