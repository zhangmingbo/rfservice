package cn.mrusg.jnbank.callcenter.rfservice;

import cn.mrusg.jnbank.callcenter.rfservice.util.FilterFileConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;

@Slf4j
@EnableAsync
@SpringBootApplication
@EnableScheduling //开启定时任务


public class RfServiceApplication {

    @Autowired
    //RestTemplateBuilder
    private RestTemplateBuilder builder;
    private static FilterFileConfig filterFileConfig;

    @Value("${app.version:unknown}")
    private String appVersion;
    @Value("${app.build.time:unknown}")
    private String appBuildTime;
    @Value("${app.name:RFService}")
    private String appName;

    @PostConstruct
    public void logVersionInfo() {
        log.info("========================================");
        log.info("  {} 版本: {}", appName, appVersion);
        log.info("  构建时间: {}", appBuildTime);
        log.info("========================================");
    }

    @Autowired
    public void setFilterFileConfig(FilterFileConfig filterFileConfig) {

        this.filterFileConfig = filterFileConfig;
    }

    // 使用RestTemplateBuilder来实例化RestTemplate对象，spring默认已经注入了RestTemplateBuilder实例
    @Bean
    public RestTemplate restTemplate() {
        return builder.build();
    }

    public static void main(String[] args) {

        SpringApplication.run(RfServiceApplication.class, args);

        filterFileConfig.FileFilter();


    }

}
