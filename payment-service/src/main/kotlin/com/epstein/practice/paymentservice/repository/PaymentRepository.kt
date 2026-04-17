package com.epstein.practice.paymentservice.repository

import com.epstein.practice.paymentservice.entity.Payment
import com.epstein.practice.paymentservice.entity.PaymentStatus
import org.springframework.data.jpa.repository.JpaRepository

interface PaymentRepository : JpaRepository<Payment, Long> {
    fun findByUserIdOrderByCreatedAtDesc(userId: Long): List<Payment>
    fun findBySeatIdAndStatus(seatId: Long, status: PaymentStatus): Payment?
}
