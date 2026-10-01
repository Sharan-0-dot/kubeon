package com.sharan.kubeon.detection;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class IssueDeduplicator {

    private final Set<String> reported = ConcurrentHashMap.newKeySet();

    /**
     * Attempts to record an issue key (namespace/name/reason).
     *
     * @return true if this is the first time the issue is seen, false if already reported.
     */
    public boolean isNew(String namespace, String name, BadStateReason reason) {
        String key = namespace + "/" + name + "/" + reason;
        return reported.add(key);
    }

    /**
     * Clears reported issues for a specific pod when it is deleted.
     */
    public void clear(String namespace, String name) {
        String prefix = namespace + "/" + name + "/";
        reported.removeIf(k -> k.startsWith(prefix));
    }
}
