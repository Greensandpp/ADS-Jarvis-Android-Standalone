package com.adsjarvis.standalone.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Storage port: SQLite on Android, in-memory for tests. */
public interface MemoryRepo {
    long insert(Memory m);
    void update(Memory m);
    void delete(long id);
    Memory get(long id);
    List<Memory> all();
    void clear();

    final class InMemory implements MemoryRepo {
        private final Map<Long, Memory> rows = new LinkedHashMap<>();
        private long next = 1;
        public synchronized long insert(Memory m) { m.id = next++; rows.put(m.id, m); return m.id; }
        public synchronized void update(Memory m) { rows.put(m.id, m); }
        public synchronized void delete(long id) { rows.remove(id); }
        public synchronized Memory get(long id) { return rows.get(id); }
        public synchronized List<Memory> all() { return new ArrayList<>(rows.values()); }
        public synchronized void clear() { rows.clear(); }
    }
}
