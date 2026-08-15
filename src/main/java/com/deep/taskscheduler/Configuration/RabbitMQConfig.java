package com.deep.taskscheduler.Configuration;

import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {
    public static final String JOBS_QUEUE = "jobs.queue";
    public static final String RETRY_EXCHANGE = "jobs.retry.exchange";
    public static final String JOBS_EXCHANGE = "jobs.exchange";
    public static final String JOBS_ROUTING_KEY = "job.execute";
    public static final String RETRY_QUEUE = "jobs.retry.queue";

    public static final String DLQ_EXCHANGE = "jobs.dlq.exchange";
    public static final String DLQ_QUEUE = "jobs.dlq.queue";


    @Bean
    public DirectExchange jobExchange(){
        return new DirectExchange(JOBS_EXCHANGE);
    }

    @Bean
    public Queue jobQueue(){
        return QueueBuilder.durable(JOBS_QUEUE)
                .withArgument("x-dead-letter-exchange",RETRY_EXCHANGE).build();
    }

    @Bean
    public Binding jobsBinding(Queue jobQueue, DirectExchange jobExchange) {
        return BindingBuilder.bind(jobQueue).to(jobExchange).with(JOBS_ROUTING_KEY);
    }


    @Bean
    public DirectExchange retryExchange() {
        return new DirectExchange(RETRY_EXCHANGE);
    }

    @Bean
    public Queue retryQueue() {
        return QueueBuilder.durable(RETRY_QUEUE)
                .withArgument("x-dead-letter-exchange", JOBS_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", JOBS_ROUTING_KEY)
                .withArgument("x-message-ttl", 10_000) // 10s delay before retry
                .build();
    }

    @Bean
    public Binding retryBinding(Queue retryQueue, DirectExchange retryExchange) {
        return BindingBuilder.bind(retryQueue).to(retryExchange).with(JOBS_ROUTING_KEY);
    }

    @Bean
    public DirectExchange dlqExchange() {
        return new DirectExchange(DLQ_EXCHANGE);
    }

    @Bean
    public Queue dlqQueue() {
        return QueueBuilder.durable(DLQ_QUEUE).build();
    }

    @Bean
    public Binding dlqBinding(Queue dlqQueue, DirectExchange dlqExchange) {
        return BindingBuilder.bind(dlqQueue).to(dlqExchange).with(JOBS_ROUTING_KEY);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter converter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(converter);
        return template;
    }
}
