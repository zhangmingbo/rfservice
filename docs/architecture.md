# RFService 服务架构与业务流程文档

## 1. 项目概述

**RFService**（Recording File Service）是呼叫中心录音文件归档处理服务，负责从 Genesys CTI 平台接收录音文件，处理后上传至 SUNECM 统一内容管理平台，并回调 Manager 管理服务完成录音归档闭环

### 1.1 核心职责

- 监控录音目录，发现新录音元数据文件
- 解析 MCP 元数据 JSON 文件，提取录音关键信息
- 多线程并发上传录音文件至 SUNECM 统一内容平台
- 向 Manager 服务推送录音处理结果
- 通过 Redis 实现检查点机制和防重复处理
- 提供录音文件下载接口

### 1.2 技术栈

| 分类 | 技术 | 版本 |
|------|------|------|
| 开发语言 | Java | 1.8 |
| 核心框架 | Spring Boot | 2.7.18 |
| Web服务 | Spring Web + Tomcat | 9.0.106 |
| 数据缓存 | Spring Data Redis | - |
| JSON处理 | Alibaba FastJSON | 1.2.83 |
| 工具库 | Hutool | 5.8.16 |
| 文件监控 | Apache Commons IO | 2.19.0 |
| 网络通信 | Netty | 4.1.113.Final |
| 内容平台SDK | SunECM Client | 3.4.0 |
| 配置加密 | Jasypt | 1.9.3 |
| 日志框架 | Log4j2 + SLF4J | 2.24.3 |

---

## 2. 系统架构

### 2.1 整体架构图

```
┌─────────────────────────────────────────────────────────────────┐
│                        Genesys CTI 平台                         │
│                    (gcti/mcp 媒体控制平台)                        │
└──────────────────────────┬──────────────────────────────────────┘
                           │ 写入录音文件 + 元数据
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│                    录音文件存储目录                               │
│          D:\record\ (生产: /home/genesys/gcti/mcp/cache/)       │
│  ├── xxx.wav (音频文件)                                          │
│  └── xxx-htccmetadata.json (元数据文件)                          │
└──────────────────────────┬──────────────────────────────────────┘
                           │ 文件监控（每10秒轮询）
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│                       RFService 服务                             │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │                   FilterFileConfig                        │  │
│  │            (文件监控配置 + 检查点恢复)                       │  │
│  └──────────────────────┬───────────────────────────────────┘  │
│                         │                                       │
│  ┌──────────────────────▼───────────────────────────────────┐  │
│  │                     FileListener                          │  │
│  │           (文件事件监听 + 线程池并发处理)                     │  │
│  │  ┌─────────────────────────────────────────────────────┐ │  │
│  │  │         ThreadPoolExecutor (8线程)                    │ │  │
│  │  │  ┌─────────┐ ┌─────────┐ ┌─────────┐              │ │  │
│  │  │  │ Thread-1│ │ Thread-2│ │ Thread-N│              │ │  │
│  │  │  └────┬────┘ └────┬────┘ └────┬────┘              │ │  │
│  │  └───────┼───────────┼───────────┼────────────────────┘ │  │
│  └──────────┼───────────┼───────────┼──────────────────────┘  │
│             │           │           │                           │
│  ┌──────────▼───────────▼───────────▼──────────────────────┐  │
│  │                  处理流程（每个线程）                        │  │
│  │  1. McpJsonResolve 解析元数据                              │  │
│  │  2. RedisService 缓存录音信息                              │  │
│  │  3. SUNECMService 上传音频文件                             │  │
│  │  4. ManagerService 推送处理结果                            │  │
│  │  5. CheckpointService 标记已处理                           │  │
│  └──────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
             │                    │                    │
             ▼                    ▼                    ▼
┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐
│     Redis       │  │    SUNECM       │  │    Manager      │
│  (缓存+检查点)   │  │ (统一内容平台)   │  │  (管理服务)      │
└─────────────────┘  └─────────────────┘  └─────────────────┘
```

### 2.2 模块划分

