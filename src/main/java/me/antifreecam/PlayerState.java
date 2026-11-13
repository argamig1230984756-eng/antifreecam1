package me.antifreecam;

/** Per-player data read by the packet (netty) thread, so fields are volatile. */
public class PlayerState {
    public volatile boolean masked = true;     // surface player -> underground hidden
    public volatile boolean worldEnabled = true;
    public volatile int minY = -64;            // world min build height
    public volatile String worldName = "";
}
