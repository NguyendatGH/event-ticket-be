package com.example.demo.domain.inventory;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class InventoryTest {

    @Test
    void reserveAndReleaseKeepCountConsistent() {
        Inventory inv = new Inventory(UUID.randomUUID(), 3);
        assertTrue(inv.canReserve(3));
        assertFalse(inv.canReserve(4));
        assertFalse(inv.canReserve(0));
        inv.reserve(2);
        assertEquals(1, inv.getAvailable());
        assertThrows(IllegalStateException.class, () -> inv.reserve(2));
        inv.release(2);
        assertEquals(3, inv.getAvailable());
        assertThrows(IllegalArgumentException.class, () -> inv.release(0));
    }
}
