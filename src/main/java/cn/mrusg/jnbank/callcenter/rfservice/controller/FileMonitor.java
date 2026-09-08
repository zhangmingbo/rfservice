package cn.mrusg.jnbank.callcenter.rfservice.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.File;

@Slf4j
@Controller
@RequestMapping("/transaction/file")
public class FileMonitor {

    // 录音节点跑批逻辑
    @ResponseBody
    @PostMapping("/do")
    public static String getFileList(String[] args) {
        String path = "D:\\record";        //要遍历的路径
        File file = new File(path);        //获取其file对象
        File[] fs = file.listFiles();    //遍历path下的文件和目录，放在File数组中
        for (File f : fs) {                    //遍历File[]数组
            if (!f.isDirectory())        //若非目录(即文件)，则打印
                log.info(f.toString());
        }
        return "";
    }
}
