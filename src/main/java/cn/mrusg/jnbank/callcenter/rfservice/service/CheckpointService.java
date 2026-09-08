package cn.mrusg.jnbank.callcenter.rfservice.service;

import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;

@Slf4j
@Service
public class CheckpointService {
    
    @Value(value = "${recorderNode.info.name}")
    private String nodeName;
    
    @Value(value = "${recorderNode.info.ip}")
    private String nodeIp;
    
    private final String CHECKPOINT_KEY_PREFIX = "rfservice:checkpoint:";
    private final String PROCESSED_FILES_KEY_PREFIX = "rfservice:processed_files:";
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    @Autowired
    private RedisTemplate<String, String> redisTemplate;
    
    /**
     * 获取实例标识
     */
    private String getInstanceId() {
        return nodeName + ":" + nodeIp;
    }
    
    /**
     * 记录检查点
     */
    public void saveCheckpoint(LocalDateTime checkpoint) {
        String timestamp = checkpoint.format(formatter);
        String key = CHECKPOINT_KEY_PREFIX + getInstanceId();
        redisTemplate.opsForValue().set(key, timestamp);
        log.info("保存检查点 - 实例[{}] 时间[{}]", getInstanceId(), timestamp);
    }
    
    /**
     * 获取上次检查点时间
     */
    public LocalDateTime getLastCheckpoint() {
        String key = CHECKPOINT_KEY_PREFIX + getInstanceId();
        String timestamp = redisTemplate.opsForValue().get(key);
        if (timestamp == null) {
            return LocalDateTime.now().minusDays(1); // 如果没有检查点，默认检查最近一天的文件
        }
        return LocalDateTime.parse(timestamp, formatter);
    }
    
    /**
     * 记录已处理的文件
     */
    public void markFileAsProcessed(String filePath) {
        String key = PROCESSED_FILES_KEY_PREFIX + getInstanceId() + ":" + 
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        redisTemplate.opsForSet().add(key, filePath);
        // 设置过期时间（7天）
        redisTemplate.expire(key, java.time.Duration.ofDays(7));
    }
    
    /**
     * 检查文件是否已处理
     */
    public boolean isFileProcessed(String filePath) {
        String key = PROCESSED_FILES_KEY_PREFIX + getInstanceId() + ":" + 
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(key, filePath));
    }
    
    /**
     * 获取指定日期范围内的所有已处理文件
     */
    public Set<String> getProcessedFiles(LocalDateTime startTime, LocalDateTime endTime) {
        String startKey = PROCESSED_FILES_KEY_PREFIX + getInstanceId() + ":" + 
                         startTime.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String endKey = PROCESSED_FILES_KEY_PREFIX + getInstanceId() + ":" + 
                       endTime.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        return redisTemplate.opsForSet().union(startKey, endKey);
    }
    
    /**
     * 清理过期的检查点记录
     */
    public void cleanupOldCheckpoints() {
        try {
            String pattern = CHECKPOINT_KEY_PREFIX + "*";
            Set<String> keys = redisTemplate.keys(pattern);
            if (keys != null) {
                for (String key : keys) {
                    String timestamp = redisTemplate.opsForValue().get(key);
                    if (timestamp != null) {
                        LocalDateTime checkpointTime = LocalDateTime.parse(timestamp, formatter);
                        // 删除7天前的检查点
                        if (checkpointTime.isBefore(LocalDateTime.now().minusDays(7))) {
                            redisTemplate.delete(key);
                            log.info("清理过期检查点: {}", key);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("清理过期检查点时发生异常", e);
        }
    }
} 