```
rfservice/
├── common/                    # 公共配置
│   ├── CryptoUtils.java       # 加密解密工具
│   ├── RedisConfig.java       # Redis配置
│   └── pointcut/              # AOP切面
├── controller/                # Web接口层
│   ├── Download.java          # 录音下载接口
│   ├── FileMonitor.java       # 文件监控接口
│   ├── SUNECMController.java  # SUNECM测试接口
│   └── TagRecord.java         # 录音标记接口
├── recorder/                  # 录音节点逻辑
│   ├── RecorderNode.java      # 录音节点定义
│   └── Scheduler.java         # 定时任务调度
├── service/                   # 业务服务层
│   ├── CheckpointService.java # 检查点服务
│   ├── ManagerService.java    # Manager交互服务
│   ├── PerformanceMonitorService.java # 性能监控
│   ├── RedisService.java      # Redis操作服务
│   └── SUNECMService.java     # SUNECM上传服务
├── util/                      # 工具类
│   ├── DateUtilU.java         # 日期工具
│   ├── FileListener.java      # 文件事件监听器（核心）
│   ├── FilterFileConfig.java  # 文件过滤配置（核心）
│   ├── McpJsonResolve.java    # MCP元数据解析
│   ├── NameResolve.java       # 文件名解析
│   └── RecordMonitor.java     # 录音监控工具
└── RfServiceApplication.java  # 应用入口
```

---

## 3. 核心组件详解

### 3.1 FilterFileConfig - 文件监控配置

**职责**: 启动文件目录监控，检测新创建的元数据文件

**核心机制**:
- 使用 Apache Commons IO 的 `FileAlterationMonitor` 实现目录轮询
- 轮询间隔可配置，默认 **10秒**（`recorder.file-monitor.interval-seconds`）
- 文件过滤器：仅监控 `.json` 后缀文件
- 启动时执行检查点恢复：处理上次停机后遗漏的文件

**关键配置**:
```yaml
recorderNode:
  mcp:
    path: D://record//        # 监控目录
recorder:
  file-monitor:
    interval-seconds: 10       # 轮询间隔
```

### 3.2 FileListener - 文件事件处理器（核心引擎）

**职责**: 处理文件创建事件，协调录音归档全流程

**核心特性**:
- 继承 `FileAlterationListenerAdaptor` 监听文件事件
- 使用 `ThreadPoolExecutor` 实现多线程并发处理
- 线程池大小可配置，默认 **8个线程**（`recorder.thread-pool.size`）
- 每个线程独立维护 SUNECM 连接（ThreadLocal模式）
- 原子计数器跟踪待处理任务数量
- 每60秒打印线程池状态和处理进度

**处理流程**:
```
onFileCreate(File file)
    │
    ├── 1. 校验文件类型（必须为.json）
    │
    ├── 2. 检查是否已处理（CheckpointService）
    │       └── 已处理则跳过
    │
    ├── 3. 提交到线程池异步处理
    │       │
    │       ├── 3.1 读取JSON文件内容
    │       │
    │       ├── 3.2 McpJsonResolve.logic() 解析元数据
    │       │       └── 提取: AGENTID, ANI, RECORDID, CONNID, dateTime, DN, path
    │       │
    │       ├── 3.3 补充节点信息
    │       │       └── RECNODE = 本机IP
    │       │       └── RECORDFILEPATH = 音频文件路径（.json路径去掉-htccmetadata.json）
    │       │
    │       ├── 3.4 Redis缓存
    │       │       ├── RECORDID -> nodeIp (7天过期)
    │       │       └── RECORDID_INFO -> 完整录音信息 (1天过期)
    │       │
    │       ├── 3.5 SUNECM上传
    │       │       ├── 调用 sunecmService.push(data)
    │       │       ├── 成功: 获取批次号BATCHNO
    │       │       └── 失败(FAIL): 尝试追加到已有批次
    │       │
    │       ├── 3.6 标记文件已处理
    │       │       └── checkpointService.markFileAsProcessed()
    │       │
    │       └── 3.7 推送Manager
    │               └── POST /transaction/mcprecord/add2
    │
    └── 4. 更新任务计数
```

### 3.3 SUNECMService - 统一内容平台上传服务

