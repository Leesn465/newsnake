package com.mysite.sbb.comment;

import com.mysite.sbb.comment.Reaction.ReactionType;

import java.time.LocalDateTime;


public record CommentResponse(
        Long id,
        String content,
        String username,
        LocalDateTime createdAt,
        long likes,
        long dislikes,
        ReactionType userReaction
) {

    public static CommentResponse fromEntity(
            CommentEntity comment,
            long likes,
            long dislikes,
            ReactionType userReaction
    ) {
        return new CommentResponse(
                comment.getId(),
                comment.getContent(),
                comment.getUser().getUsername(),
                comment.getCreatedAt(),
                likes,
                dislikes,
                userReaction
        );
    }
}