package com.epstein.practice.coreservice.repository

import com.epstein.practice.coreservice.entity.User
import org.springframework.data.jpa.repository.JpaRepository

interface UserRepository : JpaRepository<User, Long>
