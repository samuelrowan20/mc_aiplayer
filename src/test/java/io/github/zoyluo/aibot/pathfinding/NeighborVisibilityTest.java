package io.github.zoyluo.aibot.pathfinding;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NeighborVisibilityTest {
    @Test
    void unknownCandidatesAreRejectedBeforeAnyWorldAccess() {
        // Null is a tripwire: reading terrain before the visibility guard would throw.
        NeighborEnumerator enumerator = new NeighborEnumerator(pos -> false);
        assertTrue(enumerator.getNeighbors(BlockPos.ORIGIN, null).isEmpty());
    }

    @Test
    void knownFeetDoNotAuthorizeHiddenHeadOrSupportReads() {
        BlockPos feet = new BlockPos(0, 64, 0);
        for (BlockPos hidden : new BlockPos[]{feet.up(), feet.down()}) {
            NeighborEnumerator enumerator = new NeighborEnumerator(pos -> !pos.equals(hidden));
            assertFalse(enumerator.isStandable(null, feet));
        }
    }

    @Test
    void unknownEndpointDoesNotProbeForASnappedStart() {
        var result = new AStarPathfinder(null, BlockPos.ORIGIN, new BlockPos(5, 0, 0),
                20, 100, pos -> false).findPath();
        assertFalse(result.success());
        assertEquals(FailureReason.NO_START, result.reason());
        assertEquals(0, result.nodesExplored());
    }
}
