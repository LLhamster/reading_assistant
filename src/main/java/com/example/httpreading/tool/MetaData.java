package com.example.httpreading.tool;

public class MetaData {
    private  String session_id;
    private String timestamp;
    public MetaData(String session_id, String timestamp) {
        this.session_id = session_id;
        this.timestamp = timestamp;
    }
    //getter and setter
    public String getSession_id() {
        return session_id;
    }
    public void setSession_id(String session_id) {
        this.session_id = session_id;
    }
    public String getTimestamp() {
        return timestamp;
    }
    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }
    
}