**职责**: 通过 Socket 连接将录音文件上传至 SUNECM 平台

**连接管理**:
- 使用 `ThreadLocal<SunEcmClientApi>` 为每个线程维护独立连接
- 每5分钟重新登录一次（`LOGIN_INTERVAL_MS = 5 * 60 * 1000`）
- 解决多线程并发访问的线程安全问题

**上传流程**:
```
ECMupload(JSONObject data)
    │
    ├── 1. 准备批次信息 ClientBatchBean
    │       ├── modelCode: IOC
    │       ├── filePartName: IOC_P
    │       └── breakPoint: false
    │
    ├── 2. 设置索引信息 ClientBatchIndexBean
    │       ├── BUSI_START_DATE: 当前日期
    │       ├── AMOUNT: 1
    │       ├── BUSI_SERIAL_NO: "0376" + 日期 + CONNID
    │       ├── CONNID, RECDATETIME, ANI, DN, AGENTID, RECORDID
    │       └── (空值字段填充为"pass")
    │
    ├── 3. 添加文件 ClientBatchFileBean
    │       ├── 检查文件格式是否在 fms 配置中（mp3、wav）
    │       └── 设置文件属性（格式、大小、路径）
    │
    ├── 4. 调用 api.upload() 上传
    │
    └── 5. 返回结果
            ├── SUCCESS: 返回批次号
            └── FAIL: 触发追加逻辑
```

**追加文件到已有批次**:
```
appendFile(JSONObject data)
    │
    ├── 1. 生成影像流水号 BUSI_SERIAL_NO
    │
    ├── 2. checkBusiSerialNo() 查询批次是否存在
    │       └── 使用高级查询 heightQuery
    │
    └── 3. 批次存在则追加
            └── api.update() 更新批次
```

### 3.4 ManagerService - 管理服务交互

**职责**: 将处理结果推送至 Manager 服务

**交互方式**:
- HTTP POST 请求
- 目标端点: `{manager.url}/transaction/mcprecord/add2`
- 请求体: 包含完整录音元数据的 JSON
- 使用 RestTemplate 发送请求

**推送数据字段**:
| 字段 | 说明 | 来源 |
|------|------|------|
| RECORDID | 录音唯一标识 | MCP元数据 |
| CONNID | 通话标识 | MCP元数据 |
| ANI | 主叫号码 | MCP元数据 |
| AGENTID | 坐席工号 | MCP元数据 |
| DN | 分机号 | MCP元数据 |
| RECDATETIME | 录音时间 | MCP元数据(+8小时) |
| RECNODE | 录音节点IP | 本机配置 |
| BATCHNO | 上传批次号 | SUNECM返回 |
| RECORDFILEPATH | 录音文件路径 | 推导得出 |

### 3.5 CheckpointService - 检查点服务

**职责**: 记录处理进度，防止重复处理和遗漏文件

**Redis Key 设计**:
```
rfservice:checkpoint:{nodeName}:{nodeIp}     -> 上次检查点时间戳
rfservice:processed_files:{nodeName}:{nodeIp}:{yyyyMMdd} -> 已处理文件集合(Set)
```

**检查点恢复流程**:
```
服务启动
    │
    ├── 1. 读取上次检查点时间
    │       └── 无检查点则默认1天前
    │
    ├── 2. 扫描监控目录中所有.json文件
    │
    ├── 3. 筛选修改时间 > 检查点时间的文件
    │
    ├── 4. 排除已处理文件（Redis Set查询）
    │
    ├── 5. 逐个处理遗漏文件
    │
    └── 6. 更新检查点为当前时间
```

### 3.6 McpJsonResolve - MCP元数据解析器

**职责**: 解析 Genesys MCP 生成的元数据 JSON 文件

**MCP元数据结构示例**:
```json
{
  "mediaFiles": [
    {
      "mediaId": "/path/to/recording.wav",
      "parameters": {
        "callUuid": "RECORDID值",
        "connId": "CONNID值",
        "agentId": "坐席工号",
        "ani": "主叫号码",
        "dnis": "被叫号码",
        "dateTime": "2024-01-01 10:00:00"
      }
    }
  ]
}
```

