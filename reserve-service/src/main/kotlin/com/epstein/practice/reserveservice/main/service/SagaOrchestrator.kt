package com.epstein.practice.reserveservice.main.service

import com.epstein.practice.common.event.CreatePaymentCommand
import com.epstein.practice.common.event.ProcessPaymentCommand
import com.epstein.practice.common.outbox.OutboxService
import com.epstein.practice.reserveservice.config.KafkaConfig
import com.epstein.practice.reserveservice.main.cache.EventCacheRepository
import com.epstein.practice.reserveservice.main.repository.SagaRepository
import com.epstein.practice.reserveservice.main.repository.SeatRepository
import com.epstein.practice.reserveservice.type.constant.SagaStatus
import com.epstein.practice.reserveservice.type.constant.SagaStep
import com.epstein.practice.reserveservice.type.entity.ReservationSaga
import com.epstein.practice.reserveservice.type.entity.SeatStatus
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.ZonedDateTime

@Service
class SagaOrchestrator(
    private val sagaRepository: SagaRepository,
    private val seatRepository: SeatRepository,
    private val eventCache: EventCacheRepository,
    private val outboxService: OutboxService,
    meterRegistry: MeterRegistry,
) {
    private val logger = LoggerFactory.getLogger(SagaOrchestrator::class.java)

    private val sagaStarted = Counter.builder("saga.started").description("Saga 시작 수").register(meterRegistry)
    private val sagaCompleted = Counter.builder("saga.completed").description("결제 성공").register(meterRegistry)
    private val sagaFailed = Counter.builder("saga.failed").description("결제 실패").register(meterRegistry)
    private val sagaExpired = Counter.builder("saga.expired").description("타임아웃 만료").register(meterRegistry)
    private val sagaCancelled = Counter.builder("saga.cancelled").description("사용자 취소").register(meterRegistry)

    @Transactional
    fun startSaga(eventId: Long, userId: Long, seatId: Long, amount: Long): Long {
        val saga = sagaRepository.save(
            ReservationSaga(
                eventId = eventId,
                userId = userId,
                seatId = seatId,
                step = SagaStep.SEAT_HELD,
                status = SagaStatus.IN_PROGRESS,
            )
        )
        outboxService.save(
            KafkaConfig.TOPIC_PAYMENT_COMMANDS,
            seatId.toString(),
            CreatePaymentCommand(
                sagaId = saga.id,
                seatId = seatId,
                userId = userId,
                eventId = eventId,
                amount = amount,
            )
        )
        sagaStarted.increment()
        logger.info("Saga started: id={}, seat={}, user={}", saga.id, seatId, userId)
        return saga.id
    }

    @Transactional
    fun onPaymentCreated(sagaId: Long, paymentId: Long) {
        val saga = sagaRepository.findById(sagaId).orElse(null) ?: return
        if (saga.status != SagaStatus.IN_PROGRESS || saga.step != SagaStep.SEAT_HELD) return

        saga.paymentId = paymentId
        saga.step = SagaStep.PAYMENT_CREATED
        saga.updatedAt = ZonedDateTime.now()
        logger.info("Saga step: PAYMENT_CREATED, id={}, paymentId={}", sagaId, paymentId)
    }

    @Transactional
    fun requestPayment(sagaId: Long, method: String) {
        val saga = sagaRepository.findById(sagaId).orElse(null) ?: return
        if (saga.status != SagaStatus.IN_PROGRESS || saga.step != SagaStep.PAYMENT_CREATED) return

        saga.step = SagaStep.PAYMENT_PROCESSING
        saga.updatedAt = ZonedDateTime.now()

        outboxService.save(
            KafkaConfig.TOPIC_PAYMENT_COMMANDS,
            saga.seatId.toString(),
            ProcessPaymentCommand(
                sagaId = saga.id,
                seatId = saga.seatId,
                userId = saga.userId,
                method = method,
            )
        )
        logger.info("Saga step: PAYMENT_PROCESSING, id={}", sagaId)
    }

    @Transactional
    fun onPaymentSucceeded(sagaId: Long, paymentId: Long) {
        val saga = sagaRepository.findById(sagaId).orElse(null) ?: return
        if (saga.status != SagaStatus.IN_PROGRESS) return

        val seat = seatRepository.findById(saga.seatId).orElse(null) ?: return
        if (seat.status != SeatStatus.PAYMENT_PENDING) return

        seat.status = SeatStatus.RESERVED
        seat.reservedAt = ZonedDateTime.now()

        saga.step = SagaStep.COMPLETED
        saga.status = SagaStatus.COMPLETED
        saga.updatedAt = ZonedDateTime.now()

        sagaCompleted.increment()
        logger.info("Saga completed: id={}, seat={} RESERVED", sagaId, saga.seatId)
    }

    @Transactional
    fun onPaymentFailed(sagaId: Long, reason: String) {
        val saga = sagaRepository.findById(sagaId).orElse(null) ?: return
        if (saga.status != SagaStatus.IN_PROGRESS) return

        compensate(saga, SagaStatus.FAILED)
        sagaFailed.increment()
        logger.info("Saga failed: id={}, reason={}", sagaId, reason)
    }

    @Transactional
    fun onTimeout(sagaId: Long) {
        val saga = sagaRepository.findById(sagaId).orElse(null) ?: return
        if (saga.status != SagaStatus.IN_PROGRESS) return

        compensate(saga, SagaStatus.EXPIRED)
        sagaExpired.increment()
        logger.info("Saga expired: id={}, seat={}", sagaId, saga.seatId)
    }

    @Transactional
    fun onCancel(sagaId: Long) {
        val saga = sagaRepository.findById(sagaId).orElse(null) ?: return
        if (saga.status != SagaStatus.IN_PROGRESS) return

        compensate(saga, SagaStatus.CANCELLED)
        sagaCancelled.increment()
        logger.info("Saga cancelled: id={}, seat={}", sagaId, saga.seatId)
    }

    private fun compensate(saga: ReservationSaga, endStatus: SagaStatus) {
        saga.step = SagaStep.COMPENSATING
        saga.updatedAt = ZonedDateTime.now()

        val seat = seatRepository.findById(saga.seatId).orElse(null)
        val needsRedisCompensation = seat != null && seat.status == SeatStatus.PAYMENT_PENDING
        if (needsRedisCompensation) {
            seat!!.status = SeatStatus.AVAILABLE
            seat.userId = null
            seat.reservedAt = null
        }

        saga.step = SagaStep.COMPENSATED
        saga.status = endStatus
        saga.updatedAt = ZonedDateTime.now()

        if (needsRedisCompensation) {
            val eventId = saga.eventId
            val seatId = seat!!.id
            val section = seat.section
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() {
                    eventCache.adjustSeatCounts(eventId, 1, section)
                    eventCache.markSeatAvailable(eventId, seatId)
                }
            })
        }
    }

    fun findActiveSaga(eventId: Long, userId: Long): ReservationSaga? {
        return sagaRepository.findFirstByEventIdAndUserIdAndStatusInOrderByIdDesc(
            eventId, userId, listOf(SagaStatus.IN_PROGRESS)
        )
    }

    fun getSaga(sagaId: Long): ReservationSaga? {
        return sagaRepository.findById(sagaId).orElse(null)
    }
}
