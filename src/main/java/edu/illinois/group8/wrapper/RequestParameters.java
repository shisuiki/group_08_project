package edu.illinois.group8.wrapper;

import java.util.HashMap;

public class RequestParameters {
    private HashMap<String, Object> params = new HashMap<>();

    public void addParam(String key, Object value) {
        params.put(key, value);
    }

    public HashMap<String, Object> getParams() {
        return params;
    }
}
