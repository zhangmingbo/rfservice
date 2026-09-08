package cn.mrusg.jnbank.callcenter.rfservice.util;

import cn.hutool.core.date.DateUtil;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;

import java.util.Date;

@Slf4j
public class NameResolve {


    public static JSONObject logic(String args) {
        // 解析录音文件文件名
        JSONObject jsonObject = new JSONObject();
        String[] refFileName = args.split("_");
        jsonObject.put("RECORDID", refFileName[0]);
        jsonObject.put("AGENTID", refFileName[2]);
        jsonObject.put("ANI", refFileName[3]);
        jsonObject.put("DN", refFileName[1]);
        // 解析日期时间
        String time = refFileName[6].substring(0, 8).replace('-', ':');
        String dateStr = refFileName[5] + " " + time;
        Date date = DateUtil.parse(dateStr);
        DateUtil.offsetHour(date, +8);
        jsonObject.put("RECDATETIME", dateStr);
        jsonObject.put("RECPATH", args);
        log.info(jsonObject.toJSONString());
        return jsonObject;
    }



}
