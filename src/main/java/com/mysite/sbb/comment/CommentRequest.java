package com.mysite.sbb.comment;

import lombok.Data;

@Data
public class CommentRequest {
    private String username;
    private String content;
}
