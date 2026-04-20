package com.epstein.practice.paymentservice.main.repository

import com.epstein.practice.paymentservice.type.entity.Payment
import com.epstein.practice.paymentservice.type.entity.PaymentStatus
import org.springframework.data.jpa.repository.JpaRepository

interface PaymentRepository : JpaRepository<Payment, Long> {
    fun findByUserIdOrderByCreatedAtDesc(userId: Long): List<Payment>
    fun findBySeatIdAndStatus(seatId: Long, status: PaymentStatus): Payment?
}
