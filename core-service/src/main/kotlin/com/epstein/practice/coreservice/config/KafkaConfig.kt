package com.epstein.practice.coreservice.config

import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory
import org.springframework.kafka.support.serializer.JacksonJsonSerializer

@Configuration
class KafkaConfig {

    @Bean
    fun kafkaAdmin(
        @Value("\${spring.kafka.bootstrap-servers}") bootstrapServers: String,
    ): KafkaAdmin = KafkaAdmin(mapOf(
        AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
    )).apply {
        setFatalIfBrokerNotAvailable(true)
        setModifyTopicConfigs(true)
    }

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
    fun systemTicksTopic(): NewTopic =
        TopicBuilder.name(TOPIC_SYSTEM_TICKS)
            .partitions(SYSTEM_TICKS_PARTITIONS)
            .replicas(DEFAULT_REPLICATION)
            .build()

    @Bean
    fun eventLifecycleTopic(): NewTopic =
        TopicBuilder.name(TOPIC_EVENT_LIFECYCLE)
            .partitions(EVENT_LIFECYCLE_PARTITIONS)
            .replicas(DEFAULT_REPLICATION)
            .build()

    companion object {
        const val TOPIC_SYSTEM_TICKS = "system.ticks"
        const val TOPIC_EVENT_LIFECYCLE = "event.lifecycle"
        const val SYSTEM_TICKS_PARTITIONS = 1
        const val EVENT_LIFECYCLE_PARTITIONS = 1
        const val DEFAULT_REPLICATION = 1
    }
}
