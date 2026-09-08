package cn.mrusg.jnbank.callcenter.rfservice.service;

import cn.hutool.core.date.DateUtil;
import cn.mrusg.jnbank.callcenter.rfservice.util.DateUtilU;
import com.alibaba.fastjson.JSONObject;
import com.sunyard.client.SunEcmClientApi;
import com.sunyard.client.bean.*;
import com.sunyard.client.impl.SunEcmClientSocketApiImpl;
import com.sunyard.dmserver.bean.HeightQuery;
import com.sunyard.util.OptionKey;
import com.sunyard.util.TransOptionKey;
import com.sunyard.ws.utils.XMLUtil;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.File;
import java.util.Date;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

@Log4j2
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

    // 复用Socket连接，避免每次上传都新建TCP连接
    private volatile SunEcmClientApi clientApi;
    // 记录上次login时间，避免频繁login
    private volatile long lastLoginTime = 0;
    private static final long LOGIN_INTERVAL_MS = 5 * 60 * 1000; // 5分钟重新login一次

    @PostConstruct
    public void init() {
        // @Value注入完成后再初始化clientApi，修复之前ip/port为0的bug
        clientApi = new SunEcmClientSocketApiImpl(ip, socketPort);
        log.info("初始化SUNECM连接 - 服务器[{}:{}]", ip, socketPort);
        // 启动时先login一次
        ensureLogin();
    }

    @PreDestroy
    public void destroy() {
        try {
            if (clientApi != null) {
                clientApi.logout(userName);
                log.info("SUNECM连接已关闭");
            }
        } catch (Exception e) {
            log.error("关闭SUNECM连接异常", e);
        }
    }

    /**
     * 确保已login，复用连接，避免每次上传都重新认证
     */
    private void ensureLogin() {
        long now = System.currentTimeMillis();
        if (now - lastLoginTime < LOGIN_INTERVAL_MS) {
            return; // 已login且未过期，跳过
        }
        try {
            clientApi.login(userName, password);
            lastLoginTime = now;
            log.info("SUNECM登录成功 - 用户[{}] 服务器[{}:{}]", userName, ip, socketPort);
        } catch (Exception e) {
            log.error("SUNECM登录失败 - 服务器[{}:{}]", ip, socketPort, e);
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
        log.info("inner向统一内容平台SUNECM推送录音文件" + data);
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
            String resultMsg = clientApi.logout(userName);
            log.debug("#######登出返回的信息[" + resultMsg + "]#######");
            return true;
        } catch (Exception e) {
            e.printStackTrace();
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


        // 复用类级别的clientApi连接，避免每次上传都新建TCP连接
        ensureLogin();
        SunEcmClientApi api = clientApi;
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
//        log.warn("RECDATETIME: " + data.getString("RECDATETIME") + " -> " + DateUtil.format(date, "yyyyMMdd"));
        batchIndexBean.addCustomMap("ANI", data.getString("ANI"));
        batchIndexBean.addCustomMap("DN", data.getString("recordDN"));
        batchIndexBean.addCustomMap("AGENTID", data.getString("AGENTID"));
        batchIndexBean.addCustomMap("RECORDID", data.getString("RECORDID"));

        //---------------设置文档部件信息----------------
        ClientBatchFileBean batchFileBean = new ClientBatchFileBean();
        batchFileBean.setFilePartName(filePartName);

        //---------------添加文件---------------------
        String[] filename = arry[0].getName().split("\\.");
        String format = filename[1];
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
        }
        //-------------将文档索引信息和文档部件信息与批次信息关联-----
        batchBean.addDocument_Object(batchFileBean);
        batchBean.setIndex_Object(batchIndexBean);
        String massage = "";
        try {
            massage = api.upload(batchBean, dmsName);//调用上传api接口
            
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
            // 复用类级别的clientApi连接
            SunEcmClientApi api = clientApi;
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
                log.error("批次查询异常 - 批次号[{}] 错误信息[{}]", BUSI_SERIAL_NO, e.getMessage());
                e.printStackTrace();
            }
        } catch (Exception e) {
            retrunData.put("exist", false);
            log.error("批次查询异常 - 批次号[{}] 错误信息[{}]", BUSI_SERIAL_NO, e.getMessage());
            e.printStackTrace();
        }
        return retrunData;
    }


    public String appendFile(JSONObject recordData, JSONObject batchData) {
        String filePath = recordData.getString("RECORDFILEPATH");
        log.info("data中解析到的文件路径：" + filePath);
        // 以下为上传逻辑
        int AMOUNT = 1;// 上传文件数目
        //将录音文件添加到数组中
        File[] arry = new File[1];
        arry[0] = new File(filePath);


        // 复用类级别的clientApi连接
        ensureLogin();
        SunEcmClientApi api = clientApi;
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
        log.info("更新影像批次信息" + batchBean.getIndex_Object().getContentID() + " " + batchBean.getIndex_Object().getCustomMap().get("AMOUNT"));
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
            e.printStackTrace();
        }
        if (massage.startsWith("SUCCESS")) {
            log.debug("api成功上传批次返回的信息[" + massage + "]");
        } else {
            log.error("api失败上传批次返回的信息[" + massage + "]");
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
