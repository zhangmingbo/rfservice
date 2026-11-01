package cn.mrusg.jnbank.callcenter.rfservice.service;

import cn.hutool.core.date.DateUtil;
import cn.mrusg.jnbank.callcenter.rfservice.util.DateUtilU;
import com.alibaba.fastjson.JSONObject;
import com.sunyard.client.SunEcmClientApi;
import com.sunyard.client.bean.*;
import com.sunyard.client.impl.SunEcmClientSocketApiImpl;
import com.sunyard.ecm.server.bean.HeightQuery;
import com.sunyard.util.OptionKey;
import com.sunyard.util.TransOptionKey;
import com.sunyard.ws.utils.XMLUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.File;
import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

@Slf4j
@Service
public class SUNECMService {
    //查询用
    private final String groupName = "";
    @Value(value = "${sunecm.modelCode}")
    String modelCode;
    @Value(value = "${sunecm.filePartName}")
    String filePartName;
    @Value(value = "${sunecm.userName}")
    String userName = "tfcc";
    @Value(value = "${sunecm.password}")
    String password = "tfcc376";
    @Value(value = "${sunecm.ip}")
    String ip;
    @Value(value = "${sunecm.socketPort}")
    int socketPort;
    @Value(value = "${sunecm.httpPort}")
    int httpPort;
    @Value(value = "${sunecm.fms}")
    private String fms;
    @Value(value = "${sunecm.dmsName}")
    private String dmsName;

    @Autowired
    private PerformanceMonitorService performanceMonitor;

    // 每个线程独立的Socket连接，解决多线程并发访问的线程安全问题
    private final ThreadLocal<SunEcmClientApi> clientApiLocal = new ThreadLocal<>();
    // 记录每个线程的上次login时间
    private final ConcurrentHashMap<String, Long> threadLoginTimeMap = new ConcurrentHashMap<>();
    private static final long LOGIN_INTERVAL_MS = 5 * 60 * 1000; // 5分钟重新login一次

    @PostConstruct
    public void init() {
        log.info("SUNECM连接池模式 - 每个线程独立连接服务器[{}:{}]", ip, socketPort);
        // 初始化主线程连接
        getClientApi();
    }

    @PreDestroy
    public void destroy() {
        try {
            SunEcmClientApi api = clientApiLocal.get();
            if (api != null) {
                api.logout(userName);
                clientApiLocal.remove();
                log.info("SUNECM连接已关闭");
            }
        } catch (Exception e) {
            log.error("关闭SUNECM连接异常", e);
        }
    }

    /**
     * 获取当前线程的clientApi连接，若不存在则新建
     */
    private SunEcmClientApi getClientApi() {
        SunEcmClientApi api = clientApiLocal.get();
        if (api == null) {
            api = new SunEcmClientSocketApiImpl(ip, socketPort);
            clientApiLocal.set(api);
            log.info("线程[{}]新建SUNECM连接 - 服务器[{}:{}]", Thread.currentThread().getName(), ip, socketPort);
        }
        return api;
    }

    /**
     * 确保当前线程的connection已login，复用连接，避免每次上传都重新认证
     */
    private void ensureLogin() {
        String threadName = Thread.currentThread().getName();
        long now = System.currentTimeMillis();
        Long lastLogin = threadLoginTimeMap.get(threadName);
        if (lastLogin != null && (now - lastLogin) < LOGIN_INTERVAL_MS) {
            return; // 已login且未过期，跳过
        }
        try {
            SunEcmClientApi api = getClientApi();
            api.login(userName, password);
            threadLoginTimeMap.put(threadName, now);
            log.info("线程[{}]SUNECM登录成功 - 用户[{}] 服务器[{}:{}]", threadName, userName, ip, socketPort);
        } catch (Exception e) {
            log.error("线程[{}]SUNECM登录失败 - 服务器[{}:{}]", threadName, ip, socketPort, e);
        }
    }

    public static void main(String[] args) {
        SUNECMService sunecmService = new SUNECMService();
        sunecmService.login();

//        sunecmService.testQuery();
        sunecmService.logout();
    }

    // 推送录音文件
    public String push(JSONObject data) {
        log.info("向统一内容平台SUNECM推送录音文件 - 录音ID[{}] 通话ID[{}]", data.getString("RECORDID"), data.getString("CONNID"));
        // 推送逻辑
        // 返回批次号
        String batchNo = ECMupload(data);
        return batchNo;

    }

    public void login() {
        ensureLogin();
    }

    public boolean logout() {
        try {
            SunEcmClientApi api = getClientApi();
            String resultMsg = api.logout(userName);
            clientApiLocal.remove();
            log.debug("登出返回信息: {}", resultMsg);
            return true;
        } catch (Exception e) {
            log.error("SUNECM登出异常", e);
            return false;
        }
    }

