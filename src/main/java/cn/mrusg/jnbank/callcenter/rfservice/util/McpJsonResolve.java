package cn.mrusg.jnbank.callcenter.rfservice.util;

import cn.hutool.core.date.DateUtil;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.JSONPath;
import lombok.extern.slf4j.Slf4j;

import java.util.Date;

@Slf4j
public class McpJsonResolve {


    public static JSONObject logic(String args) {
        // 解析录音文件文件名
        JSONObject jsonObject = new JSONObject();
        String AGENTID = (String) JSONPath.read(args, "$.mediaFiles[0].parameters.agentId");
        String ANI = (String) JSONPath.read(args, "$.mediaFiles[0].parameters.ani");
        String RECORDID = (String) JSONPath.read(args, "$.mediaFiles[0].parameters.callUuid");
        String connId = (String) JSONPath.read(args, "$.mediaFiles[0].parameters.connId");
        String dateTime = (String) JSONPath.read(args, "$.mediaFiles[0].parameters.dateTime");
        String DN = (String) JSONPath.read(args, "$.mediaFiles[0].parameters.dnis");
        String path = (String) JSONPath.read(args, "$.mediaFiles[0].mediaId");

        // MCP 时差offset
        Date date = DateUtil.parse(dateTime);
        date = DateUtil.offsetHour(date, +8);

        jsonObject.put("RECDATETIME", DateUtil.format(date, "yyyy-MM-dd HH:mm:ss"));
        jsonObject.put("EXT1", DateUtil.format(date, "yyyyMMdd"));
        jsonObject.put("AGENTID", AGENTID);
        jsonObject.put("ANI", ANI);
        jsonObject.put("RECORDID", RECORDID);
        jsonObject.put("CONNID", connId);
        jsonObject.put("recordDN", DN);
        jsonObject.put("RECPATH", path);
        return jsonObject;
    }


}
