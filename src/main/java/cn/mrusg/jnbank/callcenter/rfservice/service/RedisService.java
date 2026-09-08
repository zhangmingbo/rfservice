package cn.mrusg.jnbank.callcenter.rfservice.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.Set;
import java.util.concurrent.TimeUnit;


/**
 * redis
 *
 * @author usagi
 * @version V1.0
 * @date 2023年6月13日
 */
@Service
@Slf4j
public class RedisService {
    private final StringRedisTemplate redisTemplate;

    public RedisService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean exists(String key) {
        return this.redisTemplate.hasKey(key);
    }

    public Long getExpire(String key) {
        if (null == key) {
            log.warn("key error");
        }
        return redisTemplate.getExpire(key, TimeUnit.SECONDS);
    }


    public void set(String key, String value) {
        this.redisTemplate.opsForValue().set(key, value);
    }


//    public void setSet(String key, String value) {
//        this.redisTemplate.opsForSet().set(key, value);
//    }


    public String get(String key) {
        return this.redisTemplate.opsForValue().get(key);
    }

    public void del(String key) {
        if (this.exists(key)) {
            this.redisTemplate.delete(key);
        }

    }

    public void setAndExpire(String key, String value, long seconds) {
        this.redisTemplate.opsForValue().set(key, value);
        this.redisTemplate.expire(key, seconds, TimeUnit.SECONDS);
    }


    public Set<String> keys(String pattern) {
        return redisTemplate.keys("*" + pattern);
    }

    public void delKeys(String pattern) {
        Set<String> keys = redisTemplate.keys(pattern);
        if (!CollectionUtils.isEmpty(keys)) {
            this.redisTemplate.delete(keys);
        }
    }


}
