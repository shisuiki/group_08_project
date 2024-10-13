package edu.illinois.group8.event;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EventManager {
    private final Map<Class<? extends Event>, List<Listener>> listeners = new HashMap<>();

    public void register(Listener listener) {
        for (Method method : listener.getClass().getMethods()) {
            if (method.isAnnotationPresent(EventHandler.class)) {
                Class<?>[] parameterTypes = method.getParameterTypes();
                if (parameterTypes.length == 1 && Event.class.isAssignableFrom(parameterTypes[0])) {
                    Class<? extends Event> eventClass = (Class<? extends Event>) parameterTypes[0];
                    listeners.computeIfAbsent(eventClass, k -> new ArrayList<>()).add(listener);
                }
            }
        }
    }

    public void callEvent(Event event) {
        List<Listener> listenerList = listeners.get(event.getClass());
        if (listenerList != null) {
            for (Listener listener : listenerList) {
                try {
                    for (Method method : listener.getClass().getMethods()) {
                        if (method.isAnnotationPresent(EventHandler.class) &&
                                method.getParameterCount() == 1 &&
                                method.getParameterTypes()[0].equals(event.getClass())) {
                            method.invoke(listener, event);
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
