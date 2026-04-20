package com.epstein.practice.reserveservice.config

import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.config.TopicBuilder
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer
import org.springframework.kafka.support.serializer.JacksonJsonSerializer

@Configuration
@EnableKafka
class KafkaConfig {

    @Bean
    fun producerFactory(
        @Value("\${spring.kafka.bootstrap-servers}") bootstrapServers: String,
    ): ProducerFactory<String, Any> {
        val props = mapOf<String, Any>(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to JacksonJsonSerializer::class.java,
            ProducerConfig.ACKS_CONFIG to "1",
        )
        return DefaultKafkaProducerFactory(props)
    }

    @Bean
    fun kafkaTemplate(producerFactory: ProducerFactory<String, Any>): KafkaTemplate<String, Any> =
        KafkaTemplate(producerFactory)

    @Bean
    fun consumerFactory(
        @Value("\${spring.kafka.bootstrap-servers}") bootstrapServers: String,
    ): ConsumerFactory<String, Any> {
        val deserializer = JacksonJsonDeserializer<Any>().apply {
            addTrustedPackages("com.epstein.practice.reserveservice.type.event", "com.epstein.practice.common.event")
        }
        val props = mapOf<String, Any>(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ConsumerConfig.GROUP_ID_CONFIG to CONSUMER_GROUP_ID,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
        )
        return DefaultKafkaConsumerFactory(props, StringDeserializer(), deserializer)
    }

    @Bean
    fun kafkaListenerContainerFactory(
        consumerFactory: ConsumerFactory<String, Any>,
    ): ConcurrentKafkaListenerContainerFactory<String, Any> {
        val factory = ConcurrentKafkaListenerContainerFactory<String, Any>()
        factory.setConsumerFactory(consumerFactory)
        factory.setMissingTopicsFatal(false)
        return factory
    }

    @Bean
    fun reserveQueueTopic(): NewTopic =
        TopicBuilder.name(TOPIC_QUEUE)
            .partitions(DEFAULT_PARTITIONS)
            .replicas(DEFAULT_REPLICATION)
            .build()

    @Bean
    fun seatEventsTopic(): NewTopic =
        TopicBuilder.name(TOPIC_SEAT_EVENTS)
            .partitions(DEFAULT_PARTITIONS)
            .replicas(DEFAULT_REPLICATION)
            .build()

    @Bean
    fun paymentEventsTopic(): NewTopic =
        TopicBuilder.name(TOPIC_PAYMENT_EVENTS)
            .partitions(DEFAULT_PARTITIONS)
            .replicas(DEFAULT_REPLICATION)
            .build()

    companion object {
        const val TOPIC_QUEUE = "reserve.queue"
        const val TOPIC_SEAT_EVENTS = "seat.events"
        const val TOPIC_PAYMENT_EVENTS = "payment.events"
        const val TOPIC_SYSTEM_TICKS = "system.ticks"
        const val TOPIC_EVENT_LIFECYCLE = "event.lifecycle"
        const val DEFAULT_PARTITIONS = 10
        const val DEFAULT_REPLICATION = 1
        const val PARTITION_BUCKETS = DEFAULT_PARTITIONS
        const val CONSUMER_GROUP_ID = "reserve-service"
    }
}
