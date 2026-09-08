package cn.mrusg.jnbank.callcenter.rfservice.service;

import com.alibaba.fastjson.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

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
            restTemplate.postForObject(url, request, String.class);
        } catch (Exception e) {
            e.printStackTrace();
        }


        return null;
    }
}
