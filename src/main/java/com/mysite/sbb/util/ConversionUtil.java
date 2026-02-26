package com.mysite.sbb.util;

import java.sql.Date;
import java.text.ParseException;
import java.text.SimpleDateFormat;

public class ConversionUtil {

    public Date stringToDate(String date) throws ParseException {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
        java.util.Date parsed = sdf.parse(date);
        return new Date(parsed.getTime());
    }
}
