package com.epstein.practice.userservice.repository

import com.epstein.practice.userservice.entity.User
import org.springframework.data.jpa.repository.JpaRepository

interface UserRepository : JpaRepository<User, Long>
