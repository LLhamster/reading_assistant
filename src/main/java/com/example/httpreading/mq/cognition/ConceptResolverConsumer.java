package com.example.httpreading.mq.cognition;

import com.example.httpreading.config.RabbitMQConfig;
import com.example.httpreading.service.cognition.ConceptResolverService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class ConceptResolverConsumer {
    private static final Logger log = LoggerFactory.getLogger(ConceptResolverConsumer.class);

    private final ConceptResolverService conceptResolverService;

    public ConceptResolverConsumer(ConceptResolverService conceptResolverService) {
        this.conceptResolverService = conceptResolverService;
    }

    @RabbitListener(queues = RabbitMQConfig.COGNITION_QUEUE_NAME)
    public void handleLearningEvent(LearningEvent event) {
        try {
            conceptResolverService.resolve(event);
        } catch (Exception exception) {
            String eventId = event == null ? "unknown" : event.getEventId();
            log.warn("认知概念解析失败，事件将被吞掉以避免阻塞问答链路 eventId={}, message={}",
                eventId, exception.getMessage(), exception);
        }
    }
}
