package cn.mrusg.jnbank.callcenter.rfservice.util;

import java.text.SimpleDateFormat;
import java.util.Date;

public class DateUtilU {

    public static String getNow(String format) {
        return new SimpleDateFormat(format).format(new Date());
    }

    public static String getYYYYMMDD() {
        return new SimpleDateFormat("yyyyMMdd").format(new Date());
    }

    public static void main(String[] args) {
        System.out.println(getYYYYMMDD());
    }
}