    public String ECMupload(JSONObject data) {
        // 记录开始时间
        long startTime = System.currentTimeMillis();
        
        String filePath = data.getString("RECORDFILEPATH");
        File file = new File(filePath);
        String fileName = file.getName();
        long fileSize = file.length();
        
        log.info("开始上传录音文件 - 文件[{}] 大小[{}MB] 通话ID[{}] 录音ID[{}] 开始时间[{}]", 
                fileName, 
                String.format("%.2f", fileSize / 1024.0 / 1024.0),
                data.getString("CONNID"),
                data.getString("RECORDID"),
                DateUtil.format(new Date(startTime), "yyyy-MM-dd HH:mm:ss.SSS"));
        
        // 诊断日志：文件是否存在
        log.info("[诊断] 录音文件路径: {}, 文件是否存在: {}, 文件大小: {} bytes", filePath, file.exists(), fileSize);
        
        // 从data中解析录音信息
        //connid 通话CONNID VARCHAR2(20)
        //recordid 通话录音ID VARCHAR2(32)
        //ani 主叫号码 VARCHAR2(20)
        //recdatetime 录音时间 YYYYMMDD varchar8
        //dn 分机号 VARCHAR2(20)
        //agentid 坐席工号 VARCHAR2(10)
        //RECORDFILEPATH 录音文件路径 VARCHAR2(200)


        // 以下为上传逻辑
        int AMOUNT = 1;// 上传文件数目
        //将录音文件添加到数组中
        File[] arry = new File[1];
        arry[0] = new File(filePath);


        // 使用当前线程独立的clientApi连接，线程安全
        ensureLogin();
        SunEcmClientApi api = getClientApi();
        ClientBatchBean batchBean = new ClientBatchBean();
        batchBean.setUser(userName);
        batchBean.setPassWord(password);
        batchBean.setBreakPoint(false);// 是否作为断点续传上传
        batchBean.setModelCode(modelCode);
        batchBean.setOwnMD5(false);// 是否为批次下的文件添加MD5码

        //---------------设置索引对象------------------
        ClientBatchIndexBean batchIndexBean = new ClientBatchIndexBean();
        batchIndexBean.setAmount(String.valueOf(AMOUNT));// 设置上传的批次文件数量（用于校验，与数据库无关）
        batchIndexBean.setVersion("1");// 设置版本号
//		batchIndexBean.setContentID("20120620_6_146_1D60A0A0-F7EC-2AB1-5023-192CF3A50F6F-1");//可以自定义批次ID，若不设置则由ECM自动生成
        //以下3行：索引自定义属性
        batchIndexBean.addCustomMap("BUSI_START_DATE", DateUtilU.getYYYYMMDD());
        batchIndexBean.addCustomMap("AMOUNT", "1");// 设置该批次影像总数量（存入数据库）
        //生成影像流水号 系统编号0376+日期+CONNID
        batchIndexBean.addCustomMap("BUSI_SERIAL_NO", "0376" + DateUtilU.getYYYYMMDD() + data.getString("CONNID"));
        // 2024年2月22日14:56:38 补充录音自定义属性
        // 遍历data中所有的字段，检查是否为空，为空则重新赋值为pass
        for (String key : data.keySet()) {
            if (data.getString(key) == null || data.getString(key).equals("")) {
                data.put(key, "pass");
            }
        }


        batchIndexBean.addCustomMap("CONNID", data.getString("CONNID"));
        // RECDATETIME 转换为YYYYMMDD
        Date date = DateUtil.parseDate(data.getString("RECDATETIME"));
        batchIndexBean.addCustomMap("RECDATETIME", DateUtil.format(date, "yyyyMMdd"));
        log.debug("RECDATETIME转换: {} -> {}", data.getString("RECDATETIME"), DateUtil.format(date, "yyyyMMdd"));
        batchIndexBean.addCustomMap("ANI", data.getString("ANI"));
        batchIndexBean.addCustomMap("DN", data.getString("recordDN"));
        batchIndexBean.addCustomMap("AGENTID", data.getString("AGENTID"));
        batchIndexBean.addCustomMap("RECORDID", data.getString("RECORDID"));

        //---------------设置文档部件信息----------------
        ClientBatchFileBean batchFileBean = new ClientBatchFileBean();
        batchFileBean.setFilePartName(filePartName);

        //---------------添加文件---------------------
        String[] filename = arry[0].getName().split("\\.");
        String format = filename.length > 1 ? filename[filename.length - 1] : "";
        log.info("[诊断] 文件名: {}, 解析格式: {}, fms配置: {}, 格式匹配结果: {}", 
                arry[0].getName(), format, fms, fms.indexOf(format) > -1);
        if (fms.indexOf(format) > -1) {// 如果是指定格式的文件，则加入文档部件
            String nowFile = filePath;
            ClientFileBean bean = new ClientFileBean();
            bean.setFileFormat(format);// 设置文件后缀
            bean.setFilesize(arry[0].length() + "");// 注意：存放原图的大小
            bean.setFileName(nowFile);//大小图合并后的路径
            //以下3行：文件自定义属性
            bean.addOtherAtt("BUSI_FILE_PAGENUM", 0 + 1 + "");
            bean.addOtherAtt("BUSI_FILE_TYPE", "BUSI_FILE_TYPE");
            bean.addOtherAtt("BUSI_FILE_SCANUSER", "96005");


            batchFileBean.addFile(bean);
            log.info("[诊断] 文件已加入批次 - 格式[{}] 大小[{}] 路径[{}]", format, arry[0].length(), nowFile);
        } else {
            log.error("[诊断] 文件格式不匹配，文件未加入批次! 格式[{}] 不在fms[{}]中", format, fms);
        }
        //-------------将文档索引信息和文档部件信息与批次信息关联-----
        batchBean.addDocument_Object(batchFileBean);
        batchBean.setIndex_Object(batchIndexBean);
        log.info("[诊断] 上传前批次信息 - modelCode[{}] dmsName[{}] user[{}] serialNo[{}]", 
                modelCode, dmsName, userName, batchIndexBean.getCustomMap().get("BUSI_SERIAL_NO"));
        String massage = "";
        try {
            log.info("[诊断] 开始调用api.upload()...");
            massage = api.upload(batchBean, dmsName);//调用上传api接口
            log.info("[诊断] api.upload()返回: {}", massage);
            
            // 记录结束时间并计算性能
            long endTime = System.currentTimeMillis();
            long duration = endTime - startTime;
            String speed = performanceMonitor.calculateSingleFileSpeed(fileSize, duration);
            
            if (massage.startsWith("SUCCESS")) {
                log.info("文件上传成功 - 文件[{}] 大小[{}MB] 耗时[{}秒] 速度[{}]", 
                        fileName,
                        String.format("%.2f", fileSize / 1024.0 / 1024.0),
                        String.format("%.2f", duration / 1000.0),
                        speed);
                        
                // 记录性能统计
                performanceMonitor.recordFileProcessed(fileSize);
            } else {
                log.error("文件上传失败 - 文件[{}] 错误信息[{}]", fileName, massage);
            }
        } catch (Exception e) {
            log.error("文件上传异常 - 文件[{}]", fileName, e);
        }
        return massage;
    }


