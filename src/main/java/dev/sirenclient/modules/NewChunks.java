package dev.sirenclient.modules;

/** Toggle only; classification happens on chunk load (see ChunkAge), drawing in EspRenderer. */
public class NewChunks extends Module {
    public NewChunks() { super("NewChunks", "New chunks red, old chunks blue (trails)"); }

    @Override public void clear() { ChunkAge.clear(); }
}
