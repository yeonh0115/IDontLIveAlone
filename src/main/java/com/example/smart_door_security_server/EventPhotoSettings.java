package com.example.smart_door_security_server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Component
public class EventPhotoSettings {
    private final boolean enabled;
    public EventPhotoSettings(@Value("${EVENT_PHOTOS_ENABLED:false}") boolean enabled) { this.enabled = enabled; }
    public boolean isEnabled() { return enabled; }
    public void requireEnabled() { if (!enabled) throw new Disabled(); }
    public static class Disabled extends ResponseStatusException {
        Disabled() { super(HttpStatus.GONE, "이벤트 사진 저장 기능이 꺼져 있습니다."); }
    }
}
