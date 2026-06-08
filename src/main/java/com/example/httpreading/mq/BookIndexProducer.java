package com.example.httpreading.mq;

import com.example.httpreading.config.RabbitMQConfig;
import com.example.httpreading.dto.BookIndexMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class BookIndexProducer {

    private static final Logger log = LoggerFactory.getLogger(BookIndexProducer.class);

    private final RabbitTemplate rabbitTemplate;

    public BookIndexProducer(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * 发送书籍索引消息（异步触发 ES 写入）
     */
    public void sendIndexMessage(Long bookId) {
        sendMessageAfterCommit(bookId, "index");
    }

    /**
     * 发送书籍删除消息（异步触发 ES 删除）
     */
    public void sendDeleteMessage(Long bookId) {
        sendMessageAfterCommit(bookId, "delete");
    }

    private void sendMessageAfterCommit(Long bookId, String action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()
            && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    doSendMessage(bookId, action);
                }
            });
            log.debug("注册事务提交后发送书籍索引消息 - bookId:{}, action:{}", bookId, action);
            return;
        }
        doSendMessage(bookId, action);
    }

    private void doSendMessage(Long bookId, String action) {
        log.info("发送书籍索引消息 - bookId:{}, action:{}", bookId, action);
        rabbitTemplate.convertAndSend(
            RabbitMQConfig.EXCHANGE_NAME,
            RabbitMQConfig.ROUTING_KEY,
            new BookIndexMessage(bookId, action)
        );
    }
}