**字段映射**:
| MCP字段 | 系统字段 | 说明 |
|---------|---------|------|
| mediaFiles[0].parameters.callUuid | RECORDID | 录音唯一ID |
| mediaFiles[0].parameters.connId | CONNID | 通话ID |
| mediaFiles[0].parameters.agentId | AGENTID | 坐席工号 |
| mediaFiles[0].parameters.ani | ANI | 主叫号码 |
| mediaFiles[0].parameters.dnis | recordDN | 分机号 |
| mediaFiles[0].parameters.dateTime | RECDATETIME | 录音时间(+8小时) |
| mediaFiles[0].mediaId | RECPATH | 录音路径 |

---

## 4. 业务流程

### 4.1 完整录音归档流程

```
┌─────────────┐
│ 通话结束     │
└──────┬──────┘
       │
       ▼
┌─────────────────────────────────────────┐
│ Genesys MCP 写入文件到录音目录            │
│ ├── 音频文件: xxx.wav / xxx.mp3          │
│ └── 元数据: xxx-htccmetadata.json       │
└──────────────────┬──────────────────────┘
                   │
                   ▼
┌─────────────────────────────────────────┐
│ RFService FilterFileConfig              │
│ 每10秒轮询监控目录                       │
└──────────────────┬──────────────────────┘
                   │ 发现新.json文件
                   ▼
┌─────────────────────────────────────────┐
│ FileListener.onFileCreate()             │
│ 触发文件创建事件                         │
└──────────────────┬──────────────────────┘
                   │
                   ▼
┌─────────────────────────────────────────┐
│ 检查点验证                               │
│ ├── 文件是否已处理？ ──是──> 跳过        │
│ └── 否 ──> 继续                         │
└──────────────────┬──────────────────────┘
                   │
                   ▼
┌─────────────────────────────────────────┐
│ 提交到线程池异步处理                      │
│ (ThreadPoolExecutor, 8线程并发)          │
└──────────────────┬──────────────────────┘
                   │
    ┌──────────────┼──────────────┐
    │              │              │
    ▼              ▼              ▼
┌────────┐   ┌────────┐   ┌────────┐
│解析JSON│   │缓存Redis│   │上传SUNECM│
│提取元数据│   │录音信息 │   │音频文件  │
└────┬───┘   └────┬───┘   └────┬───┘
     │            │            │
     └────────────┼────────────┘
                  │
                  ▼
         ┌────────────────┐
         │ 推送Manager     │
         │ /mcprecord/add2 │
         └────────┬───────┘
                  │
                  ▼
         ┌────────────────┐
         │ 标记文件已处理   │
         │ 更新检查点      │
         └────────────────┘
```

### 4.2 失败重试机制

```
上传SUNECM失败
       │
       ▼
┌─────────────────────────────┐
│ 返回结果以"FAIL"开头          │
└──────────────┬──────────────┘
               │
               ▼
┌─────────────────────────────┐
│ 生成影像流水号                │
│ BUSI_SERIAL_NO =            │
│ "0376" + 日期 + CONNID      │
└──────────────┬──────────────┘
               │
               ▼
┌─────────────────────────────┐
│ 查询批次是否已存在            │
│ checkBusiSerialNo()         │
└──────────────┬──────────────┘
               │
       ┌───────┴───────┐
       │               │
       ▼               ▼
   存在            不存在
       │               │
       ▼               ▼
┌─────────────┐  ┌─────────┐
│追加文件到批次│  │ 跳过处理 │
│ api.update()│  │ (记录日志)│
└─────────────┘  └─────────┘
```

### 4.3 服务启动恢复流程

```
服务启动
    │
    ├── 1. 打印版本信息
    │       └── app.version, app.build.time
    │
    ├── 2. 初始化组件
    │       ├── Redis连接池
    │       ├── SUNECM连接
    │       └── 线程池
    │
    ├── 3. 执行检查点恢复
    │       ├── 读取上次检查点时间
    │       ├── 扫描遗漏文件
    │       ├── 处理未上传文件
    │       └── 更新检查点
    │
    └── 4. 启动文件监控
            └── FileAlterationMonitor.start()
```

