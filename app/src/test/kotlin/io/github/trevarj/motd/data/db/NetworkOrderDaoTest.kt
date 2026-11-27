package io.github.trevarj.motd.data.db

import io.github.trevarj.motd.data.repo.NetworkRepositoryImpl
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Storage side of the manual drawer order: what a reorder writes, and what must never disturb it. */
@RunWith(RobolectricTestRunner::class)
class NetworkOrderDaoTest {
    private lateinit var db: MotdDatabase
    private lateinit var repository: NetworkRepositoryImpl

    @Before
    fun setUp() {
        db = inMemoryDb()
        repository = NetworkRepositoryImpl(db.networkDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun add(
        name: String,
        host: String = "$name.example",
    ): Long = repository.addNetwork(network(name).copy(host = host))

    private suspend fun names(): List<String> = repository.observeNetworks().first().map(NetworkEntity::name)

    @Test
    fun `added networks land at the end of the order`() =
        runTest {
            add("libera")
            add("hackint")
            add("oftc")

            assertEquals(listOf("libera", "hackint", "oftc"), names())
            assertEquals(listOf(0, 1, 2), repository.observeNetworks().first().map(NetworkEntity::ordering))
        }

    @Test
    fun `a reorder is what the drawer shows afterwards`() =
        runTest {
            val libera = add("libera")
            val hackint = add("hackint")
            val oftc = add("oftc")

            repository.reorderNetworks(listOf(oftc, libera, hackint))

            assertEquals(listOf("oftc", "libera", "hackint"), names())
            // Distinct and gap-free, so the next move has room in both directions.
            assertEquals(listOf(0, 1, 2), repository.observeNetworks().first().map(NetworkEntity::ordering))

            // And a network added after the reorder still goes last, not back to the top.
            add("ergo")
            assertEquals(listOf("oftc", "libera", "hackint", "ergo"), names())
        }

    @Test
    fun `rows the caller left out keep their relative order after the listed ones`() =
        runTest {
            val libera = add("libera")
            val hackint = add("hackint")
            add("oftc")

            // The drawer hides a child whose root is gone; an invisible row must not renumber the rest.
            repository.reorderNetworks(listOf(hackint, libera, 9999L))

            assertEquals(listOf("hackint", "libera", "oftc"), names())
        }

    @Test
    fun `editing a network in settings does not move it`() =
        runTest {
            val libera = add("libera")
            val hackint = add("hackint")
            add("oftc")
            repository.reorderNetworks(listOf(hackint, libera))

            // The settings form rebuilds the entity from its fields, so it carries the default
            // ordering = 0. Saving must not silently promote that network to the top of the drawer.
            val edited = repository.networkById(hackint)!!
            db.networkDao().setServerIconUrl(hackint, "https://example.org/new.png")
            repository.updateNetwork(edited.copy(ordering = 0, nick = "renamed"))

            assertEquals(listOf("hackint", "libera", "oftc"), names())
            assertEquals("renamed", repository.networkById(hackint)!!.nick)
            assertEquals("https://example.org/new.png", repository.networkById(hackint)!!.serverIconUrl)
        }

    @Test
    fun `reordering the same order twice changes nothing`() =
        runTest {
            val libera = add("libera")
            val hackint = add("hackint")

            repository.reorderNetworks(listOf(hackint, libera))
            repository.reorderNetworks(listOf(hackint, libera))

            assertEquals(listOf("hackint", "libera"), names())
            assertEquals(listOf(0, 1), repository.observeNetworks().first().map(NetworkEntity::ordering))
        }

    @Test
    fun `a bouncer root and its children are ranked into one flat order`() =
        runTest {
            val root =
                repository.addNetwork(
                    network("soju").copy(role = NetworkRole.BOUNCER_ROOT, host = "soju.example"),
                )
            val oftc =
                repository.addNetwork(
                    network("oftc").copy(role = NetworkRole.BOUNCER_CHILD, parentId = root, bouncerNetId = "1"),
                )
            val ergo =
                repository.addNetwork(
                    network("ergo").copy(role = NetworkRole.BOUNCER_CHILD, parentId = root, bouncerNetId = "2"),
                )
            val libera = add("libera")

            // Display order: libera, then the soju group with its children swapped.
            repository.reorderNetworks(listOf(libera, root, ergo, oftc))

            assertEquals(listOf("libera", "soju", "ergo", "oftc"), names())
        }
}
