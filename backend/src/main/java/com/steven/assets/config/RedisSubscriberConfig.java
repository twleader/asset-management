package com.steven.assets.config;

import com.steven.assets.service.PriceStreamService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;

/**
 * 訂閱 Redis pub/sub channel `price-update`，把訊息餵給 PriceStreamService 的 sink。
 * 由 price-service 在每次寫 Redis 後 publish。
 */
@Configuration
public class RedisSubscriberConfig {

    @Bean
    public RedisMessageListenerContainer priceUpdateListenerContainer(
            RedisConnectionFactory connectionFactory,
            PriceStreamService priceStreamService) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);

        MessageListenerAdapter adapter = new MessageListenerAdapter((MessageHandler) priceStreamService::onPriceUpdate);
        adapter.setSerializer(new org.springframework.data.redis.serializer.StringRedisSerializer());
        adapter.afterPropertiesSet();

        container.addMessageListener(adapter, new ChannelTopic("price-update"));
        return container;
    }

    @FunctionalInterface
    public interface MessageHandler {
        void handleMessage(String message);
    }
}
