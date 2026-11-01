package cn.mrusg.jnbank.callcenter.rfservice.controller;

import cn.mrusg.jnbank.callcenter.rfservice.service.SUNECMService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/transaction/sunecm")
public class SUNECMController {

    @Autowired
    SUNECMService sunecmService;

    @ResponseBody
    @RequestMapping("/test")
    public String test() {
        log.info("SUNECM测试接口被调用");
        return "test";
    }

}
