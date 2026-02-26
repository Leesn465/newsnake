package com.mysite.sbb.comment;

import com.mysite.sbb.config.Clean;
import com.mysite.sbb.jwt.Oauth.PrincipalDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class CommentController {

    private final CommentService commentService;
    private final Clean clean;

    @GetMapping("/{company}/comments")
    public ResponseEntity<Page<CommentResponse>> getComments(
            @PathVariable String company,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            Authentication auth
    ) {
        String username = (auth != null) ? auth.getName() : null;

        Page<CommentResponse> comments =
                commentService.getCommentPageByCompany(company, pageable, username);

        // record 는 불변이라, 필터 적용된 새 인스턴스로 매핑
        Page<CommentResponse> filtered = comments.map(c ->
                new CommentResponse(
                        c.id(),
                        clean.filterText(c.content()),
                        c.username(),
                        c.createdAt(),
                        c.likes(),
                        c.dislikes(),
                        c.userReaction()
                )
        );

        return ResponseEntity.ok(filtered);
    }

    @GetMapping("/user/{username}/comments")
    public ResponseEntity<List<CommentResponse>> getUserComments(@PathVariable String username) {
        List<CommentResponse> userComments = commentService.getCommentsByUsername(username).stream()
                .map(comment -> CommentResponse.fromEntity(
                        comment,
                        0L,
                        0L,
                        null
                ))
                .map(c -> new CommentResponse(
                        c.id(),
                        clean.filterText(c.content()),
                        c.username(),
                        c.createdAt(),
                        c.likes(),
                        c.dislikes(),
                        c.userReaction()
                ))
                .toList();

        return ResponseEntity.ok(userComments);
    }

    @PostMapping("/{company}/comments")
    public ResponseEntity<CommentResponse> addComment(
            @PathVariable String company,
            @RequestBody CommentRequest request,
            Authentication auth
    ) {
        String username = auth.getName();

        CommentEntity comment = commentService.addComment(company, username, request.getContent());

        CommentResponse response = CommentResponse.fromEntity(
                comment,
                0L,
                0L,
                null
        );

        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{company}/comments/{commentId}")
    public ResponseEntity<Void> deleteComment(@PathVariable Long commentId, Authentication auth) {
        String username = auth.getName();
        boolean isAdmin = false;
        if (auth.getPrincipal() instanceof PrincipalDetails principal) {
            isAdmin = principal.getSiteUser().isAdmin();
        }
        commentService.deleteComment(commentId, username, isAdmin);
        return ResponseEntity.ok().build();
    }
}