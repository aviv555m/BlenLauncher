package com.blen.core.version;

/**
 * Represents a single Minecraft version.
 */
public class MinecraftVersion {
    private final String id;
    private final String type; // release, snapshot
    private final long time;

    public MinecraftVersion(String id, String type, long time) {
        this.id = id;
        this.type = type;
        this.time = time;
    }

    public String getId() { return id; }
    public String getType() { return type; }
    public long getTime() { return time; }

    @Override
    public String toString() {
        if ("release".equals(type)) {
            return id;
        }
        return id + " (snapshot)";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MinecraftVersion)) return false;
        return id.equals(((MinecraftVersion) o).id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
