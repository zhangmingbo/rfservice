package cn.mrusg.jnbank.callcenter.rfservice.util;

import cn.hutool.core.io.FileUtil;
import cn.mrusg.jnbank.callcenter.rfservice.service.CheckpointService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.filefilter.FileFilterUtils;
import org.apache.commons.io.filefilter.HiddenFileFilter;
import org.apache.commons.io.filefilter.IOFileFilter;
import org.apache.commons.io.monitor.FileAlterationMonitor;
import org.apache.commons.io.monitor.FileAlterationObserver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

@Configuration
@Slf4j
public class FilterFileConfig {
    @Value(value = "${recorderNode.mcp.path}")
    private String monitorConfigDir;
    
    @Value(value = "${recorder.file-monitor.interval-seconds:10}")
    private long monitorIntervalSeconds;
    
    @Autowired
    private FileListener fileListener;
    
    @Autowired
    private CheckpointService checkpointService;

    public void FileFilter() {
        String rootDir = monitorConfigDir;
        // 轮询间隔（可配置，默认10秒）
        long interval = TimeUnit.SECONDS.toMillis(monitorIntervalSeconds);
        log.info("文件监控轮询间隔设置为: {} 秒", interval / 1000);
        
        // 创建过滤器
        IOFileFilter directories = FileFilterUtils.and(
                FileFilterUtils.directoryFileFilter(),
                HiddenFileFilter.VISIBLE);
        IOFileFilter files = FileFilterUtils.and(
                FileFilterUtils.fileFileFilter(),
                FileFilterUtils.suffixFileFilter(".json"));
        IOFileFilter filter = FileFilterUtils.or(directories, files);
        
        // 使用过滤器
        FileAlterationObserver observer = new FileAlterationObserver(new File(rootDir), filter);
        observer.addListener(fileListener);
        
        // 创建文件变化监听器
        FileAlterationMonitor monitor = new FileAlterationMonitor(interval, observer);
        
        // 启动前检查上次检查点
        checkAndProcessMissedFiles();
        
        // 开始监控
        try {
            monitor.start();
            log.info("开始监听目录: {}", rootDir);
        } catch (Exception e) {
            log.error("启动文件监控异常", e);
        }
    }
    
    /**
     * 检查并处理上次检查点之后的新文件
     */
    private void checkAndProcessMissedFiles() {
        try {
            LocalDateTime lastCheckpoint = checkpointService.getLastCheckpoint();
            if (lastCheckpoint == null) {
                log.info("未找到历史检查点，跳过文件检查，直接开始监听目录: {}", monitorConfigDir);
                return;
            }
            
            log.info("开始检查上次检查点[{}]之后的文件", lastCheckpoint);
            File monitorDir = new File(monitorConfigDir);
            if (!monitorDir.exists() || !monitorDir.isDirectory()) {
                log.error("监控目录不存在或不是目录: {}", monitorConfigDir);
                return;
            }
            
            // 查找需要处理的文件
            int totalFilesCount = 0;
            int pendingFilesCount = 0;
            File[] files = monitorDir.listFiles((dir, name) -> name.endsWith(".json"));
            
            if (files != null) {
                totalFilesCount = files.length;
                // 先统计需要处理的文件数量
                for (File file : files) {
                    LocalDateTime fileModifiedTime = LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(file.lastModified()),
                        ZoneId.systemDefault()
                    );
                    if (fileModifiedTime.isAfter(lastCheckpoint)) {
                        if (!checkpointService.isFileProcessed(file.getAbsolutePath())) {
                            pendingFilesCount++;
                        }
                    }
                }
                
                log.info("检测到需要处理的文件总数: {}", pendingFilesCount);
                
                // 处理文件
                int processedCount = 0;
                for (File file : files) {
                    LocalDateTime fileModifiedTime = LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(file.lastModified()),
                        ZoneId.systemDefault()
                    );
                    if (fileModifiedTime.isAfter(lastCheckpoint)) {
                        if (!checkpointService.isFileProcessed(file.getAbsolutePath())) {
                            log.info("发现未处理的文件: {}, 当前已处理/总待处理: {}/{}", 
                                    file.getName(), processedCount, pendingFilesCount);
                            fileListener.onFileCreate(file);
                            processedCount++;
                        }
                    }
                }
            }
            
            LocalDateTime newCheckpoint = LocalDateTime.now();
            checkpointService.saveCheckpoint(newCheckpoint);
            
            // 输出处理结果和剩余待处理任务数
            log.info("文件扫描完成 - 检查到文件总数: {}, 处理未上传文件数: {}, 剩余待处理任务: {}, 新检查点设置为: {}", 
                    totalFilesCount,
                    pendingFilesCount,
                    fileListener.getPendingTaskCount(),
                    newCheckpoint.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            
        } catch (Exception e) {
            log.error("检查未处理文件时发生错误", e);
        }
    }
}