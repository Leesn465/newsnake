package com.mysite.sbb.comment.Reaction;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/{commentId}/reactions")
public class ReactionController {
    private final ReactionService reactionService;

    @PostMapping("/like")
    public void like(@PathVariable Long commentId, Authentication auth) {
        String username = auth.getName(); // JWT에서 추출
        reactionService.reactToComment(commentId, username, ReactionType.LIKE);
    }

    @PostMapping("/dislike")
    public void dislike(@PathVariable Long commentId, Authentication auth) {
        String username = auth.getName();
        reactionService.reactToComment(commentId, username, ReactionType.DISLIKE);
    }

    @GetMapping
    public Map<String, Long> getReactions(@PathVariable Long commentId) {
        return reactionService.getReactionCount(commentId);
    }
}
