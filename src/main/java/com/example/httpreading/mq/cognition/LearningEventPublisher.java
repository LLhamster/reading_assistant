package com.example.httpreading.mq.cognition;

import java.util.UUID;

import com.example.httpreading.config.RabbitMQConfig;
import com.example.httpreading.context.manager.ContextManager;
import com.example.httpreading.dto.AiChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

@Service
public class LearningEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(LearningEventPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final ContextManager contextManager;

    public LearningEventPublisher(RabbitTemplate rabbitTemplate, ContextManager contextManager) {
        this.rabbitTemplate = rabbitTemplate;
        this.contextManager = contextManager;
    }

    public String publishAfterAnswer(AiChatRequest request, Integer contextId) {
        String eventId = UUID.randomUUID().toString();
        String recentDialogue = contextId == null ? "" : contextManager.renderHistory(contextId, 6);
        LearningEvent event = new LearningEvent(
            eventId,
            request.resolvedUserId(),
            request.getBookId(),
            request.getChapterIndex(),
            request.resolvedSessionId(),
            request.getQuestion(),
            request.getSelectedText(),
            request.getSelectedContext(),
            request.getChapterTitle(),
            request.getChapterContent(),
            recentDialogue);
        try {
            rabbitTemplate.convertAndSend(
                RabbitMQConfig.COGNITION_EXCHANGE_NAME,
                RabbitMQConfig.COGNITION_ROUTING_KEY,
                event);
            log.debug("已发布认知学习事件 eventId={}, bookId={}, chapterIndex={}",
                eventId, request.getBookId(), request.getChapterIndex());
        } catch (Exception exception) {
            log.warn("发布认知学习事件失败，不影响问答返回 eventId={}, message={}",
                eventId, exception.getMessage());
        }
        return eventId;
    }
}