    // 检查批次号是否已经存在
    public JSONObject checkBusiSerialNo(String BUSI_SERIAL_NO) {
        JSONObject retrunData = new JSONObject();

        try {
            // 使用当前线程独立的clientApi连接，线程安全
            SunEcmClientApi api = getClientApi();
            //--------以下为高级查询部分--------
            ClientHeightQuery heightQuery1 = new ClientHeightQuery();
            // 必要信息,设置登录名+登录密码
            heightQuery1.setUserName(userName);
            heightQuery1.setPassWord(password);
            // 必要信息,设置每页显示最大的行数
            heightQuery1.setLimit(5);
            // 必要信息,设置显示的页数
            heightQuery1.setPage(1);
            // 必要信息,设置内容模型名
            heightQuery1.setModelCode(modelCode);
            // 必要信息,设置影像流水号（非批次号）
            heightQuery1.addCustomAtt("BUSI_SERIAL_NO", BUSI_SERIAL_NO);
            String resultMsg;
            try {
                resultMsg = api.heightQuery(heightQuery1, groupName);
                String batchStr = resultMsg.split(TransOptionKey.SPLITSYM)[1];
                HeightQuery h1 = XMLUtil.xml2Bean(XMLUtil.removeHeadRoot(batchStr), HeightQuery.class);
                if (h1.getIndexBeans() != null) {
                    // 说明有批次
                    String contentIDQuery = "";
                    String amountQuery = "";
                    String busiStartDateQuery = "";
                    contentIDQuery = h1.getIndexBeans().get(0).getContentID();
                    amountQuery = h1.getIndexBeans().get(0).getCustomMap().get("AMOUNT");
                    busiStartDateQuery = h1.getIndexBeans().get(0).getCustomMap().get("BUSI_START_DATE");
                    retrunData.put("exist", true);
                    retrunData.put("contentID", contentIDQuery);
                    retrunData.put("amount", amountQuery);
                    retrunData.put("busiStartDate", busiStartDateQuery);
                    
                    // 精简日志输出，只显示关键信息
                    log.info("批次查询结果 - 批次号[{}] 内容ID[{}] 文件数量[{}] 业务日期[{}]", 
                            BUSI_SERIAL_NO, 
                            contentIDQuery,
                            amountQuery,
                            busiStartDateQuery);
                } else {
                    retrunData.put("exist", false);
                    log.info("批次查询结果 - 批次号[{}] 不存在", BUSI_SERIAL_NO);
                }
            } catch (Exception e) {
                retrunData.put("exist", false);
                log.error("批次查询异常 - 批次号[{}]", BUSI_SERIAL_NO, e);
            }
        } catch (Exception e) {
            retrunData.put("exist", false);
            log.error("批次查询异常 - 批次号[{}]", BUSI_SERIAL_NO, e);
        }
        return retrunData;
    }


