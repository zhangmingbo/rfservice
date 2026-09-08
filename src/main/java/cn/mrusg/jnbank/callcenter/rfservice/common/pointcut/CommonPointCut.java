package cn.mrusg.jnbank.callcenter.rfservice.common.pointcut;

import org.aspectj.lang.annotation.Pointcut;

public class CommonPointCut {

    @Pointcut("execution(* org.springframework.boot.autoconfigure.data.redis.RedisProperties.getPassword())")
    public void getRedisConnectionPwd() {
    }
}
