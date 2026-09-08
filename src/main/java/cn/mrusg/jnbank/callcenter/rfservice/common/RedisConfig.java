package cn.mrusg.jnbank.callcenter.rfservice.common;

import com.alibaba.fastjson.support.spring.FastJsonRedisSerializer;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Data
@Aspect
@Configuration
@Slf4j
@ConfigurationProperties(prefix = "spring.redis")
public class RedisConfig {


    @Autowired
    private RedisProperties redisProperties;

    private String password;

    @Before("cn.mrusg.jnbank.callcenter.rfservice.common.pointcut.CommonPointCut.getRedisConnectionPwd()")
    public void setRedisProperties() throws Exception {
        // 解密。
        String pwdStr = CryptoUtils.decrypt(password);
        log.info(pwdStr + "**--**");
        redisProperties.setPassword(pwdStr);
    }

    @Bean
    public RedisTemplate<Object, Object> redisTemplate(RedisConnectionFactory redisConnectionFactory) {

        RedisTemplate<Object, Object> template = new RedisTemplate();
        template.setConnectionFactory(redisConnectionFactory);
        //设置自定义的序列化方式（fastjson） ，不然key会出现 \xac\xed\x00\x05t\x00\tb
        template.setDefaultSerializer(new FastJsonRedisSerializer(Object.class));
        template.setKeySerializer(new StringRedisSerializer());
        return template;
    }

}
