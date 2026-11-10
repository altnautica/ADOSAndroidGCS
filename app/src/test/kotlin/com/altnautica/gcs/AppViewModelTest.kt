package com.altnautica.gcs

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.altnautica.gcs.core.data.home.SelectedVehicleStore
import com.altnautica.gcs.core.link.vehicle.OpenLink
import com.altnautica.gcs.core.link.vehicle.VehicleRepository
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.FlightMode
import com.altnautica.gcs.core.model.HeartbeatState
import com.altnautica.gcs.core.model.Reach
import com.altnautica.gcs.core.model.Vehicle
import com.altnautica.gcs.feature.configure.wizard.GroundSetupCheck
import com.altnautica.gcs.navigation.CockpitKey
import com.altnautica.gcs.navigation.HomeKey
import com.altnautica.gcs.replay.ReplayLauncher
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val prefs = MemoryPreferences()
    private val node = DeviceId("drone-1")
    private val links = MutableStateFlow<List<OpenLink>>(emptyList())
    private val heartbeat = MutableStateFlow<HeartbeatState?>(null)
    private val vehicle = mockk<Vehicle> {
        every { telemetry.heartbeat } returns heartbeat
    }
    private val open = MutableStateFlow<Map<DeviceId, Vehicle>>(emptyMap())
    private val vehicles = mockk<VehicleRepository> {
        every { openLinks } returns links
        every { openVehicles } returns open
    }

    /** The flight-controller uid the setup engine reports as needing the ground wizard, or null. */
    private var setupUid: String? = null
    private val groundSetup = mockk<GroundSetupCheck> {
        coEvery { uidNeedingSetup(any()) } answers { setupUid }
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = AppViewModel(vehicles, prefs, mockk<ReplayLauncher>(relaxed = true), SelectedVehicleStore(prefs), groundSetup)

    private fun armed(value: Boolean) {
        heartbeat.value = HeartbeatState(armed = value, mode = FlightMode(0, "STABILIZE"), vehicleType = 2, autopilot = 3, tMs = 0)
    }

    private fun linkUp() {
        links.value = listOf(OpenLink(node, Reach.Lan))
        open.value = mapOf(node to vehicle)
    }

    private fun TestScope.observe(vm: AppViewModel): MutableList<DeviceId> {
        val navigated = mutableListOf<DeviceId>()
        backgroundScope.launch { vm.askNotifications.collect {} }
        backgroundScope.launch { vm.setupOffer.collect {} }
        backgroundScope.launch { vm.setupNavigation.collect { navigated += it } }
        return navigated
    }

    @Test
    fun notificationAskWaitsForHomeAndDisarmed() = runTest(dispatcher) {
        val vm = viewModel()
        observe(vm)
        armed(false)
        linkUp()

        vm.topRouteChanged(CockpitKey(node.value))
        assertFalse("never over the cockpit", vm.askNotifications.value)

        vm.topRouteChanged(HomeKey)
        armed(true)
        assertFalse("never while armed", vm.askNotifications.value)

        // The link closing does not drop the queued ask.
        links.value = emptyList()
        armed(false)
        assertTrue("asked on Home, disarmed", vm.askNotifications.value)

        vm.notificationsAsked()
        assertFalse(vm.askNotifications.value)
    }

    @Test
    fun noNotificationAskBeforeAnyLink() = runTest(dispatcher) {
        val vm = viewModel()
        observe(vm)
        vm.topRouteChanged(HomeKey)
        assertFalse(vm.askNotifications.value)
    }

    @Test
    fun connectOffersSetupWithoutOpeningTheWizard() = runTest(dispatcher) {
        setupUid = "fc-uid-1"
        val vm = viewModel()
        val navigated = observe(vm)
        var home = 0
        linkUp()

        vm.connected(node, onSelected = { home++ })

        assertEquals(1, home)
        assertEquals(emptyList<DeviceId>(), navigated)
        assertEquals(SetupOffer("fc-uid-1", node), vm.setupOffer.value)

        vm.openSetup()
        assertEquals(listOf(node), navigated)
        assertNull(vm.setupOffer.value)
    }

    @Test
    fun offerShownOncePerUid() = runTest(dispatcher) {
        setupUid = "fc-uid-1"
        val first = viewModel()
        observe(first)
        linkUp()
        first.connected(node) {}
        first.openSetup()

        val next = viewModel()
        val navigated = observe(next)
        next.connected(node) {}
        assertNull(next.setupOffer.value)
        assertEquals(emptyList<DeviceId>(), navigated)
    }

    @Test
    fun laterSuppressesTheOfferNextTime() = runTest(dispatcher) {
        setupUid = "fc-uid-1"
        val first = viewModel()
        val navigated = observe(first)
        linkUp()
        first.connected(node) {}
        first.later()
        assertNull(first.setupOffer.value)
        assertEquals(emptyList<DeviceId>(), navigated)

        val next = viewModel()
        observe(next)
        next.connected(node) {}
        assertNull(next.setupOffer.value)
    }

    @Test
    fun noOfferWhileArmed() = runTest(dispatcher) {
        setupUid = "fc-uid-1"
        val vm = viewModel()
        observe(vm)
        linkUp()
        armed(true)

        vm.connected(node) {}
        assertNull(vm.setupOffer.value)

        armed(false)
        assertEquals(SetupOffer("fc-uid-1", node), vm.setupOffer.value)
    }

    @Test
    fun noOfferWhenSetupIsDone() = runTest(dispatcher) {
        val vm = viewModel()
        observe(vm)
        vm.connected(node) {}
        assertNull(vm.setupOffer.value)
    }

    @Test
    fun offersMadeBeforeTheKeyRenameStillCount() = runTest(dispatcher) {
        prefs.edit { it[LEGACY_OFFERED] = setOf("fc-uid-1") }
        setupUid = "fc-uid-1"
        val vm = viewModel()
        observe(vm)
        linkUp()

        vm.connected(node) {}
        assertNull(vm.setupOffer.value)

        val stored = prefs.data.first()
        assertNull(stored[LEGACY_OFFERED])
        assertEquals(setOf("fc-uid-1"), stored[OFFERED])
    }

    @Test
    fun aClosedLinkHidesItsOfferWithoutSpendingIt() = runTest(dispatcher) {
        setupUid = "fc-uid-1"
        val vm = viewModel()
        val navigated = observe(vm)
        linkUp()
        vm.connected(node) {}
        assertEquals(SetupOffer("fc-uid-1", node), vm.setupOffer.value)

        links.value = emptyList()
        open.value = emptyMap()
        assertNull(vm.setupOffer.value)
        vm.openSetup()
        vm.later()
        assertEquals(emptyList<DeviceId>(), navigated)
        assertNull(prefs.data.first()[OFFERED])

        // A link that comes back (failover, retry) without a new connect shows the offer again.
        linkUp()
        assertEquals(SetupOffer("fc-uid-1", node), vm.setupOffer.value)
    }

    @Test
    fun theOfferFollowsTheSelectedVehicle() = runTest(dispatcher) {
        val other = DeviceId("drone-2")
        val vm = viewModel()
        observe(vm)
        links.value = listOf(OpenLink(node, Reach.Lan), OpenLink(other, Reach.Lan))
        open.value = mapOf(node to vehicle, other to vehicle)

        setupUid = "fc-uid-1"
        vm.connected(node) {}
        setupUid = "fc-uid-2"
        vm.connected(other) {}

        assertEquals(SetupOffer("fc-uid-2", other), vm.setupOffer.value)
    }

    private companion object {
        val OFFERED = stringSetPreferencesKey("setup.offered_uids")
        val LEGACY_OFFERED = stringSetPreferencesKey("app.aircraftSetupOffered")
    }
}
