package cn.mrusg.jnbank.callcenter.rfservice.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;

//@PropertySource("classpath:/application.yml")

/**
 * 对外下载录音用的
 */

@RestController
@RequestMapping("/transaction/file")
public class Download {
    /**
     * @param recid    录音文件
     * @param response
     * @功能描述 下载文件:将输入流中的数据循环写入到响应输出流中，而不是一次性读取到内存
     */

    @Value(value = "${recorderNode.nas.path}")
    private String pathPrefix;

    @RequestMapping("/downloadLocal")
    public void downloadLocal(String recid, HttpServletResponse response) throws IOException {
        // 读到流中

        String path = pathPrefix + recid;
        System.out.println(path);
        InputStream inputStream = new FileInputStream(path);// 文件的存放路径
        response.reset();
        response.setContentType("application/octet-stream");
        String filename = new File(path).getName();
        response.addHeader("Content-Disposition", "attachment; filename=" + URLEncoder.encode(filename, "UTF-8"));
        ServletOutputStream outputStream = response.getOutputStream();
        byte[] b = new byte[1024];
        int len;
        //从输入流中读取一定数量的字节，并将其存储在缓冲区字节数组中，读到末尾返回-1
        while ((len = inputStream.read(b)) > 0) {
            outputStream.write(b, 0, len);
        }
        inputStream.close();
    }

}
