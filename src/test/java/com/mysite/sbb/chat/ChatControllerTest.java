package com.mysite.sbb.chat;

import com.mysite.sbb.config.Clean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;


@ExtendWith(MockitoExtension.class)
public class ChatControllerTest {
    // 🚨 문제 해결: 테스트 내부의 중첩 레코드 정의를 제거하고,
    // 실제 com.mysite.sbb.chat.ChatMessage 클래스를 사용하도록 합니다.
    // (ChatMessage는 별도의 ChatMessage.java 파일에 public record로 정의되어 있다고 가정)

    @InjectMocks
    private ChatController chatController;

    @Mock
    private ChatService chatService;

    @Mock
    private SimpMessagingTemplate simpMessagingTemplate;

    // ChatMessage는 이제 패키지 레벨 클래스를 참조합니다.
    private ChatMessage mockMsg;

    @Mock
    private Clean clean;

    @BeforeEach
    void setUp() {
        mockMsg = new ChatMessage(0, "sender", "hello world", "CHAT");
        // ✅ 기본적으로 비속어가 없는 것으로 설정 (필요 시 각 테스트에서 재설정)
        lenient().when(clean.checkBadWord(anyString())).thenReturn(false);
    }


    // --- 1. /broadcast ---
    @Test
    @DisplayName("메시지 브로드캐스트 및 저장 성공")
    void testSendBroadcast() {
        // Given
        // ID(0) 인수를 추가하여 4개의 인수를 전달합니다.
        ChatMessage input = new ChatMessage(0, "sender", "hello world", "CHAT");

        // 저장 로직 mock (리턴값은 사실 필요 없음)
        when(chatService.saveAndReturnChatMessage(eq("sender"), eq("hello world"), eq("CHAT")))
                .thenReturn(new ChatMessage(1, "sender", "hello world", "CHAT"));

        // filterText가 그대로 반환한다고 가정해서 고정 (이게 중요!)
        when(clean.filterText("hello world")).thenReturn("hello world");

        chatController.send(input);

        // Then
        // 1. 서비스가 메시지 저장에 사용되었는지 확인
        verify(chatService).saveAndReturnChatMessage("sender", "hello world", "CHAT");

        // 2. SimpMessagingTemplate가 올바른 토픽과 메시지로 브로드캐스트했는지 확인
        verify(simpMessagingTemplate).convertAndSend(eq("/topic/broadcast"), eq(mockMsg));
    }

    // --- 2. /join ---
    @Test
    @DisplayName("입장 시 최근 메시지 30개 전송 성공")
    void testJoin() {
        // Given
        String username = "newUser";
        ChatMessage msg1 = new ChatMessage(1, "user1", "hi", "CHAT");
        ChatMessage msg2 = new ChatMessage(2, "user2", "bye", "CHAT");
        List<ChatMessage> recentMessages = List.of(msg1, msg2);

        when(chatService.getRecentMessages()).thenReturn(recentMessages);

        // (만약 join에서도 필터를 탄다면 반드시 mock)
        when(clean.filterText("hi")).thenReturn("hi");
        when(clean.filterText("bye")).thenReturn("bye");

        // When
        chatController.join(username);

        // Then
        verify(chatService, times(1)).getRecentMessages();

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(simpMessagingTemplate, times(2)).convertAndSend(eq("/topic/broadcast"), captor.capture());

        List<ChatMessage> sent = captor.getAllValues();

        // 순서까지 검증하고 싶으면 (보통 join은 최신부터/오래된부터 정책이 있으니 맞춰)
        assertEquals("user1", sent.get(0).from());
        assertEquals("hi", sent.get(0).text());
        assertEquals("CHAT", sent.get(0).type());

        assertEquals("user2", sent.get(1).from());
        assertEquals("bye", sent.get(1).text());
        assertEquals("CHAT", sent.get(1).type());
    }

    // --- 3. /join-leave ---
    @Test
    @DisplayName("퇴장/참여 메시지 전송 성공")
    void testLeave() {
        // When
        chatController.leave(mockMsg);

        // Then
        // SimpMessagingTemplate가 올바른 토픽으로 메시지를 전송했는지 확인
        verify(simpMessagingTemplate, times(1)).convertAndSend(eq("/topic/join-leave"), eq(mockMsg));
    }

    // --- 4. /tag ---
    @Test
    @DisplayName("태그 예측 처리 및 브로드캐스트/개인 피드백 성공")
    void testHandleTag_withUsername() {
        // Given
        String username = "tagUser";
        Map<String, String> chartData = Map.of("username", username, "chart", "data");
        // ID(0) 인수를 추가하여 4개의 인수를 전달합니다.
        ChatMessage predictedMsg = new ChatMessage(0, username, "prediction: tag", "TAG");

        // chatService.tagPrediction 호출 시 예측 메시지 반환을 Mocking
        when(chatService.tagPrediction(eq(chartData), eq(username))).thenReturn(predictedMsg);

        // When
        chatController.handleTag(chartData);

        // Then
        // 1. 서비스가 태그 예측에 사용되었는지 확인
        verify(chatService, times(1)).tagPrediction(eq(chartData), eq(username));

        // 2. 모든 사용자에게 브로드캐스트되었는지 확인
        verify(simpMessagingTemplate, times(1)).convertAndSend(eq("/topic/broadcast"), eq(predictedMsg));

        // 3. 입력 사용자에게 개인 피드백이 전송되었는지 확인
        verify(simpMessagingTemplate, times(1)).convertAndSendToUser(eq(username), eq("/topic/tag-response"), eq(predictedMsg));
    }

    @Test
    @DisplayName("태그 예측 처리 시 username이 null이면 개인 피드백 생략")
    void testHandleTag_withoutUsername() {
        // Given
        Map<String, String> chartData = Map.of("chart", "data"); // username 없음
        // ID(0) 인수를 추가하여 4개의 인수를 전달합니다.
        ChatMessage predictedMsg = new ChatMessage(0, null, "prediction: tag", "TAG");

        // Mocking: 두 번째 인수가 null일 때 (isNull() 단독 사용)
        when(chatService.tagPrediction(eq(chartData), isNull()))
                .thenReturn(predictedMsg);

        // When
        chatController.handleTag(chartData);

        // Then
        // 1. 서비스가 태그 예측에 사용되었는지 확인 (검증 라인에서도 isNull() 사용)
        verify(chatService, times(1)).tagPrediction(eq(chartData), isNull());

        // 2. 모든 사용자에게 브로드캐스트되었는지 확인
        verify(simpMessagingTemplate, times(1)).convertAndSend(eq("/topic/broadcast"), eq(predictedMsg));

        // 3. 개인 피드백은 호출되지 않았는지 확인
        verify(simpMessagingTemplate, never()).convertAndSendToUser(any(), any(), any());
    }

}
