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
        try {
            return this.redisTemplate.hasKey(key);
        } catch (Exception e) {
            log.error("Redis exists异常 - key[{}]", key, e);
            return false;
        }
    }

    public Long getExpire(String key) {
        if (null == key) {
            log.warn("key error");
        }
        return redisTemplate.getExpire(key, TimeUnit.SECONDS);
    }


    public void set(String key, String value) {
        try {
            this.redisTemplate.opsForValue().set(key, value);
        } catch (Exception e) {
            log.error("Redis set异常 - key[{}]", key, e);
        }
    }


//    public void setSet(String key, String value) {
//        this.redisTemplate.opsForSet().set(key, value);
//    }


    public String get(String key) {
        try {
            return this.redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.error("Redis get异常 - key[{}]", key, e);
            return null;
        }
    }

    public void del(String key) {
        try {
            if (this.exists(key)) {
                this.redisTemplate.delete(key);
            }
        } catch (Exception e) {
            log.error("Redis del异常 - key[{}]", key, e);
        }
    }

    public void setAndExpire(String key, String value, long seconds) {
        try {
            this.redisTemplate.opsForValue().set(key, value);
            this.redisTemplate.expire(key, seconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("Redis setAndExpire异常 - key[{}] seconds[{}]", key, seconds, e);
        }
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
