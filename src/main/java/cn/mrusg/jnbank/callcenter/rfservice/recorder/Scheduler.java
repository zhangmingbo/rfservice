package cn.mrusg.jnbank.callcenter.rfservice.recorder;

import cn.hutool.core.io.FileUtil;
import cn.mrusg.jnbank.callcenter.rfservice.service.CheckpointService;
import cn.mrusg.jnbank.callcenter.rfservice.service.RedisService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;

/**
 * 固定定时任务
 * 1. 定时迁移录音文件
 * 2. 定时清理过期录音文件（超过7天）
 * 3. 定时清理过期检查点
 */
@Component
@Slf4j
public class Scheduler {

    @Autowired
    RedisService redisService;
    
    @Autowired
    CheckpointService checkpointService;

    @Value(value = "${recorderNode.info.ip}")
    private String ip;

    @Scheduled(cron = "0 0/5 *  * * ? ")   //scheduling task per 5 minute
    public void moveRecordFile() {
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"); //设置日期格式
        log.info("定时任务获取redis信息，处理录音迁移");
        // 从Redis取任务
        // Redis键值
        redisService.get(ip + "task");
    }
    
    @Scheduled(cron = "0 0 2 * * ? ")   // 每天凌晨2点执行
    public void cleanupCheckpoints() {
        log.info("开始清理过期检查点");
        checkpointService.cleanupOldCheckpoints();
        log.info("完成清理过期检查点");
    }

//    @Scheduled(cron = "0 1 *  * 1 ? ")   //scheduling task per 5 minute
//    public void recordFileClean() {
//        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"); //设置日期格式
//        log.info("定时清理超过一个周的录音");
//        FileUtil
//
//
//    }
}