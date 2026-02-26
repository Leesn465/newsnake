package com.mysite.sbb.chat;

// 테스트용: ChatService의 msgId 초기화
public class ChatServiceTestHelper {
    public static void resetMsgId() {
        try {
            java.lang.reflect.Field field =
                    ChatService.class.getDeclaredField("msgId");
            field.setAccessible(true);
            field.setInt(null, 0);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}