package cn.mrusg.jnbank.callcenter.rfservice.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class PerformanceMonitorService {
    
    private final AtomicLong totalBytesProcessed = new AtomicLong(0);
    private final AtomicInteger totalFilesProcessed = new AtomicInteger(0);
    private final AtomicLong lastResetTime = new AtomicLong(System.currentTimeMillis());
    
    /**
     * 记录文件处理信息
     */
    public void recordFileProcessed(long fileSize) {
        totalBytesProcessed.addAndGet(fileSize);
        totalFilesProcessed.incrementAndGet();
    }
    
    /**
     * 计算单个文件的处理速度
     */
    public String calculateSingleFileSpeed(long fileSize, long durationMillis) {
        double speedMBPerSecond = (fileSize / 1024.0 / 1024.0) / (durationMillis / 1000.0);
        return String.format("%.2f MB/s", speedMBPerSecond);
    }
    
    /**
     * 每分钟输出性能统计
     */
    @Scheduled(fixedRate = 60000) // 每分钟执行一次
    public void reportPerformance() {
        long currentTime = System.currentTimeMillis();
        long duration = currentTime - lastResetTime.get();
        
        // 获取当前统计数据
        int files = totalFilesProcessed.get();
        long bytes = totalBytesProcessed.get();
        
        // 计算速率
        double filesPerMinute = (files * 60000.0) / duration;
        double mbPerMinute = (bytes / 1024.0 / 1024.0) * (60000.0 / duration);
        
        // 输出统计信息
        log.info("归档性能统计 - 文件处理速度: {} 个/分钟, 数据处理速度: {} MB/分钟", 
                String.format("%.2f", filesPerMinute), 
                String.format("%.2f", mbPerMinute));
        
        // 重置计数器
        totalFilesProcessed.set(0);
        totalBytesProcessed.set(0);
        lastResetTime.set(currentTime);
    }
} 