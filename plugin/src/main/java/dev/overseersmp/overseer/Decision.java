package dev.overseersmp.overseer;

/**
 * A validated model decision. {@code effect} is what will actually be applied; {@code requestedAction}/{@code requestedEffect}
 * are what the model asked for (sanitised, truncated) and exist only for the log.
 */
public record Decision(String reply, Action action, String effectId, Effect effect, int favorDelta,
                       String requestedAction, String requestedEffect) {}
