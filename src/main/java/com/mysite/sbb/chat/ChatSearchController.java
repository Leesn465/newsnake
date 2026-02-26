package com.mysite.sbb.chat;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class ChatSearchController {

    private final ChatService chatService;


    @GetMapping("/api/company/search")
    public List<String> searchCompany(@RequestParam String q) {
        // DB에서 회사명이 q를 포함하는 것 조회, 최대 10개
        return chatService.searchCompany(q);
    }
}
