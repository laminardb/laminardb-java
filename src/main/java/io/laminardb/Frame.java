package io.laminardb;

/**
 * One frame of a named-stream subscription: a data batch or a durable
 * checkpoint barrier (the at-least-once contract building block, plan 03
 * §2). Sealed: exhaustive switches over exactly these two variants.
 */
public sealed interface Frame permits Frame.Data, Frame.Barrier {

    /** A data batch; its exported buffers stay valid after the next frame. */
    record Data(ArrowBatch batch) implements Frame {}

    /** Durable progress frontier for a checkpoint. */
    record Barrier(long sequence, long epoch, long checkpointId, long throughSequence) implements Frame {}
}
