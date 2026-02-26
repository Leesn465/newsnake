package com.mysite.sbb.chat;

import com.mysite.sbb.chat.ChatSave.ChatMessageEntity;
import com.mysite.sbb.chat.ChatSave.ChatMessageRepository;
import com.mysite.sbb.fastapi.FastApiEntity;
import com.mysite.sbb.fastapi.FastApiRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ChatServiceTest {

    private FastApiRepository fastApiRepository;
    private ChatMessageRepository chatMessageRepository;

    private ChatService chatService;

    @BeforeEach
    void setUp() {
        fastApiRepository = mock(FastApiRepository.class);
        chatMessageRepository = mock(ChatMessageRepository.class);

        chatService = new ChatService(fastApiRepository, chatMessageRepository);

        // static msgId 초기화
        ChatServiceTestHelper.resetMsgId();
    }

    // ===========================
    // 1. 태그 예측 메시지 생성
    // ===========================
    @Test
    void testTagPrediction() {
        FastApiEntity entity = new FastApiEntity();
        entity.setCompany("삼성전자");
        entity.setPrediction("상승");

        when(fastApiRepository.findTopByCompanyOrderByCreatedAt("삼성전자"))
                .thenReturn(Optional.of(entity));

        Map<String, String> map = Map.of("company", "삼성전자");

        ChatMessage result = chatService.tagPrediction(map, "min");

        assertThat(result.text()).isEqualTo("@삼성전자 - [상승]");
        verify(chatMessageRepository, times(1)).save(any(ChatMessageEntity.class));
    }

    // =================================
    // 1-1. 회사 정보 없을 때 "정보 없음"
    // =================================
    @Test
    void testTagPrediction_NoCompanyInfo() {
        when(fastApiRepository.findTopByCompanyOrderByCreatedAt("삼성전자"))
                .thenReturn(Optional.empty());

        Map<String, String> map = Map.of("company", "삼성전자");

        ChatMessage result = chatService.tagPrediction(map, "min");

        assertThat(result.text()).isEqualTo("@삼성전자 - [정보 없음]");
    }

    // ======================
    // 2. 회사 검색 기능
    // ======================
    @Test
    void testSearchCompany() {
        FastApiEntity e1 = new FastApiEntity();
        e1.setCompany("삼성전자");

        FastApiEntity e2 = new FastApiEntity();
        e2.setCompany("삼성바이오");

        when(fastApiRepository.findByCompanyContainingIgnoreCase("삼성"))
                .thenReturn(List.of(e1, e2));

        List<String> result = chatService.searchCompany("삼성");

        assertThat(result).containsExactly("삼성전자", "삼성바이오");
    }

    // ======================
    // 3. 채팅 메시지 저장
    // ======================
    @Test
    void testSaveAndReturnChatMessage() {
        ChatMessageEntity savedEntity = new ChatMessageEntity();
        savedEntity.setId(1L);

        when(chatMessageRepository.save(any(ChatMessageEntity.class)))
                .thenReturn(savedEntity);

        ChatMessage msg = chatService.saveAndReturnChatMessage("min", "hello", "USER");

        assertThat(msg.from()).isEqualTo("min");
        assertThat(msg.text()).isEqualTo("hello");
        assertThat(msg.type()).isEqualTo("USER");
    }

    // ======================
    // 4. 최신 메시지 조회
    // ======================
    @Test
    void testGetRecentMessages() {
        ChatMessageEntity e1 = new ChatMessageEntity();
        e1.setId(3L);
        e1.setFromUser("min");
        e1.setText("세번째");
        e1.setType("USER");

        ChatMessageEntity e2 = new ChatMessageEntity();
        e2.setId(2L);
        e2.setFromUser("min");
        e2.setText("두번째");
        e2.setType("USER");

        ChatMessageEntity e3 = new ChatMessageEntity();
        e3.setId(1L);
        e3.setFromUser("min");
        e3.setText("첫번째");
        e3.setType("USER");

        when(chatMessageRepository.findTop30ByOrderByIdDesc())
                .thenReturn(List.of(e1, e2, e3));

        List<ChatMessage> result = chatService.getRecentMessages();

        assertThat(result).hasSize(3);
        assertThat(result.get(0).text()).isEqualTo("첫번째");
        assertThat(result.get(1).text()).isEqualTo("두번째");
        assertThat(result.get(2).text()).isEqualTo("세번째");
    }
}
