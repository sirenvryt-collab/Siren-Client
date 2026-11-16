package dev.sirenclient.modules;

public abstract class Module {
    public final String name;
    public final String desc;
    private boolean enabled;

    protected Module(String name, String desc) { this.name = name; this.desc = desc; }

    public boolean isEnabled() { return enabled; }

    public void toggle() {
        enabled = !enabled;
        if (enabled) onEnable(); else onDisable();
    }

    public void onEnable() {}
    public void onDisable() {}
    public void onTick() {}
    public void clear() {}
}
