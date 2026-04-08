package com.epstein.practice.reserveservice.repository.support

import com.epstein.practice.reserveservice.dto.SectionAvailabilityResponse
import com.epstein.practice.reserveservice.entity.QSeat
import com.epstein.practice.reserveservice.entity.SeatStatus
import com.querydsl.core.types.Projections
import com.querydsl.jpa.JPAExpressions
import com.querydsl.jpa.impl.JPAQueryFactory
import org.springframework.stereotype.Repository

@Repository
class SeatQueryRepository(
    private val queryFactory: JPAQueryFactory
) {

    fun countAvailableBySection(eventId: Long): List<SectionAvailabilityResponse> {
        val seat = QSeat.seat
        val sub = QSeat("sub")

        return queryFactory
            .select(
                Projections.constructor(
                    SectionAvailabilityResponse::class.java,
                    seat.section,
                    seat.count(),
                    JPAExpressions
                        .select(sub.count())
                        .from(sub)
                        .where(
                            sub.event.id.eq(eventId),
                            sub.section.eq(seat.section)
                        )
                )
            )
            .from(seat)
            .where(
                seat.event.id.eq(eventId),
                seat.status.eq(SeatStatus.AVAILABLE)
            )
            .groupBy(seat.section)
            .orderBy(seat.section.asc())
            .fetch()
    }
}
