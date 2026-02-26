package com.mysite.sbb.config;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class IgnoreInvalidSessionAspect {

    @Around("execution(* org.springframework.session.data.redis.RedisSessionRepository.save(..))")
    public Object ignoreInvalidSession(ProceedingJoinPoint pjp) throws Throwable {
        try {
            return pjp.proceed();
        } catch (IllegalStateException e) {
            if (e.getMessage().contains("Session was invalidated")) {
                System.out.println("⚠️ Redis session save skipped (session invalidated)");
                return null;
            }
            throw e;
        }
    }
}