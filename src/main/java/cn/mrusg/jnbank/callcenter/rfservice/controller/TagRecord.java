package cn.mrusg.jnbank.callcenter.rfservice.controller;

import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * 对外，标记指定录音文件，被标记的录音文件通过定时任务迁移到共享存储or本地其他位置
 */

@Slf4j
@Controller
@RequestMapping("/transaction/tag")
public class TagRecord {
    @ResponseBody
    @PostMapping("/do")
    public static JSONObject logic(JSONObject jsonObject) {



        // 从Redis 获取登记的信息，
        return null;
    }

}