---

## 5. 定时任务

### 5.1 任务清单

| 任务 | 类 | 频率 | 功能 |
|------|-----|------|------|
| 线程池状态打印 | FileListener | 每60秒 | 输出线程池活动状态和处理进度 |
| 性能统计报告 | PerformanceMonitorService | 每60秒 | 输出文件处理速度和数据吞吐量 |
| 录音迁移 | Scheduler | 每5分钟 | 从Redis获取任务，处理录音文件迁移 |
| 内存监控 | SUNECMService | 每5分钟 | 输出JVM内存使用情况 |
| 检查点清理 | Scheduler | 每天凌晨2点 | 清理7天前的过期检查点记录 |

---

## 6. 外部接口

### 6.1 对外提供的接口

| 接口 | 方法 | 路径 | 功能 |
|------|------|------|------|
| 录音下载 | GET/POST | /transaction/file/downloadLocal | 根据recid下载录音文件 |
| 文件监控 | POST | /transaction/file/do | 手动触发目录遍历 |
| SUNECM测试 | GET/POST | /transaction/sunecm/test | SUNECM连接测试 |
| 录音标记 | POST | /transaction/tag/do | 标记指定录音文件 |

### 6.2 依赖的外部服务

| 服务 | 配置项 | 端点 | 用途 |
|------|--------|------|------|
| Manager | manager.url | /transaction/mcprecord/add2 | 推送录音处理结果 |
| Redis | spring.redis.host/port | 6379 | 缓存和检查点存储 |
| SUNECM | sunecm.ip/socketPort | 8023 | 录音文件归档上传 |

---

## 7. 配置说明

### 7.1 核心配置项

```yaml
# 服务端口
server:
  port: 9990

# Redis配置
spring:
  redis:
    host: localhost
    port: 6379
    database: 0

# 录音处理线程池
recorder:
  thread-pool:
    size: 8                    # 并发处理线程数
  file-monitor:
    interval-seconds: 10       # 文件监控轮询间隔

# 录音节点信息
recorderNode:
  info:
    name: rec94                # 节点名称
    ip: 10.3.67.91             # 节点IP
  mcp:
    path: D://record//         # MCP录音文件目录
  nas:
    path: D://record//         # NAS存储目录

# Manager服务地址
manager:
  url: http://127.0.0.1:9999

# SUNECM统一内容平台
sunecm:
  fms: mp3、wav                # 允许的音频格式
  modelCode: IOC               # 内容模型代码
  filePartName: IOC_P          # 文档部件名称
  userName: tfcc               # 登录用户名
  password: tfcc376            # 登录密码
  ip: 32.114.152.155           # 服务器IP
  socketPort: 8023             # Socket端口
  httpPort: 7001               # HTTP端口
```

### 7.2 生产环境配置要点

- 录音路径需配置为 Linux 路径格式
- SUNECM 连接信息需与生产环境一致
- Redis 建议使用集群模式
- 线程池大小根据并发量调整

---

## 8. 数据流转图

```
┌──────────┐    元数据JSON     ┌──────────┐    解析结果     ┌──────────┐
│  Genesys │ ───────────────> │   MCP    │ ─────────────> │ RFService│
│  CTI平台 │                  │  元数据  │                │  处理引擎 │
└──────────┘                  └──────────┘                └────┬─────┘
                                                               │
                    ┌──────────────────────────────────────────┤
                    │                    │                     │
                    ▼                    ▼                     ▼
             ┌──────────┐        ┌──────────┐          ┌──────────┐
             │  Redis   │        │  SUNECM  │          │ Manager  │
             │  缓存层   │        │ 内容平台  │          │ 管理服务  │
             └──────────┘        └──────────┘          └──────────┘
                    │                    │                     │
                    │  检查点/去重        │  归档存储            │  业务入库
                    │                    │                     │
                    └────────────────────┴─────────────────────┘
                                          │
                                          ▼
                                    ┌──────────┐
                                    │  数据持久化 │
                                    └──────────┘
```

---

## 9. 关键设计特点

