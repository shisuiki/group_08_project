package edu.illinois.group8.messages;

public abstract class Message {
    private String type;
    private int sid;

    public String getType() {
        return type;
    }

    public int getSid() {
        return sid;
    }

    @Override
    public String toString() {
        return "Message{" +
                "type='" + type + '\'' +
                ", sid=" + sid +
                '}';
    }
}

