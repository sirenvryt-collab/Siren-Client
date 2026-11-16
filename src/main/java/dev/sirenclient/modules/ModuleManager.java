package dev.sirenclient.modules;

import java.util.List;

public class ModuleManager {
    public final BaseFinder base = new BaseFinder();
    public final SusChunks sus = new SusChunks();
    public final NewChunks newChunks = new NewChunks();
    public final BlockEsp blockEsp = new BlockEsp();
    public final PlayerEsp playerEsp = new PlayerEsp();
    private final List<Module> all = List.of(base, sus, newChunks, blockEsp, playerEsp);

    public List<Module> all() { return all; }

    public void tick() {
        for (Module m : all) if (m.isEnabled()) m.onTick();
    }

    public void clearAll() {
        for (Module m : all) m.clear();
    }
}
