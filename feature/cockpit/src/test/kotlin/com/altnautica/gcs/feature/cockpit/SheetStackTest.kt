package com.altnautica.gcs.feature.cockpit

import com.altnautica.gcs.feature.cockpit.CockpitSheet.Camera
import com.altnautica.gcs.feature.cockpit.CockpitSheet.Confirm
import com.altnautica.gcs.feature.cockpit.CockpitSheet.Launch
import com.altnautica.gcs.feature.cockpit.CockpitSheet.Mode
import com.altnautica.gcs.feature.cockpit.CockpitSheet.Picker
import com.altnautica.gcs.feature.cockpit.CockpitSheet.Preflight
import com.altnautica.gcs.feature.cockpit.CockpitSheet.Quick
import com.altnautica.gcs.feature.cockpit.CockpitSheet.Status
import org.junit.Assert.assertEquals
import org.junit.Test

class SheetStackTest {
    @Test
    fun `opening a sheet closes the one already open`() {
        assertEquals(setOf(Picker), SheetStack.toClose(setOf(Picker), setOf(Picker, Mode)))
        assertEquals(setOf(Status, Quick), SheetStack.toClose(setOf(Status, Quick), setOf(Status, Quick, Launch)))
    }

    @Test
    fun `closing a sheet or opening a gate over one closes nothing`() {
        assertEquals(emptySet<CockpitSheet>(), SheetStack.toClose(setOf(Mode, Picker), setOf(Mode)))
        assertEquals(emptySet<CockpitSheet>(), SheetStack.toClose(setOf(Launch), setOf(Launch, Preflight)))
        assertEquals(emptySet<CockpitSheet>(), SheetStack.toClose(setOf(Mode), setOf(Mode, Confirm)))
    }

    @Test
    fun `only the topmost overlay draws`() {
        assertEquals(Confirm, SheetStack.visible(setOf(Mode, Confirm)))
        assertEquals(Preflight, SheetStack.visible(setOf(Launch, Preflight)))
        assertEquals(Launch, SheetStack.visible(setOf(Launch)))
        assertEquals(null, SheetStack.visible(emptySet()))
    }

    @Test
    fun `camera settings is a peer sheet, opening it closes the others and opening another closes it`() {
        assertEquals(setOf(Quick), SheetStack.toClose(setOf(Quick), setOf(Quick, Camera)))
        assertEquals(setOf(Camera), SheetStack.toClose(setOf(Camera), setOf(Camera, Picker)))
        assertEquals(Camera, SheetStack.visible(setOf(Camera)))
        assertEquals(Confirm, SheetStack.visible(setOf(Camera, Confirm)))
    }
}
