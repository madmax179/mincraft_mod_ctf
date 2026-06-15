package ru.madmax.tf1.team;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.DustParticleOptions;
import org.joml.Vector3f;

public enum TF1Team {

    RED  (ChatFormatting.RED,   1.0f, 0.1f, 0.1f, 0xAA0000),
    BLUE (ChatFormatting.BLUE,  0.1f, 0.3f, 1.0f, 0x1111AA),
    GREEN(ChatFormatting.GREEN, 0.0f, 0.9f, 0.0f, 0x00AA00),
    GOLD (ChatFormatting.GOLD,  1.0f, 0.7f, 0.0f, 0xFFAA00);

    public final ChatFormatting color;
    /** RGB color used for dyeing the team leather helmet. */
    public final int helmetColor;
    private final float r, g, b;

    TF1Team(ChatFormatting color, float r, float g, float b, int helmetColor) {
        this.color = color;
        this.r = r;
        this.g = g;
        this.b = b;
        this.helmetColor = helmetColor;
    }

    public DustParticleOptions dustOptions() {
        return new DustParticleOptions(new Vector3f(r, g, b), 1.0f);
    }
}
