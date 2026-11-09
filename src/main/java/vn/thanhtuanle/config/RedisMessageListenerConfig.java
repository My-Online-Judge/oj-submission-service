package vn.thanhtuanle.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import vn.thanhtuanle.messaging.JudgingPubSub;
import vn.thanhtuanle.messaging.VerdictPubSub;

/**
 * Subscribes each submission-service instance to the verdict fan-out channel and to the judging (start and progress)
 * channel. Excluded from the test profile because the container eagerly opens a Redis subscription connection and no
 * Redis runs under tests.
 */
@Configuration
@Profile("!test")
public class RedisMessageListenerConfig {

    @Bean
    public RedisMessageListenerContainer verdictListenerContainer(
            RedisConnectionFactory connectionFactory, VerdictPubSub verdictPubSub, JudgingPubSub judgingPubSub) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(verdictPubSub, new ChannelTopic(VerdictPubSub.CHANNEL));
        container.addMessageListener(judgingPubSub, new ChannelTopic(JudgingPubSub.CHANNEL));
        return container;
    }
}