### 9.1 高并发处理

- **线程池模型**: 使用 `ThreadPoolExecutor` 固定8线程并发处理录音文件
- **ThreadLocal连接**: 每个线程独立维护 SUNECM Socket 连接，避免并发冲突
- **原子计数器**: `AtomicInteger` 和 `AtomicLong` 保证线程安全的任务计数

### 9.2 可靠性保障

- **检查点机制**: 通过 Redis 记录处理进度，服务重启后自动恢复
- **防重复处理**: Redis Set 记录已处理文件路径，避免重复上传
- **失败重试**: 上传失败时自动尝试追加到已有批次

### 9.3 可观测性

- **性能监控**: 每分钟统计文件处理速度和数据吞吐量
- **线程池监控**: 每分钟输出线程活动状态和任务分布
- **内存监控**: 每5分钟输出 JVM 内存使用情况
- **详细日志**: 关键节点输出诊断日志，便于问题排查

### 9.4 灵活配置

- **轮询间隔可调**: 通过配置文件调整文件监控频率
- **线程池可调**: 根据实际并发量调整处理线程数
- **格式过滤**: 通过 `fms` 配置控制支持的音频格式

---

## 10. 文件命名规范

### 10.1 录音文件

```
格式: {时间戳}_{坐席}_{通话标识}.wav
示例: 2026-09-16_10-19-00__2015_-007E02AA-10010707-00000001.wav
```

### 10.2 元数据文件

```
格式: {录音文件名}-htccmetadata.json
示例: 2026-09-16_10-19-00__2015_-007E02AA-10010707-00000001.wav-htccmetadata.json
```

### 10.3 影像流水号

```
格式: 0376{YYYYMMDD}{CONNID}
示例: 037620260916ABC123
```

---

## 11. 故障排查指南

### 11.1 常见问题

| 问题 | 可能原因 | 排查方法 |
|------|---------|---------|
| 录音文件未处理 | 检查点跳过 | 查看 Redis 中 processed_files 集合 |
| 上传SUNECM失败 | 网络连接问题 | 检查 SUNECM 服务可达性和登录状态 |
| 重复上传 | Redis数据丢失 | 检查 Redis 连接和检查点数据 |
| 线程池满载 | 并发量过高 | 查看线程池监控日志，考虑增加线程数 |
| 文件格式不匹配 | fms配置问题 | 检查 sunecm.fms 配置是否包含目标格式 |

### 11.2 日志关键字

```
[新建]          - 文件创建事件
开始上传录音文件  - SUNECM上传开始
文件上传成功     - SUNECM上传成功
文件上传失败     - SUNECM上传失败
向Manager推送   - Manager回调
保存检查点       - 检查点更新
```

---

## 12. 部署架构

```
┌─────────────────────────────────────────────────────────┐
│                     生产环境部署                          │
├─────────────────────────────────────────────────────────┤
│                                                           │
│  ┌──────────────┐    ┌──────────────┐                   │
│  │  RFService   │    │  RFService   │   (多实例部署)     │
│  │  节点1       │    │  节点2       │                    │
│  │  rec94       │    │  rec95       │                    │
│  └──────┬───────┘    └──────┬───────┘                   │
│         │                   │                             │
│         └─────────┬─────────┘                             │
│                   │                                         │
│         ┌─────────▼─────────┐                             │
│         │    Redis Cluster   │   (共享检查点和缓存)         │
│         │   10.255.144.115   │                             │
│         └─────────┬─────────┘                             │
│                   │                                         │
│    ┌──────────────┼──────────────┐                        │
│    │              │              │                          │
│    ▼              ▼              ▼                          │
│ ┌──────┐    ┌──────┐    ┌──────────┐                      │
│ │SUNECM│    │Manager│    │ Genesys  │                      │
│ │ 平台 │    │ 服务  │    │  MCP     │                      │
│ └──────┘    └──────┘    └──────────┘                      │
│                                                           │
└─────────────────────────────────────────────────────────┘
```

---

**文档版本**: v1.0  
**最后更新**: 2026-11-01  
**维护人员**: RFService 开发团队
