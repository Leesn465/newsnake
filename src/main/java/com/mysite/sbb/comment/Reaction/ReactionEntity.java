package com.mysite.sbb.comment.Reaction;

import com.mysite.sbb.comment.CommentEntity;
import com.mysite.sbb.user.SiteUser;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(
        name = "reaction_entity",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_reaction_comment_user",
                columnNames = {"comment_id", "user_id"}
        )
)
@Setter @Getter
public class ReactionEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "comment_id")
    private CommentEntity comment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private SiteUser user;

    // LIKE or DISLIKE
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReactionType type;
}
