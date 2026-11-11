package com.personality.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ButtonEventProcessorTest {
    @Test fun longPressBoundary() {
        assertFalse(ButtonEventProcessor.isLong(100, 649))
        assertTrue(ButtonEventProcessor.isLong(100, 650))
    }

    @Test fun doublePressBoundary() {
        assertFalse(ButtonEventProcessor.isDouble(100, 99))
        assertTrue(ButtonEventProcessor.isDouble(100, 380))
        assertFalse(ButtonEventProcessor.isDouble(100, 381))
    }
}
