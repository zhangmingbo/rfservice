package cn.mrusg.jnbank.callcenter.rfservice.service;

import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Service
public class ManagerService {
    @Value(value = "${manager.url}")
    public String serviceUrl;

    public JSONObject message(JSONObject jsonObject, String path) {

        RestTemplate restTemplate = new RestTemplate();
        String url = serviceUrl + path;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<JSONObject> request = new HttpEntity<>(jsonObject, headers);
        try {
            log.info("向Manager推送数据 - URL[{}] 录音ID[{}]", url, jsonObject.getString("RECORDID"));
            String response = restTemplate.postForObject(url, request, String.class);
            log.info("Manager响应 - URL[{}] 响应[{}]", url, response);
        } catch (Exception e) {
            log.error("向Manager推送数据失败 - URL[{}] 录音ID[{}]", url, jsonObject.getString("RECORDID"), e);
        }

        return null;
    }
}
