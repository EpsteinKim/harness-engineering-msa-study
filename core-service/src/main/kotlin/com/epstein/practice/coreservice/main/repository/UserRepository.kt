package com.epstein.practice.coreservice.main.repository

import com.epstein.practice.coreservice.type.entity.User
import org.springframework.data.jpa.repository.JpaRepository

interface UserRepository : JpaRepository<User, Long>
