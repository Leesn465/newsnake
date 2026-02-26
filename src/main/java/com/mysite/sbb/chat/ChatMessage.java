package com.mysite.sbb.chat;

public record ChatMessage(long id, String from, String text, String type) {
    public static ChatMessage chat(long id, String from, String text) {
        return new ChatMessage(id, from, text, "CHAT");
    }
}