    public String appendFile(JSONObject recordData, JSONObject batchData) {
        String filePath = recordData.getString("RECORDFILEPATH");
        log.info("追加文件到批次 - 文件路径[{}]", filePath);
        // 以下为上传逻辑
        int AMOUNT = 1;// 上传文件数目
        //将录音文件添加到数组中
        File[] arry = new File[1];
        arry[0] = new File(filePath);


        // 使用当前线程独立的clientApi连接，线程安全
        ensureLogin();
        SunEcmClientApi api = getClientApi();
        ClientBatchBean batchBean = new ClientBatchBean();
        batchBean.setUser(userName);
        batchBean.setPassWord(password);
        batchBean.setBreakPoint(false);// 是否作为断点续传上传
        batchBean.setModelCode(modelCode);
        batchBean.setOwnMD5(false);// 是否为批次下的文件添加MD5码

        // 设置要更新的影像批次号
        batchBean.getIndex_Object().setContentID(batchData.getString("contentID"));// 设置影像批次ID
        batchBean.getIndex_Object().addCustomMap("BUSI_START_DATE", batchData.getString("busiStartDate"));// 设置业务开始时间
        batchBean.getIndex_Object().addCustomMap("AMOUNT", String.valueOf((Integer.parseInt(batchData.getString("amount")) + AMOUNT)));//设置该批次影像总数量 = 原Amount+本次上传影像件数量
        log.info("更新影像批次信息 - 内容ID[{}] 文件总数[{}]", batchBean.getIndex_Object().getContentID(), batchBean.getIndex_Object().getCustomMap().get("AMOUNT"));
        // 2024年2月22日14:56:38 补充录音自定义属性
        // 遍历data中所有的字段，检查是否为空，为空则重新赋值为pass
        for (String key : recordData.keySet()) {
            if (recordData.getString(key) == null || recordData.getString(key).equals("")) {
                recordData.put(key, "pass");
            }
        }

        //---------------设置文档部件信息----------------
        ClientBatchFileBean batchFileBean = new ClientBatchFileBean();
        batchFileBean.setFilePartName(filePartName);

        //---------------添加文件---------------------
        String[] filename = arry[0].getName().split("\\.");
        String format = filename[1];
        if (fms.indexOf(format) > -1) {// 如果是指定格式的文件，则加入文档部件
            String nowFile = filePath;
            ClientFileBean bean = new ClientFileBean();
            bean.setOptionType(OptionKey.U_ADD);
            bean.setFileFormat(format);// 设置文件后缀
            bean.setFilesize(arry[0].length() + "");// 注意：存放原图的大小
            bean.setFileName(nowFile);//大小图合并后的路径
            //以下3行：文件自定义属性
//            bean.addOtherAtt("BUSI_FILE_PAGENUM", 0 + 1 + "");
            bean.addOtherAtt("BUSI_FILE_TYPE", "BUSI_FILE_TYPE");
            bean.addOtherAtt("BUSI_FILE_SCANUSER", "96005");


            batchFileBean.addFile(bean);
        }
        //-------------将文档索引信息和文档部件信息与批次信息关联-----
        batchBean.addDocument_Object(batchFileBean);
        String massage = "";
        try {
            massage = api.update(batchBean, dmsName, true);//调用上传api接口
        } catch (Exception e) {
            log.error("追加文件到批次异常 - 文件[{}]", filePath, e);
        }
        if (massage.startsWith("SUCCESS")) {
            log.info("追加文件到批次成功 - 返回信息[{}]", massage);
        } else {
            log.error("追加文件到批次失败 - 返回信息[{}]", massage);
        }
        return massage;
    }

    @Scheduled(fixedRate = 300000) // 每5分钟执行一次
    public void monitorMemory() {
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        
        log.info("内存使用情况 - 已用: {}MB, 空闲: {}MB, 总计: {}MB",
                usedMemory / 1024 / 1024,
                freeMemory / 1024 / 1024,
                totalMemory / 1024 / 1024);
    }

}
