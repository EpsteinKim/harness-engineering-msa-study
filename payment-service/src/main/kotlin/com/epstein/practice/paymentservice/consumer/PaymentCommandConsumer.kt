package com.epstein.practice.paymentservice.consumer

import com.epstein.practice.common.event.CreatePaymentCommand
import com.epstein.practice.common.event.PaymentCreated
import com.epstein.practice.common.event.ProcessPaymentCommand
import com.epstein.practice.common.outbox.OutboxService
import com.epstein.practice.paymentservice.config.KafkaConfig
import com.epstein.practice.paymentservice.main.service.PaymentService
import com.epstein.practice.paymentservice.producer.PaymentProcessingService
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaHandler
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
@KafkaListener(topics = [KafkaConfig.TOPIC_PAYMENT_COMMANDS])
class PaymentCommandConsumer(
    private val paymentService: PaymentService,
    private val processingService: PaymentProcessingService,
    private val outboxService: OutboxService,
) {
    private val logger = LoggerFactory.getLogger(PaymentCommandConsumer::class.java)

    @KafkaHandler
    fun onCreatePayment(command: CreatePaymentCommand) {
        logger.info("CreatePaymentCommand received: sagaId={}, seat={}", command.sagaId, command.seatId)
        val payment = paymentService.createPendingForSeat(
            seatId = command.seatId,
            userId = command.userId,
            eventId = command.eventId,
            amount = command.amount,
        )
        outboxService.save(
            KafkaConfig.TOPIC_PAYMENT_EVENTS,
            command.seatId.toString(),
            PaymentCreated(
                sagaId = command.sagaId,
                seatId = command.seatId,
                userId = command.userId,
                paymentId = payment.id,
            )
        )
    }

    @KafkaHandler
    fun onProcessPayment(command: ProcessPaymentCommand) {
        logger.info("ProcessPaymentCommand received: sagaId={}, seat={}", command.sagaId, command.seatId)
        processingService.process(command)
    }
}
