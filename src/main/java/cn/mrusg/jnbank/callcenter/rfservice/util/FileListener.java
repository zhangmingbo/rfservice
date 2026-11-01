package cn.mrusg.jnbank.callcenter.rfservice.util;

import cn.hutool.core.io.FileUtil;
import cn.mrusg.jnbank.callcenter.rfservice.service.CheckpointService;
import cn.mrusg.jnbank.callcenter.rfservice.service.ManagerService;
import cn.mrusg.jnbank.callcenter.rfservice.service.RedisService;
import cn.mrusg.jnbank.callcenter.rfservice.service.SUNECMService;
import com.alibaba.fastjson.JSONObject;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.monitor.FileAlterationListenerAdaptor;
import org.apache.commons.io.monitor.FileAlterationObserver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.*;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class FileListener extends FileAlterationListenerAdaptor {

    @Autowired
    RedisService redisService;
    @Autowired
    ManagerService managerService;
    @Autowired
    SUNECMService sunecmService;
    @Value(value = "${recorderNode.info.ip}")
    private String nodeIp;
    
    @Value(value = "${recorder.thread-pool.size:4}")
    private int threadPoolSize;

    @Autowired
    private CheckpointService checkpointService;

    // 使用ThreadPoolExecutor替代ExecutorService以便访问更多线程池信息
    private ThreadPoolExecutor executorService;
    
    // 使用AtomicInteger跟踪待处理的任务数量
    private final AtomicInteger pendingTaskCount = new AtomicInteger(0);
    
    // 用于跟踪每个线程正在处理的文件和已处理文件数量
    private final ConcurrentMap<String, String> threadCurrentFiles = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicLong> threadProcessedFiles = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        // 创建固定大小的线程池，使用ThreadPoolExecutor以便访问更多信息
        executorService = new ThreadPoolExecutor(
            threadPoolSize,
            threadPoolSize,
            0L, 
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(),
            new ThreadFactory() {
                private final AtomicInteger threadNumber = new AtomicInteger(1);
                
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "file-processor-" + threadNumber.getAndIncrement());
                    t.setDaemon(false);
                    return t;
                }
            }
        );
        
        log.info("初始化录音处理线程池，线程数：{}", threadPoolSize);
    }

    @PreDestroy
    public void destroy() {
        if (executorService != null) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 文件创建执行
     */
    @SneakyThrows
    @Override
    public void onFileCreate(File file) {
        log.info("[新建] {}", file.getAbsolutePath());
        
        if (!FileUtil.getSuffix(file).equals("json")) {
            return;
        }

        // 检查文件是否已处理
        if (checkpointService.isFileProcessed(file.getAbsolutePath())) {
            log.info("文件已处理，跳过: {}", file.getAbsolutePath());
            return;
        }

        // 增加待处理任务计数并记录日志
        int currentPendingTasks = pendingTaskCount.incrementAndGet();
        log.info("新增待处理任务，当前待处理任务数量: {}", currentPendingTasks);

        // 提交任务到线程池
        executorService.submit(() -> {
            String threadName = Thread.currentThread().getName();
            
            // 初始化线程计数器（如果不存在）
            threadProcessedFiles.putIfAbsent(threadName, new AtomicLong(0));
            
            try {
                // 记录线程当前正在处理的文件
                threadCurrentFiles.put(threadName, file.getName());
                
                log.info("线程[{}]开始处理录音文件: {}", threadName, file.getAbsolutePath());
                String recInfoJson = FileUtil.readString(file, Charset.defaultCharset());
                // 处理录音文件信息
                JSONObject data = McpJsonResolve.logic(recInfoJson);
                // 补充录音节点信息
                data.put("RECNODE", nodeIp);
                // 补充录音文件路径，录音文件路径为json文件路径去掉-htccmetadata.json
                String recPath = file.getAbsolutePath().replace("-htccmetadata.json", "");
                data.put("RECORDFILEPATH", recPath);
                
                // 向Redis推送录音文件信息
                try {
                    redisService.setAndExpire(data.getString("RECORDID"), nodeIp, 7 * 24 * 3600);
                    redisService.setAndExpire(data.getString("RECORDID") + "_INFO", data.toString(), 1 * 24 * 3600);
                    log.info("线程[{}]向Manager推送录音信息: {}", threadName, data.getString("RECORDID"));
                } catch (Exception e) {
                    log.error("线程[{}]Redis操作异常: {}", threadName, e.toString());
                }

                // 向统一内容平台SUNECM推送录音文件
                try {
                    String batchNo = sunecmService.push(data);
                    log.info("线程[{}]向SUNECM推送录音文件: {}, 批次号: {}", threadName, data.getString("RECORDID"), batchNo);
                    data.put("BATCHNO", batchNo);

                    // 检查返回结果，如果失败，尝试向原有批次内追加文件
                    if (batchNo.startsWith("FAIL")) {
                        log.info("线程[{}]尝试向批次内追加录音文件", threadName);
                        // 向批次内追加录音文件
                        appendFile(data);
                    }
                    
                    // 标记文件为已处理
                    checkpointService.markFileAsProcessed(file.getAbsolutePath());
                } catch (Exception e) {
                    log.error("线程[{}]推送录音文件到内容平台发生异常", threadName, e);
                }

                // 向Manager 推送录音信息
                managerService.message(data, "/transaction/mcprecord/add2");
                log.info("线程[{}]完成处理录音文件: {}", threadName, file.getAbsolutePath());
                
                // 增加线程处理文件计数
                threadProcessedFiles.get(threadName).incrementAndGet();
                
            } catch (Exception e) {
                log.error("线程[{}]处理录音文件异常: {}", threadName, file.getAbsolutePath(), e);
            } finally {
                // 清除线程当前处理的文件记录
                threadCurrentFiles.remove(threadName);
                
                // 任务完成，减少待处理任务计数并记录日志
                int remainingTasks = pendingTaskCount.decrementAndGet();
                log.info("完成一个任务处理，剩余待处理任务数量: {}", remainingTasks);
            }
        });
    }

    /**
     * 获取当前待处理任务数量
     */
    public int getPendingTaskCount() {
        return pendingTaskCount.get();
    }
    
    /**
     * 定时任务，每60秒打印线程池状态和每个线程的处理情况
     */
    @Scheduled(fixedRate = 60000)
    public void printThreadPoolStatus() {
        try {
            int activeCount = executorService.getActiveCount();
            int poolSize = executorService.getPoolSize();
            long taskCount = executorService.getTaskCount();
            long completedTaskCount = executorService.getCompletedTaskCount();
            int queueSize = executorService.getQueue().size();
            
            log.info("线程池状态 - 活动线程: {}/{}, 总任务: {}, 已完成: {}, 队列中: {}, 待处理: {}",
                    activeCount, poolSize, taskCount, completedTaskCount, queueSize, pendingTaskCount.get());
            
            // 打印每个线程的处理情况
            if (threadProcessedFiles.isEmpty() || activeCount == 0) {
                log.info("线程处理详情: 当前没有活动的处理线程");
            } else {
                // 使用单行日志格式避免空行
                log.info("线程处理详情:");
                for (Map.Entry<String, AtomicLong> entry : threadProcessedFiles.entrySet()) {
                    String threadName = entry.getKey();
                    long processedFiles = entry.getValue().get();
                    String currentFile = threadCurrentFiles.getOrDefault(threadName, "空闲");
                    
                    log.info("  - 线程[{}]: 已处理文件数: {}, 当前处理: {}", 
                            threadName, processedFiles, currentFile);
                }
            }
            
        } catch (Exception e) {
            log.error("打印线程池状态时发生异常", e);
        }
    }

    /**
     * 向批次内追加录音文件
     */
    public String appendFile(JSONObject data) {
        String threadName = Thread.currentThread().getName();
        try {
            // 1. 查询是否已经有这个批次，有则追加；没有直接跳过，其他原因报错
            String BUSI_SERIAL_NO = "0376" + DateUtilU.getYYYYMMDD() + data.getString("CONNID");

            String amountQuery = "";
            log.info("线程[{}]检查BusiSerialNo是否存在: {}", threadName, BUSI_SERIAL_NO);
            JSONObject returnData = sunecmService.checkBusiSerialNo(BUSI_SERIAL_NO);

            // 从returnMessage中解析 文件数量&批次号
            // 判断retrunData.exist 是否为true
            if (returnData.getBoolean("exist")) {
                // 2. 追加录音文件
                log.info("线程[{}]尝试向批次内追加录音", threadName);
                sunecmService.appendFile(data, returnData);
                return "success";
            }

            return "";
        } catch (Exception e) {
            log.error("线程[{}]追加录音文件异常", threadName, e);
            return "";
        }
    }

    /**
     * 文件创建修改
     */
    @Override
    public void onFileChange(File file) {
        log.info("[修改] {}", file.getAbsolutePath());
        //读取文件内容
        try (BufferedReader buf = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"))) {
            log.debug("文件修改内容: {}", buf.readLine());
        } catch (IOException e) {
            log.error("读取修改文件内容异常", e);
        }
    }

    /**
     * 文件删除
     */
    @Override
    public void onFileDelete(File file) {
        log.info("[删除] {}", file.getAbsolutePath());
    }

    /**
     * 目录创建
     */
    @Override
    public void onDirectoryCreate(File directory) {
        log.info("[新建目录] {}", directory.getAbsolutePath());
    }

    /**
     * 目录修改
     */
    @Override
    public void onDirectoryChange(File directory) {
        log.info("[修改目录] {}", directory.getAbsolutePath());
    }

    /**
     * 目录删除
     */
    @Override
    public void onDirectoryDelete(File directory) {
        log.info("[删除目录] {}", directory.getAbsolutePath());
    }

    @Override
    public void onStart(FileAlterationObserver observer) {
        // TODO Auto-generated method stub
        super.onStart(observer);
    }

    @Override
    public void onStop(FileAlterationObserver observer) {
        // TODO Auto-generated method stub
        super.onStop(observer);
    }
}