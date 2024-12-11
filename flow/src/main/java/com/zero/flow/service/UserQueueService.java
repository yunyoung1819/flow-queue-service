package com.zero.flow.service;

import com.zero.flow.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuples;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserQueueService {
    private final ReactiveRedisTemplate<String, String> reactiveRedisTemplate;

    private final String USER_QUEUE_WAIT_KEY = "users:queue:%s:wait";

    private final String USER_QUEUE_WAIT_KEY_FOR_SCAN = "users:queue:*:wait";

    private final String USER_QUEUE_PROCEED_KEY = "users:queue:%s:proceed";

    @Value("${scheduler.enabled}")
    private Boolean scheduling = false;

    // 1. 대기열 등록 API
    public Mono<Long> registerWaitQueue(final String queue, final Long userId) {

        var unixTimestamp = Instant.now().getEpochSecond();

        return reactiveRedisTemplate.opsForZSet()
                .add(USER_QUEUE_WAIT_KEY.formatted(queue), userId.toString(), unixTimestamp)
                .filter(i -> i)
                .switchIfEmpty(Mono.error(ErrorCode.QUEUE_ALREADY_REGISTERED_USER.build()))
                .flatMap(i ->
                        reactiveRedisTemplate.opsForZSet().rank(USER_QUEUE_WAIT_KEY.formatted(queue), userId.toString()))
                .map(i -> i >= 0 ? i+1: i);
    }

    // 2. 진입을 허용
    // 진입을 허용하는 단계
    // 2-1. wait queue 사용자를 제거
    // 2-2. proceed queue 사용자를 추가
    public Mono<Long> allowUser(final String queue, final Long count) {
        return reactiveRedisTemplate.opsForZSet()
                .popMin(USER_QUEUE_WAIT_KEY.formatted(queue), count)
                .flatMap(member -> reactiveRedisTemplate.opsForZSet()
                        .add(USER_QUEUE_PROCEED_KEY.formatted(queue), member.getValue(), Instant.now().getEpochSecond()))
                .count();
    }

    // 3. 진입이 가능한 상태인지 조회
    public Mono<Boolean> isAllowed(final String queue, final Long userId) {
        return reactiveRedisTemplate.opsForZSet().rank(USER_QUEUE_PROCEED_KEY.formatted(queue), userId.toString())
                .defaultIfEmpty(-1L)
                .map(rank -> rank >= 0);
    }

    public Mono<Boolean> isAllowedByToken(final String queue, final Long userId, final String token) {
        return this.generateToken(queue, userId)
                .filter(gen -> gen.equalsIgnoreCase(token))
                .map(i -> true)
                .defaultIfEmpty(false);
    }

    // 4. 대기번호를 화면으로 전달
    public Mono<Long> getRank(final String queue, final Long userId) {
        return reactiveRedisTemplate.opsForZSet().rank(USER_QUEUE_WAIT_KEY.formatted(queue), userId.toString())
                .defaultIfEmpty(-1L)
                .map(rank -> rank >= 0 ? rank + 1: rank);
    }

    public Mono<String> generateToken(final String queue, final Long userId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            var input = "user-queue-%s-%d".formatted(queue, userId);
            byte[] encodedhash = digest.digest(input.getBytes(StandardCharsets.UTF_8));

            StringBuilder hexString = new StringBuilder();
            for (byte aByte : encodedhash) {
                hexString.append(String.format("%02x", aByte));
            }
            return Mono.just(hexString.toString());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    // 5. 대기열 스케쥴러
    @Scheduled(initialDelay = 5000, fixedDelay = 10000)
    public void scheduleAllowUser() {
        if (!scheduling) {
            log.info("passed scheduling...");
            return;
        }
        log.info("called scheduling...");

        var maxAllowUserCount = 100L;
        reactiveRedisTemplate.scan(ScanOptions.scanOptions()
                .match(USER_QUEUE_WAIT_KEY_FOR_SCAN)
                .count(100)
                .build())
                .map(key -> key.split(":")[2])
                .flatMap(queue -> allowUser(queue, maxAllowUserCount).map(allowed -> Tuples.of(queue, allowed)))
                .doOnNext(tuple -> log.info("Tried %d and allowed %d membmers of %s queue".formatted(maxAllowUserCount, tuple.getT2(), tuple.getT1())))
                .subscribe();

    }
}
