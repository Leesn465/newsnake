package com.mysite.sbb.chat;

import com.mysite.sbb.config.Clean;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.Map;

@Controller
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final SimpMessagingTemplate simpMessagingTemplate;
    private final Clean clean;

    @MessageMapping("/broadcast")
    public void send(ChatMessage msg) {
        // 1. 화면 전송용 별표 처리
        String filteredContent = clean.filterText(msg.text());

        // 1) DB 저장 (원본 텍스트 저장)
        ChatMessage saved = chatService.saveAndReturnChatMessage(msg.from(), msg.text(), msg.type());

        // 2. 브로드캐스트용 객체 (별표 처리됨)
        ChatMessage filteredMsg = new ChatMessage(saved.id(), saved.from(), filteredContent, saved.type());

        simpMessagingTemplate.convertAndSend("/topic/broadcast", filteredMsg);
    }

    @MessageMapping("/join")
    public void join(String username) {
        List<ChatMessage> recentMessages = chatService.getRecentMessages();
        for (ChatMessage msg : recentMessages) {
            // [중요] DB에서 꺼내온 과거 내역도 별표 처리해서 전송
            String filtered = clean.filterText(msg.text());
            ChatMessage filteredMsg = new ChatMessage(msg.id(), msg.from(), filtered, msg.type());
            simpMessagingTemplate.convertAndSend("/topic/broadcast", filteredMsg);
        }
    }


    @MessageMapping("/join-leave")
    public void leave(ChatMessage msg) {
        simpMessagingTemplate.convertAndSend("/topic/join-leave", msg);
    }

    @MessageMapping("/tag")
    public void handleTag(Map<String, String> chart) {
        String username = chart.get("username");
        ChatMessage msg = chatService.tagPrediction(chart, username);

        simpMessagingTemplate.convertAndSend("/topic/broadcast", msg);

        if(username != null) {
            simpMessagingTemplate.convertAndSendToUser(username, "/topic/tag-response", msg);
        }
    }
}