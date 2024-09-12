package com.example.authproxy.service;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class CacheService {

    private final RedisTemplate<String, String> redisTemplate;

    public CacheService(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean isTokenValid(String token) {
        return redisTemplate.hasKey(token);
    }

    public void cacheToken(String token) {
        redisTemplate.opsForValue().set(token, "valid", 30, TimeUnit.MINUTES);
    }
}
