package dev.sirenclient.modules;

/** A scored chunk: total score plus the position of its heaviest block. */
public record Hit(int score, int x, int y, int z) {}
